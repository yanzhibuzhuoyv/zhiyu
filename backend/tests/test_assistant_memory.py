from app.assistant_memory import standalone_references
from app.chat_routes import prompt
from app.conversation_memory import conversation_context_result, memory_terms
from app.database import ChatDatabase


def test_standalone_assistant_keeps_raw_role_origin_and_idempotency_without_fake_user(tmp_path):
    chat = ChatDatabase(tmp_path)
    conversation = chat.create_conversation("owner", "chat", "test", False)
    identifier = conversation["id"]
    first = chat.save_assistant("owner", identifier, "晚上陪你去灯塔火锅", message_id="a" * 32)
    assert chat.save_assistant("owner", identifier, "重试另一答案", message_id="a" * 32) == first
    assert chat.list_messages("owner", identifier) == [first]
    assert chat.get_message("other", identifier, first["id"]) is None
    assert first["role"] == "assistant" and first["origin"] == "proactive"
    with chat.connection() as db:
        assert db.execute("SELECT count(*) FROM memory_fragments").fetchone()[0] == 1
        assert db.execute("SELECT count(*) FROM memory_annotations").fetchone()[0] == 0


def test_old_proactive_original_is_recalled_independently_and_not_a_user_fact(tmp_path):
    chat = ChatDatabase(tmp_path)
    identifier = chat.create_conversation("owner", "chat", "test", False)["id"]
    original = "明天我陪你去灯塔火锅，不用你一个人安排。"
    saved = chat.save_assistant("owner", identifier, original)
    for index in range(25):
        chat.save_turn("owner", identifier, f"普通日常分享{index}", "认真陪你聊日常", [])
    recent = chat.recent_messages("owner", identifier)
    context = conversation_context_result(chat, "owner", identifier, "你之前说要陪我去灯塔火锅吗？", recent_messages=recent)
    found = [item for item in context.references if item["message_id"] == saved["id"]]
    assert len(found) == 1
    assert found[0]["text"] == original and found[0]["role"] == "assistant"
    assert found[0]["origin"] == "proactive" and found[0]["created_at"] == saved["created_at"]
    assert saved["id"] not in str(standalone_references(chat, "other", identifier, 1000, memory_terms("灯塔火锅")))


def test_cold_proactive_original_can_recover_without_rebuilding_active_index(tmp_path):
    chat = ChatDatabase(tmp_path)
    identifier = chat.create_conversation("owner", "chat", "test", False)["id"]
    saved = chat.save_assistant("owner", identifier, "我答应陪你去灯塔火锅。")
    with chat.connection() as db:
        db.execute("BEGIN IMMEDIATE")
        sequence = db.execute("SELECT sequence FROM messages WHERE id=?", (saved["id"],)).fetchone()[0]
        chat.cold.cool(db, sequence, identifier, saved["content"], chat.memory)
    found = standalone_references(chat, "owner", identifier, sequence + 1, memory_terms("灯塔火锅"))
    assert found["references"][0]["message_id"] == saved["id"]
    assert found["references"][0]["text"] == saved["content"]
    with chat.connection() as db:
        assert db.execute("SELECT count(*) FROM memory_fragments").fetchone()[0] == 0


def test_unpaired_context_order_has_no_role_relabel_or_dropped_ai_message(tmp_path):
    chat = ChatDatabase(tmp_path)
    identifier = chat.create_conversation("owner", "chat", "test", False)["id"]
    chat.save_turn("owner", identifier, "第一句用户原话", "第一句 AI 原话", [])
    chat.save_assistant("owner", identifier, "主动分享一")
    chat.save_assistant("owner", identifier, "主动分享二")
    chat.save_turn("owner", identifier, "收到啦", "好，等你", [])
    history = chat.recent_messages("owner", identifier)
    provided = prompt(history, "接着聊", [])[1:-1]
    assert [item["content"] for item in provided] == ["第一句用户原话", "第一句 AI 原话", "主动分享一", "主动分享二", "收到啦", "好，等你"]
    assert [item["role"] for item in provided] == ["user", "assistant", "assistant", "assistant", "user", "assistant"]


def test_proactive_cold_search_budget_does_not_claim_complete(monkeypatch, tmp_path):
    chat = ChatDatabase(tmp_path)
    identifier = chat.create_conversation("owner", "chat", "test", False)["id"]
    for index in range(3):
        message = chat.save_assistant("owner", identifier, f"灯塔火锅{index}")
        with chat.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            sequence = db.execute("SELECT sequence FROM messages WHERE id=?", (message["id"],)).fetchone()[0]
            chat.cold.cool(db, sequence, identifier, message["content"], chat.memory)
    monkeypatch.setattr("app.assistant_memory.MAX_COLD_ROWS", 2)
    result = standalone_references(chat, "owner", identifier, 10, memory_terms("灯塔火锅"))
    assert result["complete"] is False
    assert all(item["role"] == "assistant" for item in result["references"])
