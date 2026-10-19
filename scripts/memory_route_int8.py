"""Desktop-only candidate: exact native INT8/query representation, fail closed.

No application integration, user history, credentials, downloads or training here.
"""
import hashlib
import json
import math
import os
import re
from pathlib import Path

from scripts.memory_route_linear import LinearDecision, LinearRouter
from scripts.memory_route_policy import route_input

ROOT = Path(__file__).resolve().parents[1]
REVISION = "7999e1d3359715c523056ef9478215996d62a620"
MODEL_SHA = "a20c8811f4c52f72f31783c615b6a6ea2eaac6b8b52eea5a107e004f4cc632f7"
VOCAB_SHA = "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c"
CONFIG_SHA = "3853a7979202c348751b753e36f579c41d8da7d36af617d3d907e1fc9b441f2a"
TOKENIZER_SHA = "e6f3b96db926a37d4039995fbf5ad17de158dfb8f6343d607e4dbaad18d75f5a"
PREFIX = "为这个句子生成表示以用于检索相关文章："
MODEL_IDENTITY = (f"bge-small-zh-v1.5:{REVISION}:qint8:{MODEL_SHA}:wp-utf16-v1:"
    f"{VOCAB_SHA}:{CONFIG_SHA}:{TOKENIZER_SHA}:case-preserved:accents-preserved:"
    "cls:l2:prefix-sha256:" + hashlib.sha256(PREFIX.encode()).hexdigest()
    + ":d512:chunk510-source8192-v2")


def eligible_input(text, token_count):
    if not isinstance(text, str) or not text.strip():
        return False
    if len(text.encode("utf-16-le")) // 2 > 8192:
        return False
    # WordPiece maps words longer than 100 code points to one UNK. Refuse that
    # whole input rather than treating the one token as understanding its tail.
    if any(len(word) > 100 for word in re.findall(r"[A-Za-z0-9_]+", text)):
        return False
    count = token_count(text)
    return type(count) is int and 3 <= count <= 512


class ResearchRouter(LinearRouter):
    def __init__(self, encoder, prototypes, coefficients, intercept, policy, identity):
        super().__init__(encoder, prototypes, coefficients, intercept, policy)
        self.profile_identity = identity

    def decide(self, text, recent_context):
        if self.profile_identity != MODEL_IDENTITY or getattr(
            self.encoder, "model_identity", None) != MODEL_IDENTITY:
            return LinearDecision("retrieve", "model_identity_mismatch")
        try:
            route_input(text, recent_context)
            return super().decide(text, recent_context)
        except (ValueError, TypeError, UnicodeError):
            return LinearDecision("retrieve", "invalid_complete_input")


class Int8QueryEncoder:
    model_identity = MODEL_IDENTITY

    def __init__(self):
        os.environ["HF_HUB_OFFLINE"] = "1"
        os.environ["TRANSFORMERS_OFFLINE"] = "1"
        os.environ["TOKENIZERS_PARALLELISM"] = "false"
        import onnxruntime as ort
        from transformers import AutoTokenizer

        source = Path.home() / ".cache/huggingface/hub/models--BAAI--bge-small-zh-v1.5/snapshots" / REVISION
        model = ROOT / "artifacts/semantic-models/bge-small-zh-v1.5-int8.onnx"
        for path, expected in ((model, MODEL_SHA), (source / "vocab.txt", VOCAB_SHA),
            (source / "config.json", CONFIG_SHA), (source / "tokenizer_config.json", TOKENIZER_SHA)):
            if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
                raise ValueError("Research model identity check failed")
        self.tokenizer = AutoTokenizer.from_pretrained(source, local_files_only=True,
            trust_remote_code=False, use_fast=False)
        config = json.loads(self.tokenizer.backend_tokenizer.normalizer.__getstate__())
        if config.get("lowercase") is not False or config.get("strip_accents") is not None or config.get("handle_chinese_chars") is not True:
            raise ValueError("Native case/accent preservation mismatch")
        self.prefix_ids = self.tokenizer.encode(PREFIX, add_special_tokens=False)
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 2
        self.session = ort.InferenceSession(str(model), options, providers=["CPUExecutionProvider"])

    def input_ids(self, text):
        return [self.tokenizer.cls_token_id, *self.prefix_ids,
            *self.tokenizer.encode(text, add_special_tokens=False, truncation=False),
            self.tokenizer.sep_token_id]

    def fits_input(self, text):
        return eligible_input(text, lambda value: len(self.input_ids(value)))

    def encode_documents(self, texts):
        # The legacy evaluation interface name is encode_documents, but all
        # training/Q/C inputs HERE use the exact retrieval QUERY prefix.
        import numpy as np
        vectors = []
        for text in texts:
            if not self.fits_input(text):
                raise ValueError("Refused incomplete/oversized research input")
            ids = np.asarray([self.input_ids(text)], dtype=np.int64)
            feed = {"input_ids": ids, "attention_mask": np.ones_like(ids),
                "token_type_ids": np.zeros_like(ids)}
            value = self.session.run(None, feed)[0]
            if value.shape != (1, 512) or not np.isfinite(value).all():
                raise ValueError("Invalid research ONNX output")
            norm = float(np.linalg.norm(value[0]))
            if not math.isfinite(norm) or norm <= 0:
                raise ValueError("Invalid research vector norm")
            vectors.append((value[0] / norm).astype(float).tolist())
        return vectors
