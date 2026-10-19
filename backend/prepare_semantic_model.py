"""Offline export of the pinned public BGE cache; no chat data or remote Python."""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import shutil
import time
from pathlib import Path

REPOSITORY = "BAAI/bge-small-zh-v1.5"
REVISION = "7999e1d3359715c523056ef9478215996d62a620"
SOURCE_SHA = {
    "model.safetensors": "354763b9b1357bc9c44f62c6be2276321081ed2567773608c0d0785b61d5a026",
    "config.json": "3853a7979202c348751b753e36f579c41d8da7d36af617d3d907e1fc9b441f2a",
    "vocab.txt": "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c",
    "tokenizer_config.json": "e6f3b96db926a37d4039995fbf5ad17de158dfb8f6343d607e4dbaad18d75f5a",
}
TOKENIZER_REFERENCE_SHA = {
    "tokenizer.json": "48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26",
    "special_tokens_map.json": "b6d346be366a7d1d48332dbc9fdf3bf8960b5d879522b7799ddba59e76237ee3",
}
QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："
ROOT = Path(__file__).resolve().parents[1]


def sha(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify_source(source: Path) -> None:
    if source.name != REVISION:
        raise ValueError("Source must be the pinned public-model revision")
    for filename, digest in (SOURCE_SHA | TOKENIZER_REFERENCE_SHA).items():
        if sha(source / filename) != digest:
            raise ValueError(f"Pinned source SHA mismatch: {filename}")


def export(source: Path, destination: Path) -> tuple[Path, Path]:
    import torch
    from onnxruntime.quantization import QuantType, quantize_dynamic
    from transformers import AutoModel, AutoTokenizer

    torch.set_num_threads(2)
    tokenizer = AutoTokenizer.from_pretrained(
        source, local_files_only=True, trust_remote_code=False
    )
    model = AutoModel.from_pretrained(
        source, local_files_only=True, trust_remote_code=False, attn_implementation="eager"
    ).eval()

    class ClsEncoder(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.encoder = model

        def forward(self, input_ids, attention_mask, token_type_ids):
            hidden = self.encoder(
                input_ids=input_ids,
                attention_mask=attention_mask,
                token_type_ids=token_type_ids,
                return_dict=False,
            )[0]
            return torch.nn.functional.normalize(hidden[:, 0, :], p=2, dim=1)

    encoded = tokenizer("本地语义检索导出样例", return_tensors="pt")
    float_path = destination / "bge-small-zh-v1.5-fp32.onnx"
    int_path = destination / "bge-small-zh-v1.5-int8.onnx"
    with torch.inference_mode():
        torch.onnx.export(
            ClsEncoder().eval(),
            tuple(encoded[name] for name in ("input_ids", "attention_mask", "token_type_ids")),
            str(float_path),
            input_names=["input_ids", "attention_mask", "token_type_ids"],
            output_names=["sentence_embedding"],
            dynamic_axes={
                name: {1: "sequence"} for name in ("input_ids", "attention_mask", "token_type_ids")
            },
            opset_version=17,
            dynamo=False,
            external_data=False,
        )
    print(json.dumps({"stage": "exported", "bytes": float_path.stat().st_size}), flush=True)
    quantize_dynamic(
        str(float_path),
        str(int_path),
        weight_type=QuantType.QInt8,
        per_channel=True,
        op_types_to_quantize=["MatMul", "Gather"],
        extra_options={"MatMulConstBOnly": True},
    )
    return float_path, int_path


def quality(source: Path, float_path: Path, int_path: Path, destination: Path) -> dict:
    import numpy as np
    import onnxruntime as ort
    import torch
    from transformers import AutoModel, AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(
        source, local_files_only=True, trust_remote_code=False
    )
    model = AutoModel.from_pretrained(
        source, local_files_only=True, trust_remote_code=False, attn_implementation="eager"
    ).eval()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 2
    fp = ort.InferenceSession(str(float_path), options, providers=["CPUExecutionProvider"])
    quant = ort.InferenceSession(str(int_path), options, providers=["CPUExecutionProvider"])
    documents = [
        "我一口辣的都受不了，吃火锅得选清汤。",
        "我的生日是四月二十三号。",
        "小时候爷爷带我去江边放风筝，那段回忆一直很珍贵。",
        "最近晚上翻来覆去睡不着，白天特别没精神。",
        "每次看到小狗就忍不住想抱一抱。",
        "我答应周六陪你到城东那家店涮肉。",
        "昨天我读了一本关于天文的书。",
        "我刚把阳台的衣服收进来了。",
    ]
    queries = [
        "我在饮食上有什么忌口？",
        "我是哪一天出生的？",
        "我小时候最怀念的是什么？",
        "我的作息和精神状态最近怎么样？",
        "我更亲近哪种动物？",
        "我们周末约好了做什么？",
    ]
    texts = documents + [QUERY_PREFIX + value for value in queries]
    reference = []
    floats = []
    quantized = []
    timings = []
    for text in texts:
        inputs = tokenizer(text, return_tensors="np", truncation=False)
        assert inputs["input_ids"].shape[1] <= 512
        feed = {name: value.astype(np.int64) for name, value in inputs.items()}
        with torch.inference_mode():
            hidden = model(
                **{name: torch.from_numpy(value) for name, value in feed.items()}
            ).last_hidden_state[:, 0, :]
            expected = torch.nn.functional.normalize(hidden, p=2, dim=1).numpy()[0]
        start = time.perf_counter()
        actual = quant.run(None, feed)[0][0]
        timings.append(time.perf_counter() - start)
        floats.append(fp.run(None, feed)[0][0])
        quantized.append(actual)
        reference.append(expected)
    floats = np.stack(floats)
    quantized = np.stack(quantized)
    reference = np.stack(reference)
    fp_ranking = (floats[len(documents) :] @ floats[: len(documents)].T).argmax(axis=1).tolist()
    quant_ranking = (
        (quantized[len(documents) :] @ quantized[: len(documents)].T).argmax(axis=1).tolist()
    )
    gold = list(range(len(queries)))
    scores = np.sum(reference * quantized, axis=1)
    result = {
        "gold": gold,
        "fp32_top1": fp_ranking,
        "int8_top1": quant_ranking,
        "fp32_gold_hits": sum(a == b for a, b in zip(fp_ranking, gold, strict=False)),
        "int8_gold_hits": sum(a == b for a, b in zip(quant_ranking, gold, strict=False)),
        "torch_fp32_max_abs_error": float(np.max(np.abs(reference - floats))),
        "torch_int8_cosine_min": float(scores.min()),
        "torch_int8_cosine_mean": float(scores.mean()),
        "int8_encode_seconds_min": min(timings),
        "int8_encode_seconds_max": max(timings),
        "documents": documents,
        "queries": queries,
    }
    # Explicit tail evidence: each independently encoded chunk keeps source offsets.
    long_text = "我们沿河边慢慢走，顺便讨论天气。" * 90 + "我碰到花生会过敏，千万别让我吃。"
    raw = tokenizer(
        long_text, add_special_tokens=False, return_offsets_mapping=True, truncation=False
    )
    ids = raw["input_ids"]
    segments = [ids[start : start + 510] for start in range(0, len(ids), 510)]
    vectors = []
    for segment in segments:
        sequence = np.array(
            [[tokenizer.cls_token_id, *segment, tokenizer.sep_token_id]], dtype=np.int64
        )
        vectors.append(
            quant.run(
                None,
                {
                    "input_ids": sequence,
                    "attention_mask": np.ones_like(sequence),
                    "token_type_ids": np.zeros_like(sequence),
                },
            )[0][0]
        )
    query = tokenizer(QUERY_PREFIX + "我的食物禁忌是什么？", return_tensors="np", truncation=False)
    query_vector = quant.run(None, {name: value.astype(np.int64) for name, value in query.items()})[
        0
    ][0]
    similarities = (np.stack(vectors) @ query_vector).tolist()
    result["tail_probe"] = {
        "token_count": len(ids),
        "chunk_count": len(segments),
        "scores": similarities,
        "highest_chunk": int(np.argmax(similarities)),
        "tail_chunk": len(segments) - 1,
        "tail_kept": ids[-1] == segments[-1][-1],
    }
    if result["torch_fp32_max_abs_error"] > 0.001 or result["torch_int8_cosine_min"] < 0.97:
        raise RuntimeError(f"Quantization quality gate failed: {result}")
    (destination / "quality-report.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return result


def tokenizer_fixtures(source: Path, destination: Path) -> None:
    import base64

    from transformers import AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(
        source, local_files_only=True, trust_remote_code=False
    )
    cases = [
        "我想吃苹果，今天开心呀！",
        "Hello WORLD café",
        "我😀想𠀀吃",
        "我[MASK]想[CLS]吃",
        "我\x00\u200b想\t吃\n果",
        "hellos functionally 中文",
    ]
    rows = []
    for text in cases:
        encoded = tokenizer(
            text, add_special_tokens=False, return_offsets_mapping=True, truncation=False
        )
        spans = [
            f"{len(text[:a].encode('utf-16-le')) // 2}:{len(text[:b].encode('utf-16-le')) // 2}"
            for a, b in encoded["offset_mapping"]
        ]
        rows.append(
            "\t".join(
                [
                    base64.b64encode(text.encode()).decode(),
                    ",".join(map(str, encoded["input_ids"])),
                    ",".join(spans),
                ]
            )
        )
    (destination / "tokenizer-golden.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts" / "semantic-models")
    parser.add_argument("--reuse-export", action="store_true")
    args = parser.parse_args()
    source = args.source.resolve()
    destination = args.output.resolve()
    destination.relative_to(ROOT / "artifacts")
    verify_source(source)
    destination.mkdir(parents=True, exist_ok=True)
    for filename in (
        "vocab.txt",
        "config.json",
        "tokenizer_config.json",
        "special_tokens_map.json",
    ):
        shutil.copyfile(source / filename, destination / filename)
    print(json.dumps({"stage": "source_verified", "revision": REVISION}), flush=True)
    if args.reuse_export:
        float_path = destination / "bge-small-zh-v1.5-fp32.onnx"
        int_path = destination / "bge-small-zh-v1.5-int8.onnx"
    else:
        float_path, int_path = export(source, destination)
    report = quality(source, float_path, int_path, destination)
    tokenizer_fixtures(source, destination)
    manifest = {
        "repository": REPOSITORY,
        "revision": REVISION,
        "source_sha256": SOURCE_SHA,
        "model": {"file": int_path.name, "sha256": sha(int_path), "bytes": int_path.stat().st_size},
        "fp32": {
            "file": float_path.name,
            "sha256": sha(float_path),
            "bytes": float_path.stat().st_size,
        },
        "vocab": {"file": "vocab.txt", "sha256": sha(destination / "vocab.txt")},
        "config_sha256": sha(destination / "config.json"),
        "tokenizer_config_sha256": sha(destination / "tokenizer_config.json"),
        "dimension": 512,
        "max_tokens": 512,
        "pooling": "CLS",
        "normalization": "L2",
        "query_prefix": QUERY_PREFIX,
        "tokenizer": "bert-wordpiece-exact-utf16-v1-case-preserved-no-strip-accents",
        "quantization": "ORT dynamic QInt8 per-channel MatMul constant-B + Gather",
        "export_versions": {
            name: importlib.metadata.version(name)
            for name in ("torch", "transformers", "onnx", "onnxruntime", "numpy", "ml-dtypes")
        },
    }
    (destination / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(
        json.dumps(
            {
                "stage": "complete",
                "manifest": manifest,
                "quality": {
                    name: value
                    for name, value in report.items()
                    if name not in ("queries", "documents")
                },
            }
        ),
        flush=True,
    )


if __name__ == "__main__":
    main()
