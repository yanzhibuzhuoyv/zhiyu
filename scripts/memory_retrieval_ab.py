"""Isolated original-fragment cascades; ranking fusion never merges memories."""

import hashlib
import json
import re
import sqlite3
import sys
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
from app.conversation_memory import memory_terms  # noqa: E402 - shared backend path setup
from app.vendor_rag.bm25 import _tokenize  # noqa: E402

CANDIDATES = 16
PAIRS = 4
CHAR_BUDGET = 6144
GATE_COVERAGE = 0.75
COMPOUND = re.compile(r"[、；;]|分别|以及|同时|还是|和|但是|不过")
UNCERTAIN = re.compile(
    r"[？?]|如果|假如|假设|听说|朋友说|同事说|他(?:说|的)|她(?:说|的)|没有|没说|不是"
)


def original_digest(messages):
    data = json.dumps(messages, ensure_ascii=False, sort_keys=True).encode()
    return hashlib.sha256(data).hexdigest()


@dataclass(frozen=True)
class Original:
    id: str
    role: str
    text: str
    created_at: str


@dataclass(frozen=True)
class Fragment:
    message: int
    start: int
    end: int


def validate_originals(messages):
    if not messages or len(messages) % 2:
        raise ValueError("必须提供完整成对合成原文")
    originals, seen = [], set()
    for i, m in enumerate(messages):
        if any(
            not isinstance(m.get(k), str) or not m[k] for k in ("id", "role", "text", "created_at")
        ):
            raise ValueError("原文字段无效")
        if m["id"] in seen or m["role"] != ("user" if i % 2 == 0 else "assistant"):
            raise ValueError("原文ID或角色无效")
        if datetime.fromisoformat(m["created_at"]).tzinfo is None:
            raise ValueError("原文时间必须带时区")
        originals.append(Original(**{k: m[k] for k in ("id", "role", "text", "created_at")}))
        seen.add(m["id"])
    return tuple(originals)


def checked_spans(text, encoder, *, query=False):
    spans = encoder.spans(text, query=query)
    covered = 0
    for start, end in spans:
        if (
            type(start) is not int
            or type(end) is not int
            or not 0 <= start < end <= len(text)
            or start > covered
            or end <= covered
        ):
            raise ValueError("分窗没有覆盖完整原文")
        covered = end
    if covered != len(text):
        raise ValueError("原文尾部丢失")
    return spans


def checked_vectors(values, count, dimension=None):
    matrix = np.asarray(values, dtype=np.float32)
    if (
        matrix.ndim != 2
        or matrix.shape[0] != count
        or matrix.shape[1] < 1
        or not np.isfinite(matrix).all()
        or (dimension and matrix.shape[1] != dimension)
    ):
        raise ValueError("向量数量、维度或数值无效")
    norms = np.linalg.norm(matrix, axis=1)
    if not np.allclose(norms, 1, atol=0.001):
        raise ValueError("向量未归一化")
    return matrix


class RetrievalIndex:
    def __init__(self, messages, encoder, scope="synthetic"):
        if not isinstance(scope, str) or not scope:
            raise ValueError("scope无效")
        self.originals = validate_originals(messages)
        self.encoder, self.scope = encoder, scope
        fragments, texts = [], []
        for i, original in enumerate(self.originals):
            for start, end in checked_spans(original.text, encoder):
                fragments.append(Fragment(i, start, end))
                texts.append(original.text[start:end])
        self.fragments = tuple(fragments)
        self.vectors = checked_vectors(encoder.encode_documents(texts), len(texts))
        self.vector_bytes = self.vectors.nbytes
        self.db = sqlite3.connect(":memory:")
        self.db.execute("CREATE VIRTUAL TABLE terms USING fts5(postings)")
        self.db.executemany(
            "INSERT INTO terms(rowid,postings) VALUES (?,?)",
            [
                (
                    i + 1,
                    " ".join("t" + t.encode().hex() for t in set(_tokenize(text)) if len(t) > 1),
                )
                for i, text in enumerate(texts)
            ],
        )
        self.db.commit()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.db.close()

    def lexical(self, query):
        terms = memory_terms(query)
        if not terms:
            return []
        expression = " OR ".join('"t' + t.encode().hex() + '"' for t in terms)
        rows = self.db.execute(
            "SELECT rowid FROM terms WHERE terms MATCH ? ORDER BY bm25(terms), rowid LIMIT ?",
            (expression, CANDIDATES),
        )
        return [row[0] - 1 for row in rows]

    def semantic(self, query):
        spans = checked_spans(query, self.encoder, query=True)
        values = [self.encoder.encode_query(query[s:e]) for s, e in spans]
        vectors = checked_vectors(values, len(spans), self.vectors.shape[1])
        # Each full query window searches the complete scope, including zero-lexical hits.
        scores = (self.vectors @ vectors.T).max(axis=1)
        ranked = np.argsort(-scores, kind="stable")[:CANDIDATES]
        return [int(i) for i in ranked], len(spans)

    def selected(self, rankings):
        pair_scores, preferred = {}, {}
        # Canonical stage order makes union results independent of execution order.
        for stage in ("lexical", "semantic"):
            seen_pairs = set()
            for rank, position in enumerate(rankings.get(stage, []), 1):
                fragment = self.fragments[position]
                pair = fragment.message // 2
                contribution = 1 / (60 + rank)
                if pair not in seen_pairs:
                    pair_scores[pair] = pair_scores.get(pair, 0) + contribution
                    seen_pairs.add(pair)
                key = fragment.message
                if key not in preferred or contribution > preferred[key][0]:
                    preferred[key] = (contribution, fragment)
        ordered = sorted(pair_scores, key=lambda p: (-pair_scores[p], p))
        refs, remaining = [], CHAR_BUDGET
        for pair in ordered[:PAIRS]:
            values = [
                self.reference(i, preferred.get(i), pair_scores[pair])
                for i in (pair * 2, pair * 2 + 1)
            ]
            length = sum(len(r["text"]) for r in values)
            if length <= remaining:
                refs.extend(values)
                remaining -= length
        return refs

    def reference(self, index, preferred, score):
        original = self.originals[index]
        fragment = preferred[1] if preferred else Fragment(index, 0, min(384, len(original.text)))
        return {
            "message_id": original.id,
            "turn_id": self.originals[index // 2 * 2].id,
            "role": original.role,
            "created_at": original.created_at,
            "text": original.text[fragment.start : fragment.end],
            "char_start": fragment.start,
            "char_end": fragment.end,
            "truncated": fragment.start != 0 or fragment.end != len(original.text),
            "source_current": None,
            "memory_tier": "mid",
            "reason": "independent_original_candidate",
            "relation_confirmed": False,
            "score": round(score, 8),
        }

    def gate(self, query, refs):
        terms = memory_terms(query)
        user = [r["text"] for r in refs if r["role"] == "user"]
        covered = {t for t in terms if any(t in text for text in user)}
        coverage = len(covered) / len(terms) if terms else 0
        reasons = []
        if len(terms) < 2:
            reasons.append("few_query_terms")
        if COMPOUND.search(query) or query.count("？") + query.count("?") > 1:
            reasons.append("compound_query")
        if len(query) > 256 or len(terms) == 32:
            reasons.append("long_or_capped_query")
        if not user or coverage < GATE_COVERAGE:
            reasons.append("low_user_term_coverage")
        if not any(not UNCERTAIN.search(t) and any(term in t for term in terms) for t in user):
            reasons.append("uncertain_source")
        return coverage, reasons

    def retrieve(self, query, order, *, scope="synthetic"):
        if scope != self.scope or order not in {"lexical-first", "semantic-first", "both"}:
            raise ValueError("scope或顺序无效")
        if not isinstance(query, str) or not query.strip() or len(query) > 16000:
            raise ValueError("query无效")
        start, rankings, timings = time.perf_counter(), {}, {}
        stages = ["semantic", "lexical"] if order == "semantic-first" else ["lexical", "semantic"]
        coverage, reasons, windows = 0, [], 0
        for number, stage in enumerate(stages):
            before = time.perf_counter()
            if stage == "lexical":
                rankings[stage] = self.lexical(query)
            else:
                rankings[stage], windows = self.semantic(query)
            timings[stage] = (time.perf_counter() - before) * 1000
            refs = self.selected(rankings)
            if number == 0:
                coverage, reasons = self.gate(query, refs)
                if order != "both" and not reasons:
                    break
        return {
            "order": order,
            "stages": list(timings),
            "candidate_counts": {k: len(v) for k, v in rankings.items()},
            "stage_ms": timings,
            "elapsed_ms": (time.perf_counter() - start) * 1000,
            "stopped_after_first": len(timings) == 1,
            "first_user_term_coverage": coverage,
            "trigger_reasons": reasons,
            "query_windows": windows,
            "references": refs,
        }

    def save_index(self, path):
        target = sqlite3.connect(path)
        try:
            self.db.backup(target)
        finally:
            target.close()


class ResearchEncoder:
    """Cached documents only; query encoding is timed afresh in either order."""

    def __init__(self, directory):
        from scripts.eval_memory_route import BgeEncoder

        self.base = BgeEncoder(directory)
        self.cache = {}

    def spans(self, text, *, query=False):
        tokenizer = self.base.model.tokenizer
        offsets = tokenizer(
            text, add_special_tokens=False, truncation=False, return_offsets_mapping=True
        )["offset_mapping"]
        if not offsets:
            return [(0, len(text))]
        spans = []
        for first in range(0, len(offsets), 320):
            last = min(first + 384, len(offsets))
            start = 0 if first == 0 else offsets[first][0]
            end = len(text) if last == len(offsets) else offsets[last][0]
            fragment = text[start:end]
            prefix = self.base.encoder._query_prefix if query else ""
            if not self.base.fits_input(prefix + fragment):
                raise ValueError("模型窗口超长，拒绝静默截断")
            spans.append((start, end))
            if last == len(offsets):
                break
        return spans

    def encode_documents(self, texts):
        missing = list(dict.fromkeys(t for t in texts if t not in self.cache))
        for offset in range(0, len(missing), 128):
            batch = missing[offset : offset + 128]
            self.cache.update(zip(batch, self.base.encode_documents(batch), strict=True))
        return [self.cache[t] for t in texts]

    def encode_query(self, text):
        return self.base.encoder.encode_query(text)
