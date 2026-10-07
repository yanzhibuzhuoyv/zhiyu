"""Experimental local recall routing; abstention always retains retrieval.

Cosine scores are similarity, not calibrated probabilities. No application
integration or knowledge-RAG decisions are made by this evaluation module.
"""

import math
from dataclasses import dataclass


def route_input(text, recent_context):
    if not isinstance(text, str) or not text.strip() or len(text) > 16000:
        raise ValueError("路由输入需要完整有效文本")
    if not isinstance(recent_context, (list, tuple)) or len(recent_context) > 4:
        raise ValueError("路由近期上下文最多四条")
    for item in recent_context:
        if (
            not isinstance(item, dict)
            or set(item) != {"role", "content"}
            or item["role"] not in {"user", "assistant"}
            or not isinstance(item["content"], str)
            or not item["content"].strip()
            or len(item["content"]) > 16000
        ):
            raise ValueError("路由近期原话格式无效")
    if not recent_context:
        return text
    context = "\n".join(
        item["role"] + "：" + item["content"] for item in recent_context
    )
    return "最近对话：\n" + context + "\n当前用户：" + text


def _normalize(vector):
    if (
        not isinstance(vector, (list, tuple))
        or not 1 <= len(vector) <= 8192
        or any(type(x) not in {float, int} or not math.isfinite(x) for x in vector)
    ):
        raise ValueError("路由向量无效")
    norm = math.hypot(*vector)
    if not math.isfinite(norm) or norm <= 0:
        raise ValueError("路由向量长度无效")
    return tuple(x / norm for x in vector)


@dataclass(frozen=True)
class RoutePolicy:
    min_similarity: float = 0.80
    min_margin: float = 0.08

    def __post_init__(self):
        for number, maximum in ((self.min_similarity, 1), (self.min_margin, 2)):
            if (
                type(number) not in {float, int}
                or not math.isfinite(number)
                or not 0 <= number <= maximum
            ):
                raise ValueError("路由阈值无效")


@dataclass(frozen=True)
class RouteDecision:
    route: str
    reason: str
    present_score: float | None = None
    retrieve_score: float | None = None


class PrototypeRouter:
    def __init__(self, encoder, prototypes, policy):
        if not isinstance(policy, RoutePolicy):
            raise TypeError("路由策略无效")
        if not isinstance(prototypes, (list, tuple)) or not 2 <= len(prototypes) <= 256:
            raise ValueError("路由示例数量无效")
        copied = []
        for item in prototypes:
            if (
                not isinstance(item, (list, tuple))
                or len(item) != 2
                or item[0] not in {"present", "retrieve"}
            ):
                raise ValueError("路由示例类别无效")
            copied.append((item[0], _normalize(item[1])))
        if {kind for kind, _ in copied} != {"present", "retrieve"} or len(
            {len(v) for _, v in copied}
        ) != 1:
            raise ValueError("路由示例需要同维度的两种类别")
        self.encoder, self.prototypes, self.policy = encoder, tuple(copied), policy
        self.dimension = len(copied[0][1])

    def scores(self, vector):
        query = _normalize(vector)
        if len(query) != self.dimension:
            raise ValueError("路由向量维度不一致")
        return {
            kind: max(
                sum(x * y for x, y in zip(query, ref, strict=True))
                for label, ref in self.prototypes
                if label == kind
            )
            for kind in ("present", "retrieve")
        }

    def _decision(self, scores):
        present, retrieve = scores["present"], scores["retrieve"]
        if (
            present >= self.policy.min_similarity
            and present - retrieve > self.policy.min_margin
        ):
            return RouteDecision("present", "present_match", present, retrieve)
        reason = "retrieve_match" if retrieve > present else "uncertain"
        return RouteDecision("retrieve", reason, present, retrieve)

    def from_scores(self, scores):
        """Replay precomputed synthetic scores using the same decision function."""
        if not isinstance(scores, (list, tuple)) or not 1 <= len(scores) <= 2:
            raise ValueError("路由评分数量无效")
        for pair in scores:
            if (
                not isinstance(pair, dict)
                or set(pair) != {"present", "retrieve"}
                or any(
                    type(value) not in {int, float}
                    or not math.isfinite(value)
                    or not -1.000001 <= value <= 1.000001
                    for value in pair.values()
                )
            ):
                raise ValueError("路由评分无效")
        decisions = [self._decision(pair) for pair in scores]
        if decisions[0].route != "present":
            return decisions[0]
        if len(decisions) == 2 and decisions[1].route != "present":
            return RouteDecision(
                "retrieve",
                "context_veto",
                decisions[0].present_score,
                decisions[0].retrieve_score,
            )
        return decisions[0]

    def decide(self, text, recent_context):
        contextual = route_input(text, recent_context)
        inputs = [text, contextual] if recent_context else [text]
        try:
            if not all(self.encoder.fits_input(item) for item in inputs):
                return RouteDecision("retrieve", "input_limit")
            vectors = self.encoder.encode_documents(inputs)
            if not isinstance(vectors, list) or len(vectors) != len(inputs):
                raise ValueError("路由模型返回数量不一致")
            return self.from_scores([self.scores(vector) for vector in vectors])
        except (OSError, ValueError, TypeError, RuntimeError, AttributeError):
            return RouteDecision("retrieve", "encoder_error")
