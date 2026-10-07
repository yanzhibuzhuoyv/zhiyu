"""Bounded hints for natural recall, independent of source truth or retrieval.

An offered source was provided to the model, not necessarily mentioned in its
answer. No evidence is removed: explicit questions and corrections must still
use the original source. Hints expire on eviction/restart; originals do not.
"""

from collections import OrderedDict
from threading import RLock

MAX_SOURCE_IDS = 8


class MemoryAttention:
    def __init__(self, *, max_scopes=4096):
        if type(max_scopes) is not int or not 1 <= max_scopes <= 4096:
            raise ValueError("记忆提示缓存容量无效")
        self._limit = max_scopes
        self._scopes: OrderedDict[tuple[str, str], frozenset[str]] = OrderedDict()
        self._lock = RLock()

    def decorate(self, owner, identifier, references):
        """Annotate copies; preserve every independent original and its fields."""
        scope = (owner, identifier)
        with self._lock:
            offered = self._scopes.get(scope, frozenset())
            if scope in self._scopes:
                self._scopes.move_to_end(scope)
        return [{**ref, "recently_offered": ref["message_id"] in offered,
                 "presentation_hint": "optional_background"} for ref in references]

    def remember(self, owner, identifier, references):
        """Call only after saving a successful turn, under that turn's lock."""
        scope = (owner, identifier)
        ids = frozenset(ref["message_id"] for ref in references[:MAX_SOURCE_IDS])
        with self._lock:
            if not ids:
                self._scopes.pop(scope, None)
                return
            self._scopes[scope] = ids
            self._scopes.move_to_end(scope)
            while len(self._scopes) > self._limit:
                self._scopes.popitem(last=False)

    def forget(self, owner, identifier):
        with self._lock:
            self._scopes.pop((owner, identifier), None)
