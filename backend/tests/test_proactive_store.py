from datetime import UTC, datetime

from app.proactive_store import ProactiveStore


def test_preferences_are_off_and_owner_scoped(tmp_path):
    store = ProactiveStore(tmp_path)
    assert not store.preferences("owner", "chat")["enabled"]
    store.update("owner", "chat", {"enabled": True, "quiet_start": 22, "quiet_end": 8})
    assert store.preferences("owner", "chat")["enabled"]
    assert not store.preferences("other", "chat")["enabled"]
    assert ProactiveStore(tmp_path).preferences("owner", "chat")["enabled"]


def test_request_reservation_is_durable_and_does_not_store_original(tmp_path):
    store = ProactiveStore(tmp_path)
    now = datetime(2030, 1, 2, 4, tzinfo=UTC)
    assert store.reserve("owner", "chat", "request", "2030-01-02", now)
    assert not store.reserve("owner", "chat", "request", "2030-01-02", now)
    assert store.stats("owner", "chat", "2030-01-02")["today_count"] == 1
    store.finish("owner", "chat", "request", "a" * 32)
    assert ProactiveStore(tmp_path).event("owner", "chat", "request")["message_id"] == "a" * 32
    assert store.event("other", "chat", "request") is None
    assert "content" not in store.event("owner", "chat", "request")


def test_pending_contact_prevents_concurrent_extra_generation(tmp_path):
    store = ProactiveStore(tmp_path)
    now = datetime(2030, 1, 2, 4, tzinfo=UTC)
    assert store.reserve("owner", "chat", "first", "2030-01-02", now)
    assert not store.reserve("owner", "chat", "second", "2030-01-02", now)
