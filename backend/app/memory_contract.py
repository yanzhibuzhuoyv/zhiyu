"""Execution state of a bounded lookup, separate from conclusions about the user."""

from enum import StrEnum

from .companion_contract_generated import RETRIEVAL_STATES


class RetrievalState(StrEnum):
    OK = "ok"
    NOT_SEARCHED = "not_searched"
    PARTIAL = "partial"


assert tuple(state.value for state in RetrievalState) == RETRIEVAL_STATES


def completed_state(*completed: bool) -> RetrievalState:
    if not completed or any(type(item) is not bool for item in completed):
        raise ValueError("retrieval completion observations must be booleans")
    return RetrievalState.OK if all(completed) else RetrievalState.PARTIAL


def resolve_state(state: RetrievalState | str | None, legacy_complete: bool | None) -> RetrievalState:
    """Old experiment callers can explicitly supply a bool; omitted state is unknown."""
    if legacy_complete is not None and type(legacy_complete) is not bool:
        raise ValueError("invalid legacy retrieval completion observation")
    resolved = RetrievalState(state) if state is not None else None
    legacy = completed_state(legacy_complete) if legacy_complete is not None else None
    if resolved is not None and legacy is not None and resolved != legacy:
        raise ValueError("conflicting retrieval execution observations")
    return resolved or legacy or RetrievalState.NOT_SEARCHED
