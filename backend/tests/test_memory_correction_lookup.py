import json
from datetime import UTC, datetime

import pytest

from app.conversation_memory import conversation_context_result, memory_terms
from app.database import ChatDatabase
from app.memory_semantic import parse_response


def claim(quote, *, topic="user:birthday", kind="profile", correction=False):
    return {"quote": quote, "kind": kind, "subject": "user", "polarity": "affirm",
            "topic_key": topic, "confidence": 0.96, "explicit_keep": False,
            "correction": correction, "promise_state": None, "date_basis": None,
            "event_date": None}


def save(chat, cid, text, claims):
    payload = json.dumps({"user": claims, "assistant": []}, ensure_ascii=False)
    created = datetime.now(UTC).isoformat()
    parsed = parse_response(payload, {"user": {"content": text, "created_at": created},
                                      "assistant": {"content": "收到", "created_at": created}})
    result = {"claims": parsed, "roles": {"user": "ok", "assistant": "ok"}, "version": "semantic-v1"}
    return chat.save_turn("owner", cid, text, "收到", [], recognition=result)


@pytest.fixture
def setup(tmp_path):
    chat = ChatDatabase(tmp_path, auto_annotations=True, annotation_mode="semantic")
    cid = chat.create_conversation("owner", "恋人", "test-model", False)["id"]
    return chat, cid


@pytest.mark.parametrize("cold", [False, True])
def test_linked_correction_replaces_same_source_candidate_head_with_exact_span(setup, cold):
    chat, cid = setup
    old = save(chat, cid, "我的生日是2000-03-06", [claim("我的生日是2000-03-06")])
    quote = "更正：我的生日是2001-04-07"
    text = "生日。" * 5000 + quote
    corrected = save(chat, cid, text, [claim(quote, correction=True)])
    if cold:
        # Exercise historical cold state; management correctly disallows cooling permanent sources.
        with chat.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            seq = db.execute("SELECT sequence FROM messages WHERE id=?", (corrected["user_message"]["id"],)).fetchone()[0]
            chat.cold.cool(db, seq, cid, text, chat.memory)
    for _ in range(25):
        chat.save_turn("owner", cid, "日常散步", "轻松休息", [])
    context = conversation_context_result(chat, "owner", cid, "2000 生日")
    refs = {ref["message_id"]: ref for ref in context.references}
    ref = refs[corrected["user_message"]["id"]]
    assert quote in ref["text"] and ref["char_start"] == 15000
    assert text[ref["char_start"]:ref["char_end"]] == ref["text"]
    assert refs[old["user_message"]["id"]]["source_current"] is False
    assert chat.list_messages("owner", cid)[2]["content"] == text


def test_each_correction_only_carries_its_actually_linked_old_turn(setup):
    chat, cid = setup
    old_birth = save(chat, cid, "我的生日是2000-03-06", [claim("我的生日是2000-03-06")])
    old_like = save(chat, cid, "我喜欢红茶", [claim("我喜欢红茶", kind="preference", topic="like:tea")])
    new_birth = save(chat, cid, "更正：生日是2001-04-07", [claim("更正：生日是2001-04-07", correction=True)])
    new_like = save(chat, cid, "更正：我如今偏爱绿茶", [claim("更正：我如今偏爱绿茶", kind="preference", topic="like:tea", correction=True)])
    result = chat.memory_lookup("owner", cid, 9, memory_terms("2000 红茶"), 256, 768)
    turns = {turn["user_id"]: turn for turn in result["turns"]}
    assert turns[new_birth["user_message"]["id"]]["correction_of"] == (old_birth["user_message"]["id"],)
    assert turns[new_like["user_message"]["id"]]["correction_of"] == (old_like["user_message"]["id"],)


def test_unrelated_superseded_topic_in_same_message_does_not_pull_its_correction(setup):
    chat, cid = setup
    text = "我的生日是2000-03-06。我喜欢红茶"
    save(chat, cid, text, [claim("我的生日是2000-03-06"), claim("我喜欢红茶", kind="preference", topic="like:tea")])
    birth = save(chat, cid, "更正：生日是2001-04-07", [claim("更正：生日是2001-04-07", correction=True)])
    like = save(chat, cid, "更正：我如今偏爱绿茶", [claim("更正：我如今偏爱绿茶", kind="preference", topic="like:tea", correction=True)])
    result = chat.memory_lookup("owner", cid, 7, memory_terms("2000"), 256, 768)
    ids = {turn["user_id"] for turn in result["turns"]}
    assert birth["user_message"]["id"] in ids
    assert like["user_message"]["id"] not in ids
