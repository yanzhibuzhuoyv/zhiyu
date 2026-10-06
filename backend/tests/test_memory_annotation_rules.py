import pytest

from app.memory_annotation_rules import classify_source


@pytest.mark.parametrize("text,kind,polarity", [
    ("我想吃苹果", "wish", "affirm"),
    ("我又想吃苹果。", "wish", "affirm"),
    ("我一直喜欢苹果", "preference", "affirm"),
    ("我不喜欢苹果", "preference", "deny"),
    ("我的公历生日是03-06", "profile", "affirm"),
    ("我的生日是3月6日", "profile", "affirm"),
    ("我承诺在2026-10-08陪你吃火锅", "promise", "affirm"),
])
def test_exact_source_sentences(text, kind, polarity):
    result = classify_source(text, "user")
    assert result["kind"] == kind and result["polarity"] == polarity
    assert result["subject"] == "user"
    assert result["span_start"] == 0 and result["span_end"] == len(text)


@pytest.mark.parametrize("text", [
    "朋友的生日是3月6日", "假如我的生日是3月6日", "我的生日不是3月6日",
    "他说：我的生日是3月6日", "“我的生日是3月6日”", "你还记得我的生日吗",
    "我的生日是2026-02-30", "我的生日是2月30日", "我的农历生日是3月6日",
    "我的生日是3月6日，但那是朋友的生日", "我喜欢苹果，但是我其实不喜欢",
    "如果我想吃苹果", "我想吃苹果但现在不想了", "我想吃不甜的苹果",
    "我喜欢苹果也讨厌苹果", "我喜欢苹果这是个谎言", "我喜欢苹果那是以前",
    "我想读山海经然后去跑步", "我喜欢applesbutnotnow",
    "我承诺明天陪你吃火锅", "我承诺在2026-10-08陪你吃火锅，除非下雨",
])
def test_unsupported_sources_are_not_guessed(text):
    assert classify_source(text, "user") is None


def test_keep_and_correction_are_explicit_and_source_stays_independent():
    kept = classify_source("请记住：我一直喜欢苹果", "user")
    correction = classify_source("更正：我的公历生日是03-07", "user")
    assert kept["explicit_keep"] is True
    assert correction["correction"] is True
    assert correction["date_basis"] == "gregorian"
    assert classify_source("我的生日是3月6日", "user")["date_basis"] is None


def test_ai_can_make_own_promise_but_never_supply_user_profile_or_preference():
    assert classify_source("我的生日是3月6日", "assistant") is None
    assert classify_source("我一直喜欢苹果", "assistant") is None
    promise = classify_source("我承诺在2026-10-08陪你吃火锅", "assistant")
    assert promise["subject"] == "assistant" and promise["promise_state"] == "pending"


@pytest.mark.parametrize("text,role", [(None, "user"), ("我想吃苹果", "system"), (b"a", "user")])
def test_rule_inputs_validate_types_and_actual_author(text, role):
    with pytest.raises(ValueError):
        classify_source(text, role)


@pytest.mark.parametrize("action,item", [
    ("喝", "红茶"), ("看", "星空"), ("读", "山海经"), ("听", "古典音乐"),
    ("去", "海边"), ("买", "画笔"), ("玩", "围棋"), ("学", "法语"), ("做", "陶艺"),
])
def test_literal_topics_work_beyond_food_examples(action, item):
    wish = classify_source(f"我想{action}{item}", "user")
    promise = classify_source(f"我承诺在2026-10-08陪你{action}{item}", "assistant")
    assert wish["kind"] == "wish" and wish["topic_key"] == f"{action}:{item}"
    assert promise["kind"] == "promise" and promise["topic_key"] == f"promise:{action}:{item}"


@pytest.mark.parametrize("text", ["我承诺在2026-02-30陪你读山海经", "我承诺在2026-10-08陪你吃苹果但是会下雨"])
def test_bad_promise_is_not_accepted(text):
    assert classify_source(text, "user") is None


@pytest.mark.parametrize("text", ["我想吃苹果\n", " 我想吃苹果 "])
def test_whitespace_is_not_removed_from_source_pointer(text):
    result = classify_source(text, "user")
    assert result["span_start"] == 0 and result["span_end"] == len(text)
