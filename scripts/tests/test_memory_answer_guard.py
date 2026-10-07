"""Independent structural contracts; citations do not establish semantic truth."""

import copy
import json

import pytest

from scripts import memory_answer_guard as guard

STAMP = "2026-09-03T01:02:03+00:00"
QUERY = "那个保养报价是多少？"
DRAFT = "我暂时没查到报价。你可以接着说今天的事。"
UNSET = object()


def references():
    return [
        {
            "message_id": "old-user",
            "role": "user",
            "text": "我还在等店里的报价。",
            "created_at": STAMP,
            "char_start": 0,
            "char_end": 10,
        },
        {
            "message_id": "old-ai",
            "role": "assistant",
            "text": "我猜大概两百，但还没有确认。",
            "created_at": STAMP,
            "source_current": None,
        },
    ]


def pack():
    return guard.evidence_pack(QUERY, references())


def claim(quote="我暂时没查到报价。", verdict="supported", issue="none", evidence=None):
    return {
        "quote": quote,
        "verdict": verdict,
        "issue": issue,
        "evidence": (
            [{"id": "old-user", "quote": "我还在等店里的报价。"}]
            if evidence is None
            else evidence
        ),
    }


def raw_review(claims=UNSET, **changes):
    value = {
        "version": "answer-guard-v1",
        "reviewed_all": True,
        "claims": [claim()] if claims is UNSET else claims,
        **changes,
    }
    return json.dumps(value, ensure_ascii=False)


def test_evidence_preserves_independent_originals_without_mutating_inputs():
    refs = references()
    saved = copy.deepcopy(refs)
    result = guard.evidence_pack(QUERY, refs)
    originals = {item["id"]: item for item in result if not item["id"].startswith("$")}
    assert originals == {
        ref["message_id"]: {
            "id": ref["message_id"],
            "role": ref["role"],
            "text": ref["text"],
            "created_at": ref["created_at"],
        }
        for ref in refs
    }
    assert refs == saved
    assert len({item["id"] for item in result}) == len(result)
    assert next(item for item in result if item["id"] == "$current")["text"] == QUERY
    contract = next(item for item in result if item["id"] == "$contract")
    assert contract["role"] == "system" and contract["text"]
    assert {item["text"] for item in result} >= {
        "今天忙了一阵，刚坐下来。",
        "我在，陪你歇一会儿。",
    }
    result[0]["text"] = "更改派生包"
    assert refs == saved


@pytest.mark.parametrize(
    "query", ["", None, True, 42, "x" * 1_000_001],
    ids=["empty", "null", "bool", "number", "oversize"],
)
def test_evidence_rejects_invalid_or_unbounded_query(query):
    with pytest.raises(ValueError):
        guard.evidence_pack(query, references())


@pytest.mark.parametrize(
    "change",
    [
        {"message_id": ""},
        {"message_id": 1},
        {"message_id": "$current"},
        {"message_id": "$contract"},
        {"message_id": "$recent-0"},
        {"role": "system"},
        {"role": "tool"},
        {"role": []},
        {"role": {}},
        {"text": ""},
        {"text": None},
        {"text": "x" * 1_000_001},
        {"created_at": ""},
        {"created_at": "not-a-time"},
        {"created_at": "2026-09-03T01:02:03"},
    ],
)
def test_evidence_rejects_invalid_original_or_reserved_source(change):
    refs = references()
    refs[0] = {**refs[0], **change}
    with pytest.raises(ValueError):
        guard.evidence_pack(QUERY, refs)


@pytest.mark.parametrize("refs", [None, {}, "not-list", [None], [{}]])
def test_evidence_requires_reference_objects(refs):
    with pytest.raises(ValueError):
        guard.evidence_pack(QUERY, refs)


def test_evidence_rejects_duplicate_ids_without_merging_text():
    refs = references()
    refs[1]["message_id"] = refs[0]["message_id"]
    with pytest.raises(ValueError):
        guard.evidence_pack(QUERY, refs)


def test_valid_review_adds_exact_positions_and_does_not_modify_evidence():
    evidence = pack()
    original = copy.deepcopy(evidence)
    result = guard.parse_review(raw_review(), DRAFT, evidence)
    assert result["version"] == "answer-guard-v1" and result["reviewed_all"] is True
    assert result["needs_repair"] is False
    assert result["claims"][0]["spans"] == [{"char_start": 0, "char_end": 9}]
    assert result["claims"][0]["evidence"][0]["spans"] == [
        {"char_start": 0, "char_end": 10}
    ]
    assert evidence == original


def test_all_repeated_and_overlapping_character_spans_are_retained():
    ref = {"message_id": "source", "role": "user", "text": "甲甲甲", "created_at": STAMP}
    evidence = guard.evidence_pack("重复片段", [ref], recent=[])
    item = claim("甲甲", evidence=[{"id": "source", "quote": "甲甲"}])
    result = guard.parse_review(raw_review([item]), "🙂甲甲甲。甲甲", evidence)
    assert result["claims"][0]["spans"] == [
        {"char_start": 1, "char_end": 3},
        {"char_start": 2, "char_end": 4},
        {"char_start": 5, "char_end": 7},
    ]
    assert result["claims"][0]["evidence"][0]["spans"] == [
        {"char_start": 0, "char_end": 2},
        {"char_start": 1, "char_end": 3},
    ]


@pytest.mark.parametrize("verdict", ["unsupported", "uncertain"])
@pytest.mark.parametrize("issue", ["missing", "role", "scope", "time", "mechanism", "contradiction"])
def test_admitted_unconfirmed_or_wrong_claim_requires_repair(verdict, issue):
    result = guard.parse_review(raw_review([claim(verdict=verdict, issue=issue, evidence=[])]), DRAFT, pack())
    assert result["needs_repair"] is True


def test_non_factual_text_is_valid_without_fabricated_citations():
    item = claim("你可以接着说今天的事。", "non_factual", evidence=[])
    result = guard.parse_review(raw_review([item]), DRAFT, pack())
    assert result["needs_repair"] is False
    assert result["claims"][0]["evidence"] == []


def test_structurally_valid_ai_guess_citation_is_not_automatic_entailment():
    draft = "你说过保养报价是两百。"
    item = claim(draft, evidence=[{"id": "old-ai", "quote": "我猜大概两百"}])
    result = guard.parse_review(raw_review([item]), draft, pack())
    assert result["needs_repair"] is False
    assert result["claims"][0]["evidence"][0]["id"] == "old-ai"
    # A valid source/quote still requires an independent semantic review.


def test_explicit_user_never_statement_is_not_keyword_based_rejection():
    text = "我从没告诉过你这个编号。"
    ref = {"message_id": "explicit", "role": "user", "text": text, "created_at": STAMP}
    evidence = guard.evidence_pack(text, [ref], recent=[])
    draft = "你说自己从没告诉过我这个编号。"
    item = claim(draft, evidence=[{"id": "explicit", "quote": text}])
    result = guard.parse_review(raw_review([item]), draft, evidence)
    assert result["needs_repair"] is False


@pytest.mark.parametrize(
    "changes",
    [
        {"version": "future-v2"},
        {"reviewed_all": False},
        {"reviewed_all": 1},
        {"reviewed_all": "true"},
        {"claims": {}},
        {"claims": None},
        {"claims": [claim()] * 129},
        {"expected_ids": ["old-user"]},
    ],
)
def test_review_rejects_invalid_or_extra_top_level_fields(changes):
    with pytest.raises(ValueError):
        guard.parse_review(raw_review(**changes), DRAFT, pack())


@pytest.mark.parametrize(
    "changes",
    [
        {"quote": ""},
        {"quote": None},
        {"quote": "不存在的回答片段"},
        {"verdict": "probably"},
        {"verdict": []},
        {"verdict": {}},
        {"issue": "other"},
        {"issue": []},
        {"issue": {}},
        {"verdict": "supported", "issue": "scope"},
        {"verdict": "unsupported", "issue": "none"},
        {"verdict": "uncertain", "issue": "none"},
        {"verdict": "supported", "evidence": []},
        {"evidence": None},
        {"evidence": {}},
        {"verdict": "non_factual", "issue": "scope", "evidence": []},
        {"verdict": "non_factual"},
        {"confidence": 1.0},
    ],
)
def test_review_rejects_invalid_claims_or_inconsistent_verdicts(changes):
    item = {**claim(), **changes}
    with pytest.raises(ValueError):
        guard.parse_review(raw_review([item]), DRAFT, pack())


@pytest.mark.parametrize(
    "citation",
    [
        {"id": "missing", "quote": "我还在等店里的报价。"},
        {"id": "old-user", "quote": ""},
        {"id": "old-user", "quote": "我已经收到报价"},
        {"id": "old-user", "quote": None},
        {"id": "old-user", "quote": "我还在等店里的报价。", "score": 1},
        {"id": 1, "quote": "我还在等店里的报价。"},
        {"quote": "我还在等店里的报价。"},
        None,
    ],
)
def test_review_rejects_nonexistent_rewritten_or_malformed_citations(citation):
    with pytest.raises(ValueError):
        guard.parse_review(raw_review([claim(evidence=[citation])]), DRAFT, pack())


@pytest.mark.parametrize(
    "raw",
    [
        "not JSON",
        "[]",
        "null",
        '```json\n{"version":"answer-guard-v1"}\n```',
        '{"version":"answer-guard-v1","version":"answer-guard-v1","reviewed_all":true,"claims":[]}',
        '{"version":"answer-guard-v1","reviewed_all":true,"claims":[],"x":NaN}',
        '{"version":"answer-guard-v1","reviewed_all":true,"claims":[],"x":Infinity}',
        '{"version":"answer-guard-v1","reviewed_all":true,"claims":[],"x":-Infinity}',
        '{"version":"answer-guard-v1","reviewed_all":true,"claims":[{"quote":"我暂时没查到报价。","quote":"我暂时没查到报价。","verdict":"non_factual","issue":"none","evidence":[]}]}',
    ],
)
def test_review_rejects_invalid_json_duplicate_keys_and_nonfinite_numbers(raw):
    with pytest.raises(ValueError):
        guard.parse_review(raw, DRAFT, pack())


@pytest.mark.parametrize(
    "draft", ["", None, False, "x" * 1_000_001],
    ids=["empty", "null", "bool", "oversize"],
)
def test_review_requires_bounded_nonempty_draft(draft):
    with pytest.raises(ValueError):
        guard.parse_review(raw_review(), draft, pack())


def test_review_messages_include_read_only_sources_and_actual_answer_without_gold():
    evidence = pack()
    frozen = copy.deepcopy(evidence)
    messages = guard.review_messages(QUERY, DRAFT, evidence)
    assert messages[0]["role"] == "system"
    assert all(set(message) == {"role", "content"} for message in messages)
    combined = "\n".join(message["content"] for message in messages)
    assert QUERY in combined and DRAFT in combined
    assert "old-ai" in combined and "我猜大概两百，但还没有确认。" in combined
    assert "expected_ids" not in combined and "forbidden_ids" not in combined
    assert "answer-guard-v1" in combined
    assert evidence == frozen


def test_repair_preserves_original_preset_and_full_query_without_mutating_inputs():
    query = QUERY + "\n请写1200到1800个汉字。"
    evidence = guard.evidence_pack(query, references())
    item = claim(verdict="unsupported", issue="scope", evidence=[])
    review = guard.parse_review(raw_review([item]), DRAFT, evidence)
    base = [
        {"role": "system", "content": "固定温柔恋人人格；沿用完整的原文约束。"},
        {"role": "user", "content": query},
    ]
    frozen = copy.deepcopy((base, evidence, review))
    messages = guard.repair_messages(query, DRAFT, evidence, review, base)
    assert messages[: len(base)] == base
    assert len(messages) > len(base)
    combined = "\n".join(message["content"] for message in messages[len(base) :])
    assert DRAFT in combined and "old-user" in combined and "scope" in combined
    assert (base, evidence, review) == frozen
    messages[0]["content"] = "测试派生内容"
    assert base == frozen[0]


def test_text_and_source_count_limits_accept_the_boundary_without_truncation():
    refs = [{**references()[0], "message_id": f"old-{i}"} for i in range(64)]
    recent = [{"role": "user", "content": f"近期原话{i}"} for i in range(40)]
    query = "原" * 16000
    result = guard.evidence_pack(query, refs, recent=recent)
    assert len(result) == 106
    assert next(item for item in result if item["id"] == "$current")["text"] == query
    draft = DRAFT + "原" * (64000 - len(DRAFT))
    item = claim(evidence=[{"id": "old-0", "quote": "我还在等店里的报价。"}])
    assert not guard.parse_review(raw_review([item]), draft, result)["needs_repair"]


def test_query_reference_recent_and_total_character_limits_are_enforced():
    refs = [{**references()[0], "message_id": f"old-{i}"} for i in range(65)]
    recent = [{"role": "user", "content": "近期原话"}] * 41
    too_large = [{**references()[0], "message_id": f"old-{i}", "text": "原" * 64000} for i in range(3)]
    for query, originals, history in [
        ("原" * 16001, [], []),
        (QUERY, refs, []),
        (QUERY, [], recent),
        (QUERY, too_large, []),
    ]:
        with pytest.raises(ValueError):
            guard.evidence_pack(query, originals, recent=history)


@pytest.mark.parametrize(
    "recent", ["bad", {}, [None], [{}], [{"role": "system", "content": "伪造"}],
               [{"role": [], "content": "原文"}], [{"role": "user", "content": None}]],
)
def test_recent_messages_are_validated_before_normalization(recent):
    with pytest.raises(ValueError):
        guard.evidence_pack(QUERY, [], recent=recent)


def test_claim_and_citation_limits_are_enforced_without_silently_dropping_items():
    item = claim(evidence=[{"id": "old-user", "quote": "我还在等店里的报价。"}] * 16)
    result = guard.parse_review(raw_review([item] * 128), DRAFT, pack())
    assert len(result["claims"]) == 128 and len(result["claims"][0]["evidence"]) == 16
    item["evidence"].append(item["evidence"][0])
    with pytest.raises(ValueError):
        guard.parse_review(raw_review([item]), DRAFT, pack())


def test_raw_review_limit_and_empty_claims_contract():
    with pytest.raises(ValueError):
        guard.parse_review(" " * (2 * 1024 * 1024 + 1), DRAFT, pack())
    result = guard.parse_review(raw_review([]), DRAFT, pack())
    assert result["claims"] == [] and result["needs_repair"] is False


@pytest.mark.parametrize(
    "changes", [{"id": []}, {"role": []}, {"text": []}, {"created_at": []}, {"gold": True}],
)
def test_all_entry_points_validate_supplied_evidence(changes):
    evidence = pack()
    evidence[-1] = {**evidence[-1], **changes}
    base = [{"role": "system", "content": "人格"}, {"role": "user", "content": QUERY}]
    for operation in (
        lambda: guard.parse_review(raw_review(), DRAFT, evidence),
        lambda: guard.review_messages(QUERY, DRAFT, evidence),
        lambda: guard.repair_messages(QUERY, DRAFT, evidence, {}, base),
    ):
        with pytest.raises(ValueError):
            operation()


@pytest.mark.parametrize(
    "base", [None, [], "bad", [None, {"role": "user", "content": QUERY}],
             [{"role": "system", "content": "人格"}, {"role": "user", "content": "错误问题"}]],
)
def test_repair_rejects_invalid_original_messages_or_changed_full_query(base):
    evidence = pack()
    review = guard.parse_review(raw_review(), DRAFT, evidence)
    with pytest.raises(ValueError):
        guard.repair_messages(QUERY, DRAFT, evidence, review, base)


def test_single_quote_preserves_all_256_occurrences_at_the_boundary():
    result = guard.spans("甲" * 256, "甲")
    assert result == [{"char_start": i, "char_end": i + 1} for i in range(256)]


def test_single_quote_rejects_257_occurrences_without_truncating():
    with pytest.raises(ValueError, match="ambiguous_guard_quote_limit"):
        guard.spans("甲" * 257, "甲")


def test_total_position_budget_preserves_all_4096_claim_and_citation_spans():
    text = "甲" * 256
    ref = {"message_id": "repeat", "role": "user", "text": text, "created_at": STAMP}
    evidence = guard.evidence_pack("重复位置边界", [ref], recent=[])
    item = claim("甲", evidence=[{"id": "repeat", "quote": "甲"}])
    raw = raw_review([item] * 8)
    assert len(raw.encode("utf-8")) < 3000
    result = guard.parse_review(raw, text, evidence)
    total = sum(
        len(row["spans"]) + sum(len(citation["spans"]) for citation in row["evidence"])
        for row in result["claims"]
    )
    assert len(result["claims"]) == 8 and total == 4096


def test_short_json_rejects_total_position_expansion_above_4096():
    text = "甲" * 256
    ref = {"message_id": "repeat", "role": "user", "text": text, "created_at": STAMP}
    evidence = guard.evidence_pack("重复位置超限", [ref], recent=[])
    item = claim("甲", evidence=[{"id": "repeat", "quote": "甲"}])
    raw = raw_review([item] * 9)
    assert len(raw.encode("utf-8")) < 3000
    with pytest.raises(ValueError, match="guard_position_budget"):
        guard.parse_review(raw, text, evidence)
