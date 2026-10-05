import json
import zlib

import pytest
from fastapi.testclient import TestClient
from test_api import FakeProvider, login

from app.config import Settings
from app.database import ChatDatabase
from app.main import create_app
from app.source_codec import (
    MAGIC,
    MAX_SOURCE_BYTES,
    ChatStorageError,
    decode_sources,
    encode_sources,
)


def source_fixture():
    return [{"document_id": f"doc-{index}", "name": "恋爱记忆.md",
             "text": "每次告别前认真说晚安，遇到分歧先倾听彼此。" * 40,
             "score": 0.123456} for index in range(5)]


@pytest.fixture
def history_setup(tmp_path):
    app = create_app(Settings(data_dir=tmp_path), provider_client=FakeProvider())
    with TestClient(app) as client:
        headers = login(client)
        owner = app.state.sessions.get(headers["Authorization"][7:]).owner
        chat = app.state.chat
        identifier = chat.create_conversation(owner, "恋人", "test-model", False)["id"]
        yield client, headers, owner, identifier, chat, app


def seed_turns(chat, owner, identifier, count=113):
    for index in range(count):
        chat.save_turn(owner, identifier, f"消息 {index}", f"回应 {index}", source_fixture())


def test_history_pages_have_no_gaps_duplicates_and_keep_old_array(history_setup):
    client, headers, owner, identifier, chat, _ = history_setup
    seed_turns(chat, owner, identifier)
    full = client.get(f"/api/conversations/{identifier}/messages", headers=headers).json()
    assert isinstance(full, list) and len(full) == 226
    before, accumulated, lengths = None, [], []
    while True:
        params = {"limit": 100}
        if before:
            params["before"] = before
        response = client.get(f"/api/conversations/{identifier}/history", headers=headers, params=params)
        assert response.status_code == 200, response.text
        page = response.json()
        lengths.append(len(page["messages"]))
        accumulated = page["messages"] + accumulated
        if not page["has_more"]:
            assert page["next_before"] is None
            break
        assert page["next_before"] == page["messages"][0]["id"]
        before = page["next_before"]
    assert lengths == [100, 100, 26]
    assert accumulated == full
    assert len({message["id"] for message in accumulated}) == 226


def test_history_cursor_is_scoped_to_conversation_and_owner(history_setup):
    client, headers, owner, identifier, chat, app = history_setup
    current = chat.save_turn(owner, identifier, "你好", "早安", [])
    second = chat.create_conversation(owner, "另一个", "test-model", False)["id"]
    another = chat.save_turn(owner, second, "私密", "回应", [])
    foreign_headers = login(client, "other-key")
    foreign_owner = app.state.sessions.get(foreign_headers["Authorization"][7:]).owner
    foreign_id = chat.create_conversation(foreign_owner, "他人", "test-model", False)["id"]
    foreign = chat.save_turn(foreign_owner, foreign_id, "私密", "回应", [])
    for cursor in (another["user_message"]["id"], foreign["user_message"]["id"], "unknown-id"):
        assert client.get(f"/api/conversations/{identifier}/history", headers=headers,
                          params={"before": cursor}).status_code == 404
    assert client.get(f"/api/conversations/{identifier}/history", headers=foreign_headers).status_code == 404
    oldest = client.get(f"/api/conversations/{identifier}/history", headers=headers,
                        params={"before": current["user_message"]["id"]}).json()
    assert oldest == {"messages": [], "has_more": False, "next_before": None}


@pytest.mark.parametrize("params", [{"limit": 0}, {"limit": 101}, {"limit": "bad"},
                                  {"before": ""}, {"before": "x" * 129}])
def test_history_external_inputs_are_validated(history_setup, params):
    client, headers, _, identifier, _, _ = history_setup
    assert client.get(f"/api/conversations/{identifier}/history", headers=headers,
                      params=params).status_code == 422


def test_history_default_limit_empty_and_new_writes_do_not_shift_cursor(history_setup):
    client, headers, owner, identifier, chat, _ = history_setup
    assert client.get(f"/api/conversations/{identifier}/history", headers=headers).json() == {
        "messages": [], "has_more": False, "next_before": None,
    }
    seed_turns(chat, owner, identifier, 51)
    page = client.get(f"/api/conversations/{identifier}/history", headers=headers).json()
    assert len(page["messages"]) == 100 and page["has_more"]
    chat.save_turn(owner, identifier, "后来新写", "新回应", [])
    earlier = client.get(f"/api/conversations/{identifier}/history", headers=headers,
                         params={"limit": 1, "before": page["next_before"]}).json()
    assert len(earlier["messages"]) == 1
    assert earlier["has_more"] is True
    assert earlier["messages"][0]["content"] == "回应 0"


def test_sources_round_trip_and_compress_only_when_worthwhile():
    assert encode_sources([]) == "[]"
    tiny = [{"text": "少量文字", "score": 0.75}]
    assert isinstance(encode_sources(tiny), str)
    sources = source_fixture()
    encoded = encode_sources(sources)
    assert isinstance(encoded, bytes) and encoded.startswith(MAGIC)
    assert len(encoded) < len(json.dumps(sources, ensure_ascii=False).encode()) * 0.25
    assert decode_sources(encoded) == sources
    assert decode_sources(json.dumps(sources, ensure_ascii=False)) == sources
    # Deterministic high-entropy data that should stay TEXT if savings are small.
    import random
    import string

    generator = random.Random(4)
    entropy = "".join(generator.choices(string.ascii_letters + string.digits, k=3000))
    noisy = [{"text": entropy}]
    assert isinstance(encode_sources(noisy), str)
    assert decode_sources(encode_sources(noisy)) == noisy


@pytest.mark.parametrize("value", [None, b"unknown-format", "not-json", "{}", "[1]",
                                 MAGIC + b"\0", MAGIC + (20).to_bytes(4, "big") + b"broken"])
def test_invalid_sources_fail_closed_without_exposing_source_text(value):
    with pytest.raises(ChatStorageError, match="聊天来源"):
        decode_sources(value)


def test_compressed_sources_validate_length_truncation_and_expansion_bound():
    encoded = encode_sources(source_fixture())
    header = len(MAGIC) + 4
    bad_length = MAGIC + (1).to_bytes(4, "big") + encoded[header:]
    too_large = MAGIC + (MAX_SOURCE_BYTES + 1).to_bytes(4, "big") + encoded[header:]
    bomb = MAGIC + (MAX_SOURCE_BYTES).to_bytes(4, "big") + zlib.compress(b"x" * (MAX_SOURCE_BYTES + 1))
    for value in (encoded[:-2], encoded + b"trailing", bad_length, too_large, bomb):
        with pytest.raises(ChatStorageError):
            decode_sources(value)
    with pytest.raises(ChatStorageError):
        decode_sources(" " * (MAX_SOURCE_BYTES + 1))
    with pytest.raises(ChatStorageError):
        encode_sources([{"text": "x" * MAX_SOURCE_BYTES}])


@pytest.mark.parametrize("value", ["\ud800", "[{\"score\":NaN}]", '[{"text":"\\ud800"}]',
                                 '[{"score":1e999}]',
                                 '[{"text":"' + "汉" * (MAX_SOURCE_BYTES // 2) + '"}]'],
                         ids=["invalid_unicode", "nonfinite_score", "escaped_surrogate",
                              "nonfinite_exponent", "large_multibyte_text"])
def test_legacy_sources_reject_bad_encoding_nonfinite_and_multibyte_size(value):
    with pytest.raises(ChatStorageError):
        decode_sources(value)


@pytest.mark.parametrize("value", [[{"score": float("inf")}], [{"text": "\ud800"}],
                                 [{"value": object()}], None])
def test_new_sources_reject_unserializable_values(value):
    with pytest.raises(ChatStorageError):
        encode_sources(value)


def test_invalid_utf8_compressed_sources_are_rejected():
    invalid = MAGIC + (1).to_bytes(4, "big") + zlib.compress(b"\xff")
    with pytest.raises(ChatStorageError):
        decode_sources(invalid)


def test_source_encoding_failure_rolls_back_the_entire_turn(history_setup):
    _, _, owner, identifier, chat, _ = history_setup
    with pytest.raises(ChatStorageError):
        chat.save_turn(owner, identifier, "我的生日", "好的", [{"text": "x" * MAX_SOURCE_BYTES}])
    assert chat.list_messages(owner, identifier) == []


def test_mixed_legacy_text_and_compressed_blob_survive_restart(history_setup, tmp_path):
    client, headers, owner, identifier, chat, _ = history_setup
    saved = chat.save_turn(owner, identifier, "白玫瑰", "回应", source_fixture())
    old_sources = [{"name": "旧文件.txt", "text": "原有来源", "score": 2.0}]
    legacy = chat.save_turn(owner, identifier, "旧格式", "回应旧格式", old_sources)
    with chat.connection() as db:
        db.execute("UPDATE messages SET sources=? WHERE id=?",
                   (json.dumps(old_sources, ensure_ascii=False), legacy["assistant_message"]["id"]))
        rows = db.execute("SELECT typeof(sources), typeof(content) FROM messages ORDER BY sequence").fetchall()
        assert [tuple(row) for row in rows] == [("text", "text"), ("blob", "text"), ("text", "text"), ("text", "text")]
    app = create_app(Settings(data_dir=tmp_path), provider_client=FakeProvider())
    with TestClient(app) as restarted:
        restored = login(restarted)
        result = restarted.get(f"/api/conversations/{identifier}/history", headers=restored).json()
        assert result["messages"][1] == saved["assistant_message"]
        assert result["messages"][3] == legacy["assistant_message"]


def test_corrupt_sources_produce_safe_503_api_error(history_setup):
    client, headers, owner, identifier, chat, _ = history_setup
    saved = chat.save_turn(owner, identifier, "问候", "回应", [])
    with chat.connection() as db:
        db.execute("UPDATE messages SET sources=? WHERE id=?",
                   ("SENSITIVE-CORRUPTED-SOURCE", saved["assistant_message"]["id"]))
    for endpoint in ("messages", "history"):
        response = client.get(f"/api/conversations/{identifier}/{endpoint}", headers=headers)
        assert response.status_code == 503
        assert "SENSITIVE-CORRUPTED-SOURCE" not in response.text


def test_synthetic_database_footprint_is_smaller_without_changing_messages(tmp_path):
    compressed = ChatDatabase(tmp_path / "compressed")
    legacy = ChatDatabase(tmp_path / "legacy")
    compact_id = compressed.create_conversation("owner", "恋人", "model", True)["id"]
    legacy_id = legacy.create_conversation("owner", "恋人", "model", True)["id"]
    sources = source_fixture()
    old_json = json.dumps(sources, ensure_ascii=False)
    for index in range(160):
        compact_turn = compressed.save_turn("owner", compact_id, f"问候 {index}", "晚安", sources)
        legacy_turn = legacy.save_turn("owner", legacy_id, f"问候 {index}", "晚安", sources)
        with legacy.connection() as db:
            db.execute("UPDATE messages SET sources=? WHERE id=?",
                       (old_json, legacy_turn["assistant_message"]["id"]))
        assert compact_turn["assistant_message"]["sources"] == legacy_turn["assistant_message"]["sources"]
    for chat in (compressed, legacy):
        with chat.connection() as db:
            db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    compressed_size, legacy_size = compressed.path.stat().st_size, legacy.path.stat().st_size
    assert compressed_size < legacy_size * 0.4
    compact_messages = compressed.list_messages("owner", compact_id)
    old_messages = legacy.list_messages("owner", legacy_id)
    assert [(m["role"], m["content"], m["sources"]) for m in compact_messages] == [
        (m["role"], m["content"], m["sources"]) for m in old_messages
    ]
    print(f"synthetic_sources_160_turns compressed_db={compressed_size} legacy_db={legacy_size}")
