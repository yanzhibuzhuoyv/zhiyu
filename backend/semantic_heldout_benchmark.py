"""Fixed, synthetic heldout evaluation. This script never calibrates its threshold."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from time import perf_counter

import numpy as np
import onnxruntime as ort
from transformers import AutoTokenizer

from prepare_semantic_model import QUERY_PREFIX, REVISION, ROOT, verify_source

DOCUMENTS = [
    "我坐长途汽车容易头晕恶心，出发前通常会吃药。",
    "我一听到雷声就会紧张，喜欢有人陪在身边。",
    "我找工作时最在意团队氛围，薪水排在后面。",
    "我答应星期天替妈妈去医院拿检查结果。",
    "收到手写的小纸条会让我高兴，比贵重的礼物更打动我。",
    "我对猫毛会打喷嚏，眼睛还会发痒。",
    "我不喜欢临时改变安排，最好提前一天告诉我。",
    "压力大时我会一个人沿海边散步，听浪声就放松些。",
    "我把新买的马克杯放到书架左边了。",
    "那部科幻电影的配乐很好听。",
    "楼下的便利店换了招牌。",
    "我今天下载了一个学习软件。",
]
QUERIES = [
    "我远行时身体会出现什么不舒服？",
    "打雷时我最需要什么？",
    "择业时我把什么放在收入之前？",
    "周末我给家里安排了哪件事？",
    "什么样的心意最容易让我感动？",
    "我接近哪类宠物时可能身体不适？",
    "我对计划变动有什么要求？",
    "我通常怎么缓解紧绷的情绪？",
]
FROZEN = {
    "threshold": 0.60,
    "margin": None,
    "top_k_different_messages": 8,
    "query_chunk_aggregation": "max",
    "passage_pooling": "CLS+L2",
    "calibration_source": "Existing 6 smoke queries only; no heldout tuning",
    "note": "0.60 is the pre-existing service default, not a claimed calibrated optimum",
}


def main() -> None:
    source = (
        Path.home() / ".cache/huggingface/hub/models--BAAI--bge-small-zh-v1.5/snapshots" / REVISION
    )
    verify_source(source)
    folder = ROOT / "artifacts/semantic-models"
    manifest = folder / "manifest.json"
    assert (
        hashlib.sha256(manifest.read_bytes()).hexdigest()
        == "60fc9061b2d9460a879707aede1fa3283241c3f425311bc462227c49c5d9d292"
    )
    frozen_input = {
        "documents": DOCUMENTS,
        "queries": QUERIES,
        "expected_document": list(range(8)),
        "frozen": FROZEN,
    }
    input_bytes = json.dumps(frozen_input, ensure_ascii=False, indent=2).encode()
    input_path = folder / "heldout-input-v1.json"
    if input_path.exists() and input_path.read_bytes() != input_bytes:
        raise RuntimeError("Heldout inputs or frozen threshold changed")
    input_path.write_bytes(input_bytes)
    tokenizer = AutoTokenizer.from_pretrained(
        source, local_files_only=True, trust_remote_code=False
    )
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 2
    sessions = {
        name: ort.InferenceSession(
            str(folder / f"bge-small-zh-v1.5-{name}.onnx"),
            options,
            providers=["CPUExecutionProvider"],
        )
        for name in ("fp32", "int8")
    }
    encodings = {}
    for name, session in sessions.items():
        embeddings = []
        for text in DOCUMENTS + [QUERY_PREFIX + query for query in QUERIES]:
            inputs = tokenizer(text, return_tensors="np", truncation=False)
            embeddings.append(
                session.run(None, {key: value.astype(np.int64) for key, value in inputs.items()})[
                    0
                ][0]
            )
        vectors = np.stack(embeddings)
        scores = vectors[len(DOCUMENTS) :] @ vectors[: len(DOCUMENTS)].T
        top = scores.argmax(axis=1)
        encodings[name] = {
            "gold_hits_top1": int(sum(i == value for i, value in enumerate(top))),
            "rows": [
                {
                    "query": query,
                    "expected": index,
                    "top1": int(top[index]),
                    "gold_score": float(scores[index, index]),
                    "top1_score": float(scores[index, top[index]]),
                    "semantic_only_default_accepts": bool(
                        scores[index, top[index]] >= FROZEN["threshold"]
                    ),
                    "scores": scores[index].tolist(),
                }
                for index, query in enumerate(QUERIES)
            ],
        }
    phrase = "走在晚霞下，风吹过树梢，我想起你说过的每一句话。" * 40
    raw = tokenizer(phrase, add_special_tokens=False, truncation=False)["input_ids"][:510]
    sequence = np.array([[tokenizer.cls_token_id, *raw, tokenizer.sep_token_id]], dtype=np.int64)
    feed = {
        "input_ids": sequence,
        "attention_mask": np.ones_like(sequence),
        "token_type_ids": np.zeros_like(sequence),
    }
    latencies = []
    session = sessions["int8"]
    session.run(None, feed)
    for _ in range(10):
        start = perf_counter()
        session.run(None, feed)
        latencies.append((perf_counter() - start) * 1000)
    report = {
        "input_sha256": hashlib.sha256(input_bytes).hexdigest(),
        "frozen": FROZEN,
        "models": encodings,
        "desktop_512_token_ms": {
            "min": min(latencies),
            "mean": float(np.mean(latencies)),
            "max": max(latencies),
        },
        "warning": "Desktop CPU timings are not phone/emulator timings; do not claim 50M indexing completed from estimates",
    }
    (folder / "heldout-report-v1.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(
        json.dumps(
            {
                "input_sha256": report["input_sha256"],
                "hits": {name: value["gold_hits_top1"] for name, value in encodings.items()},
                "int8_gold_scores": [value["gold_score"] for value in encodings["int8"]["rows"]],
                "default_semantic_accepts": [
                    value["semantic_only_default_accepts"] for value in encodings["int8"]["rows"]
                ],
                "desktop_512_token_ms": report["desktop_512_token_ms"],
            }
        )
    )


if __name__ == "__main__":
    main()
