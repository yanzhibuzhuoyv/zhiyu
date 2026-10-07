"""Experimental supervised intent head; values are not calibrated probabilities."""

import math
from dataclasses import dataclass

from scripts.memory_route_policy import PrototypeRouter, RoutePolicy, route_input


@dataclass(frozen=True)
class LinearPolicy:
    min_similarity: float = 0.60
    min_present_affinity: float = 0.90

    def __post_init__(self):
        for value in (self.min_similarity, self.min_present_affinity):
            if (
                type(value) not in {int, float}
                or not math.isfinite(value)
                or not 0 <= value <= 1
            ):
                raise ValueError("线性路由阈值无效")
        if self.min_present_affinity < 0.5:
            raise ValueError("线性路由需要保守的当前意图阈值")


@dataclass(frozen=True)
class LinearDecision:
    route: str
    reason: str
    present_affinity: float | None = None
    nearest_similarity: float | None = None


class LinearRouter:
    def __init__(self, encoder, prototypes, coefficients, intercept, policy):
        self.support = PrototypeRouter(encoder, prototypes, RoutePolicy())
        self.encoder, self.dimension = encoder, self.support.dimension
        if not isinstance(policy, LinearPolicy):
            raise TypeError("线性路由策略无效")
        if (
            not isinstance(coefficients, (list, tuple))
            or len(coefficients) != self.dimension
        ):
            raise ValueError("分类头维度无效")
        if any(
            type(x) not in {int, float} or not math.isfinite(x)
            for x in (*coefficients, intercept)
        ):
            raise ValueError("分类头系数无效")
        self.coefficients, self.intercept = tuple(coefficients), intercept
        self.policy = policy

    def scores(self, vector):
        # The reference router validates/normalizes the same encoder vector.
        from scripts.memory_route_policy import _normalize

        normalized = _normalize(vector)
        similarity = max(self.support.scores(normalized).values())
        logit = (
            sum(x * y for x, y in zip(normalized, self.coefficients, strict=True))
            + self.intercept
        )
        if not math.isfinite(logit):
            raise ValueError("分类头数值溢出")
        affinity = (
            1 / (1 + math.exp(-logit))
            if logit >= 0
            else math.exp(logit) / (1 + math.exp(logit))
        )
        return {"present_affinity": affinity, "nearest_similarity": similarity}

    def _decision(self, scores):
        affinity, nearest = scores["present_affinity"], scores["nearest_similarity"]
        if nearest < self.policy.min_similarity:
            return LinearDecision("retrieve", "low_support", affinity, nearest)
        if affinity > self.policy.min_present_affinity:
            return LinearDecision("present", "linear_present", affinity, nearest)
        return LinearDecision("retrieve", "uncertain", affinity, nearest)

    def from_scores(self, scores):
        if not isinstance(scores, (list, tuple)) or not 1 <= len(scores) <= 2:
            raise ValueError("线性路由评分数量无效")
        for pair in scores:
            if not isinstance(pair, dict) or set(pair) != {
                "present_affinity",
                "nearest_similarity",
            }:
                raise ValueError("线性路由评分格式无效")
            if any(
                type(x) not in {int, float} or not math.isfinite(x)
                for x in pair.values()
            ):
                raise ValueError("线性路由评分无效")
            if (
                not 0 <= pair["present_affinity"] <= 1
                or not -1.000001 <= pair["nearest_similarity"] <= 1.000001
            ):
                raise ValueError("线性路由评分范围无效")
        decisions = [self._decision(pair) for pair in scores]
        if decisions[0].route != "present":
            return decisions[0]
        if len(decisions) == 2 and decisions[1].route != "present":
            return LinearDecision(
                "retrieve",
                "context_veto",
                decisions[0].present_affinity,
                decisions[0].nearest_similarity,
            )
        return decisions[0]

    def decide(self, text, recent_context):
        contextual = route_input(text, recent_context)
        inputs = [text, contextual] if recent_context else [text]
        try:
            if not all(self.encoder.fits_input(item) for item in inputs):
                return LinearDecision("retrieve", "input_limit")
            vectors = self.encoder.encode_documents(inputs)
            if not isinstance(vectors, list) or len(vectors) != len(inputs):
                raise ValueError("线性路由模型返回数量不一致")
            return self.from_scores([self.scores(vector) for vector in vectors])
        except (OSError, ValueError, TypeError, RuntimeError, AttributeError):
            return LinearDecision("retrieve", "encoder_error")
