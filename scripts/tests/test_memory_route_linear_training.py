"""Fixed training inputs and validation selection, using a mocked optimizer."""

import sys
import warnings
from types import SimpleNamespace

import pytest

from scripts import eval_memory_route as evaluation
from scripts.memory_route_linear import LinearPolicy, LinearRouter
from scripts.memory_route_linear_training import (
    FIT_PARAMETERS,
    fit_head,
    select_linear_policy,
)


class Array(list):
    def tolist(self):
        return list(self)


class ConvergenceWarning(UserWarning):
    pass


def install_optimizer(monkeypatch, called, warning=False, classes=(0, 1)):
    class Optimizer:
        def __init__(self, **kwargs):
            called.append(kwargs)

        def fit(self, x, y):
            called.append((x, y))
            if warning:
                warnings.warn(
                    "PRIVATE synthetic warning", ConvergenceWarning, stacklevel=1
                )
            self.coef_, self.intercept_, self.classes_ = (
                [Array([2, -2])],
                [0.25],
                Array(classes),
            )
            return self

    monkeypatch.setitem(
        sys.modules,
        "sklearn.linear_model",
        SimpleNamespace(LogisticRegression=Optimizer),
    )
    monkeypatch.setitem(
        sys.modules,
        "sklearn.exceptions",
        SimpleNamespace(ConvergenceWarning=ConvergenceWarning),
    )


def test_fit_is_fixed_l2_and_positive_class_is_present(monkeypatch):
    called = []
    install_optimizer(monkeypatch, called)
    result = fit_head([("present", [2, 0]), ("retrieve", [0, 3])])
    assert result == ([2, -2], 0.25)
    assert called[0] == dict(FIT_PARAMETERS) and called[1] == ([[1, 0], [0, 1]], [1, 0])
    with pytest.raises(TypeError):
        FIT_PARAMETERS["C"] = 10


def test_incomplete_fit_or_reversed_class_mapping_cannot_be_serialized(monkeypatch):
    called = []
    install_optimizer(monkeypatch, called, classes=(1, 0))
    with pytest.raises(ValueError):
        fit_head([("present", [1, 0]), ("retrieve", [0, 1])])


def test_nonconvergence_is_an_error_not_a_silent_trained_model(monkeypatch):
    install_optimizer(monkeypatch, [], warning=True)
    with pytest.raises(ConvergenceWarning):
        fit_head([("present", [1, 0]), ("retrieve", [0, 1])])


def test_threshold_selection_uses_only_validation_scores_and_keeps_timing_null():
    prototypes = [("present", [1, 0]), ("retrieve", [0, 1])]
    validation = [
        {"id": "v0", "category": "contract", "gold": "present"},
        {"id": "v1", "category": "contract", "gold": "retrieve"},
    ]
    scores = [
        [{"present_affinity": 0.85, "nearest_similarity": 0.85}],
        [{"present_affinity": 0.70, "nearest_similarity": 0.85}],
    ]
    policy, summary, details = select_linear_policy(
        None, prototypes, [2, -2], 0, validation, scores
    )
    assert policy == LinearPolicy(0.85, 0.84)
    assert summary["important_false_skip"] == 0 and summary["present_skipped"] == 1
    assert summary["milliseconds_p95"] is None and details["grid_candidates"] == 450
    assert evaluation.summarize(details["validation_results"]) == summary
    assert len(details["validation_scores"]) == 2
    assert (
        LinearRouter(None, prototypes, [2, -2], 0, policy).from_scores(scores[1]).route
        == "retrieve"
    )
