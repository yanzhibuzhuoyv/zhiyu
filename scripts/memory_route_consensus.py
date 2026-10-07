"""Conservative second candidate; one current-text prototype per train case."""

from scripts.memory_route_policy import PrototypeRouter, _normalize


class ConsensusRouter(PrototypeRouter):
    """Compare third-present cosine with best-retrieve cosine.

    A strictly positive margin implies all global nearest three are present;
    ties with retrieve abstain regardless of prototype ordering. The evaluator
    supplies only one prototype per training case, never two from Q and C.
    """

    def __init__(self, encoder, prototypes, policy):
        super().__init__(encoder, prototypes, policy)
        if sum(label == "present" for label, _ in self.prototypes) < 3:
            raise ValueError("一致性路由需要至少三个当前对话训练样本")

    def scores(self, vector):
        query = _normalize(vector)
        if len(query) != self.dimension:
            raise ValueError("路由向量维度不一致")
        scored = [
            (label, sum(x * y for x, y in zip(query, reference, strict=True)))
            for label, reference in self.prototypes
        ]
        return {
            "present": sorted(
                (score for label, score in scored if label == "present"), reverse=True
            )[2],
            "retrieve": max(score for label, score in scored if label == "retrieve"),
        }
