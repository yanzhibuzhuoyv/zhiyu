"""Fixed supervised fit and validation-only selection for the third experiment."""

import warnings
from types import MappingProxyType

from scripts import eval_memory_route as evaluation
from scripts.memory_route_linear import LinearPolicy, LinearRouter
from scripts.memory_route_policy import PrototypeRouter, RoutePolicy

FIT_PARAMETERS = MappingProxyType(
    {
        "C": 1.0,
        "l1_ratio": 0.0,
        "class_weight": "balanced",
        "solver": "lbfgs",
        "max_iter": 1000,
        "random_state": 0,
        "tol": 0.0001,
    }
)


def fit_head(prototypes):
    from sklearn.exceptions import ConvergenceWarning
    from sklearn.linear_model import LogisticRegression

    support = PrototypeRouter(None, prototypes, RoutePolicy())
    features = [list(vector) for label, vector in support.prototypes]
    labels = [1 if label == "present" else 0 for label, vector in support.prototypes]
    model = LogisticRegression(**FIT_PARAMETERS)
    with warnings.catch_warnings():
        warnings.simplefilter("error", ConvergenceWarning)
        model.fit(features, labels)
    if model.classes_.tolist() != [0, 1] or len(model.coef_) != 1:
        raise ValueError("分类头必须为0检索、1当下的二分类")
    coefficients, intercept = model.coef_[0].tolist(), float(model.intercept_[0])
    LinearRouter(None, prototypes, coefficients, intercept, LinearPolicy())
    return coefficients, intercept


def select_linear_policy(
    cached, prototypes, coefficients, intercept, validation, scores
):
    candidates = []
    for similarity in range(50, 91, 5):
        for affinity in range(50, 100):
            policy = LinearPolicy(similarity / 100, affinity / 100)
            router = LinearRouter(cached, prototypes, coefficients, intercept, policy)
            summary = evaluation.summarize(
                evaluation.validation_rows(router, validation, scores)
            )
            if summary["important_false_skip"] == 0:
                candidates.append(
                    (
                        summary["present_skipped"],
                        policy.min_present_affinity,
                        policy.min_similarity,
                        policy,
                        summary,
                    )
                )
    if not candidates:
        raise ValueError("验证集没有满足保守门禁的线性策略")
    _, _, _, policy, summary = max(candidates, key=lambda row: row[:3])
    router = LinearRouter(cached, prototypes, coefficients, intercept, policy)
    return (
        policy,
        summary,
        {
            "grid_candidates": 450,
            "safe_candidates": len(candidates),
            "validation_results": evaluation.validation_rows(
                router, validation, scores
            ),
            "validation_scores": [
                {"id": case["id"], "gold": case["gold"], "scores": score}
                for case, score in zip(validation, scores, strict=True)
            ],
        },
    )


def calibration(encoder, train, validation):
    cache = evaluation.calibration_cache(encoder, train, validation)
    prototypes = evaluation.training_prototypes(train, cache, False)
    if len(prototypes) != len(train):
        raise ValueError("完整训练样本必须全部能编码")
    coefficients, intercept = fit_head(prototypes)
    cached = evaluation.CachedEncoder(encoder, cache)
    baseline = LinearRouter(cached, prototypes, coefficients, intercept, LinearPolicy())
    scores = evaluation.validation_scores(baseline, validation, cache)
    policy, summary, details = select_linear_policy(
        cached, prototypes, coefficients, intercept, validation, scores
    )
    return (
        prototypes,
        coefficients,
        intercept,
        policy,
        summary,
        {
            **details,
            "prototype_count": len(prototypes),
            "calibration_inputs": len(cache),
            "training_parameters": dict(FIT_PARAMETERS),
            "positive_class": "present=1",
        },
    )
