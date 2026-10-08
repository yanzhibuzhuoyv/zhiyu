import asyncio
from concurrent.futures import ThreadPoolExecutor

import pytest
from test_media_assets import image_bytes

from app.media_assets import MediaLibraries
from app.media_models import VisualUpdate
from app.visual_generation import GenerationError, generate_visual
from app.visual_profiles import VisualConflict, VisualProfiles

OWNER = "a" * 64


def test_visual_profile_is_separate_and_optional(tmp_path):
    store = VisualProfiles(tmp_path)
    before = store.get(OWNER, "chat", configured=False)
    assert before["mode"] == "none" and before["reference_id"] is None
    assert before["generation"]["remaining"] == 2
    result = store.update(OWNER, "chat", VisualUpdate(mode="generate", description="我的原创角色，短发"))
    assert result["mode"] == "generate"
    assert result["visual_version"] == 1
    assert "style" not in result and "instructions" not in result
    assert VisualProfiles(tmp_path).get(OWNER, "chat")["visual_id"] == result["visual_id"]
    assert store.get("b" * 64, "chat")["mode"] == "none"


def test_quota_is_owner_scoped_persistent_atomic_and_idempotent(tmp_path):
    store = VisualProfiles(tmp_path)
    store.update(OWNER, "chat", VisualUpdate(mode="generate", description="原创角色"))

    def reserve(index):
        try:
            return store.reserve(OWNER, "chat" + str(index), "req" + str(index), "人物")
        except VisualConflict:
            return None

    with ThreadPoolExecutor(max_workers=8) as executor:
        reservations = list(executor.map(reserve, range(8)))
    assert sum(value is not None for value in reservations) == 2
    stats = VisualProfiles(tmp_path).get(OWNER, "other")["generation"]
    assert stats["pending"] == 2 and stats["remaining"] == 0
    first = next(value for value in reservations if value)
    same = store.reserve(OWNER, first["conversation_id"], first["request_id"], "人物")
    assert same["id"] == first["id"] and same["replayed"] is True
    with pytest.raises(VisualConflict):
        store.reserve(OWNER, first["conversation_id"], first["request_id"], "另一段说明")
    store.fail(first["id"])
    assert store.get(OWNER, "chat")["generation"]["remaining"] == 1


@pytest.mark.asyncio
async def test_unconfigured_generator_returns_503_without_using_chance(tmp_path):
    store = VisualProfiles(tmp_path)
    assets = MediaLibraries(tmp_path / "libraries")
    with pytest.raises(GenerationError) as raised:
        await generate_visual(store, assets, None, OWNER, "chat", "test-id", "原创角色")
    assert raised.value.status_code == 503
    assert store.get(OWNER, "chat")["generation"]["remaining"] == 2


@pytest.mark.asyncio
async def test_successful_generation_replays_once_two_total_across_chats(tmp_path):
    store = VisualProfiles(tmp_path)
    assets = MediaLibraries(tmp_path / "libraries")
    calls = []

    async def fake(description, **identity):
        calls.append((description, identity))
        return image_bytes("blue")

    first = await generate_visual(store, assets, fake, OWNER, "chat", "once", "原创短发角色")
    replayed = await generate_visual(store, assets, fake, OWNER, "chat", "once", "原创短发角色")
    assert first["reference_id"] == replayed["reference_id"]
    assert len(calls) == 1 and first["generation"]["used"] == 1
    await generate_visual(store, assets, fake, OWNER, "chat2", "twice", "原创短发角色")
    with pytest.raises(GenerationError) as raised:
        await generate_visual(store, assets, fake, OWNER, "chat3", "thrice", "原创短发角色")
    assert raised.value.status_code == 409
    assert len(calls) == 2


@pytest.mark.asyncio
async def test_generator_failure_refunds_and_pending_blocks_same_request(tmp_path):
    store = VisualProfiles(tmp_path)
    assets = MediaLibraries(tmp_path / "libraries")
    entered, release = asyncio.Event(), asyncio.Event()

    async def delayed(description, **identity):
        entered.set()
        await release.wait()
        raise RuntimeError("private upstream credentials must not leak")

    task = asyncio.create_task(generate_visual(store, assets, delayed, OWNER, "chat", "pending", "原创角色"))
    await entered.wait()
    with pytest.raises(GenerationError) as raised:
        await generate_visual(store, assets, delayed, OWNER, "chat", "pending", "原创角色")
    assert raised.value.status_code == 409
    with pytest.raises(VisualConflict):
        store.update(OWNER, "chat", VisualUpdate(mode="none"))
    release.set()
    with pytest.raises(GenerationError) as raised:
        await task
    assert "private upstream" not in str(raised.value)
    assert store.get(OWNER, "chat")["generation"]["remaining"] == 2


def test_visual_profile_unknown_fields_cannot_unlock_personality():
    with pytest.raises(ValueError):
        VisualUpdate(mode="import", style="playful")
