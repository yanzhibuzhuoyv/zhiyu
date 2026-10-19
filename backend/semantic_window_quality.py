"""Check dynamic ONNX windows against the pinned original model; never writes its manifest."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from transformers import AutoModel, AutoTokenizer

from prepare_semantic_model import REVISION, ROOT, sha, verify_source


def main() -> None:
    source = (
        Path.home() / ".cache/huggingface/hub/models--BAAI--bge-small-zh-v1.5/snapshots" / REVISION
    )
    verify_source(source)
    folder = ROOT / "artifacts/semantic-models"
    assert (
        sha(folder / "manifest.json")
        == "60fc9061b2d9460a879707aede1fa3283241c3f425311bc462227c49c5d9d292"
    )
    tokenizer = AutoTokenizer.from_pretrained(
        source, local_files_only=True, trust_remote_code=False
    )
    torch.set_num_threads(2)
    model = AutoModel.from_pretrained(
        source, local_files_only=True, trust_remote_code=False, attn_implementation="eager"
    ).eval()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 2
    fp = ort.InferenceSession(
        str(folder / "bge-small-zh-v1.5-fp32.onnx"), options, providers=["CPUExecutionProvider"]
    )
    quant = ort.InferenceSession(
        str(folder / "bge-small-zh-v1.5-int8.onnx"), options, providers=["CPUExecutionProvider"]
    )
    long = "走在晚霞下，风吹过树梢，我想起你说过的每一句话。" * 40
    ids = tokenizer(long, add_special_tokens=False, truncation=False)["input_ids"]
    rows = []
    for count in (1, 15, 510):
        sequence = np.array(
            [[tokenizer.cls_token_id, *ids[:count], tokenizer.sep_token_id]], dtype=np.int64
        )
        feed = {
            "input_ids": sequence,
            "attention_mask": np.ones_like(sequence),
            "token_type_ids": np.zeros_like(sequence),
        }
        with torch.inference_mode():
            hidden = model(
                **{name: torch.from_numpy(value) for name, value in feed.items()}
            ).last_hidden_state[:, 0, :]
            reference = torch.nn.functional.normalize(hidden, p=2, dim=1).numpy()[0]
        actual_fp = fp.run(None, feed)[0][0]
        actual_quant = quant.run(None, feed)[0][0]
        error = float(np.max(np.abs(reference - actual_fp)))
        cosine = float(reference @ actual_quant)
        assert error < 0.001 and cosine > 0.97, (count, error, cosine)
        assert (
            np.isfinite(actual_quant).all()
            and abs(float(actual_quant @ actual_quant) - 1) < 0.00001
        )
        rows.append(
            {
                "sequence_tokens": count + 2,
                "content_tokens": count,
                "torch_fp32_max_abs_error": error,
                "torch_int8_cosine": cosine,
                "int8_norm_squared": float(actual_quant @ actual_quant),
            }
        )
    report = {
        "model_sha": sha(folder / "bge-small-zh-v1.5-int8.onnx"),
        "windows": rows,
        "note": "Public synthetic phrase dynamic-window numerical parity; not retrieval quality or full-corpus performance.",
    }
    (folder / "window-quality-report.json").write_text(
        json.dumps(report, indent=2), encoding="utf-8"
    )
    print(json.dumps(report))


if __name__ == "__main__":
    main()
