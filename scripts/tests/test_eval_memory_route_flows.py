"""Evidence and counterfactual accounting, independent of language accuracy."""

import pytest

from scripts import eval_memory_route_flows as evaluation
from scripts.eval_memory_route_flows import reference_evidence, summarize_rows
from scripts.memory_route_linear import LinearDecision


def source():
    return {
        "id": "u1",
        "role": "user",
        "created_at": "2026-10-01T01:00:00+00:00",
        "content": "这是一句完整原话。",
    }


def reference():
    return {
        "message_id": "u1",
        "role": "user",
        "created_at": source()["created_at"],
        "text": "完整原话",
        "char_start": 4,
        "char_end": 8,
    }


def test_exact_source_excerpt_and_metadata_are_required():
    item = reference()
    assert reference_evidence([item], {"u1": source()}) == ["u1"]
    assert source()["content"] == "这是一句完整原话。"


@pytest.mark.parametrize(
    "changes",
    [
        {"message_id": "foreign"},
        {"role": "assistant"},
        {"created_at": "wrong"},
        {"text": "改写原话"},
        {"char_start": -1},
        {"char_end": 100},
        {"char_start": True},
    ],
)
def test_foreign_rewritten_or_malformed_evidence_cannot_be_scored_as_valid(changes):
    with pytest.raises(ValueError):
        reference_evidence([{**reference(), **changes}], {"u1": source()})


def row(gold, route, needed=(), baseline=()):
    return {
        "gold": gold,
        "decision": {"route": route, "reason": "synthetic"},
        "needed_ids": list(needed),
        "provided_ids": list(baseline),
        "classifier_ms": 25.0,
        "baseline_ms": 2.0,
        "recent_only_ms": 1.0,
        "reference_chars": 100 if baseline else 0,
    }


def test_existing_retrieval_gaps_do_not_hide_skipping_damage():
    report = summarize_rows(
        [
            row("retrieve", "present", ["one"], ["one"]),
            row("retrieve", "retrieve", ["two"], []),
            row("retrieve", "present", ["three"], []),
            row("present", "present", [], ["optional"]),
        ]
    )
    assert report["important_false_skip"] == 2
    assert report["baseline_fact_gap_turns"] == 2
    assert report["skip_added_fact_gap_turns"] == 1
    assert report["daily_skipped"] == 1
    assert report["daily_reference_chars"] == 100
    assert report["zero_important_false_skip"] is False
    assert report["classifier_cost_lower_than_lookup"] is False


def test_safe_but_all_retrieve_is_not_skip_coverage():
    report = summarize_rows([row("present", "retrieve"), row("retrieve", "retrieve")])
    assert report["zero_important_false_skip"] is True
    assert report["daily_skip_rate"] == 0
    assert report["fresh_independent_user_samples"] is False


def test_empty_observation_does_not_report_success():
    with pytest.raises(ValueError):
        summarize_rows([])


def flow():
    return {
        "id": "unit-flow",
        "old_messages": [
            {
                "id": "old-user",
                "role": "user",
                "created_at": source()["created_at"],
                "text": "我给修表店取名叫蓝鸟工坊。",
            }
        ],
        "bridge_turns": [{"user": "今晚在窗边歇了一会儿。", "assistant": "我陪你。"}]
        * 22,
        "turns": [
            {
                "id": "unit-current",
                "text": "修表店叫什么名字？",
                "assistant": "叫蓝鸟工坊。",
                "gold": "retrieve",
                "needed_source_ids": ["old-user"],
                "category": "old_place",
            },
            {
                "id": "unit-daily",
                "text": "现在望着窗外的灯很放松。",
                "assistant": "和你一起歇歇。",
                "gold": "present",
                "needed_source_ids": [],
                "category": "daily",
            },
        ],
    }


class SyntheticRouter:
    def __init__(self):
        self.contexts = []

    def decide(self, text, recent):
        self.contexts.append(recent)
        return LinearDecision("present", "test_counterfactual")


def test_actual_continuous_storage_recall_and_counterfactual_are_separate(tmp_path):
    router = SyntheticRouter()
    rows, signatures = evaluation.observe_bank(tmp_path, [flow()], router)
    summary = summarize_rows(rows)
    assert summary["important_false_skip"] == summary["skip_added_fact_gap_turns"] == 1
    assert summary["baseline_fact_gap_turns"] == 0
    assert signatures[0]["messages"] == 50
    assert signatures[0]["originals_identical_each_turn"] is True
    assert all(len(context) == 4 for context in router.contexts)
    assert router.contexts[-1][-1]["content"] == "叫蓝鸟工坊。"
    assert rows[0]["references"][0]["text"] == flow()["old_messages"][0]["text"]
    assert rows[0]["references"][0]["created_at"] == source()["created_at"]


def test_unpaired_or_wrong_authorship_is_not_silently_fused(tmp_path):
    from app.database import ChatDatabase

    bad = {
        **flow(),
        "old_messages": [{**flow()["old_messages"][0], "role": "assistant"}],
    }
    chat = ChatDatabase(tmp_path)
    cid = chat.create_conversation("owner", "unit", "unit", False)["id"]
    with pytest.raises(ValueError):
        evaluation.seed_flow(chat, "owner", cid, bad)
    assert chat.list_messages("owner", cid) == []


def test_changed_recent_snapshot_aborts_observation(tmp_path, monkeypatch):
    from app.database import ChatDatabase

    chat = ChatDatabase(tmp_path)
    cid = chat.create_conversation("owner", "unit", "unit", False)["id"]
    originals, mapping = evaluation.seed_flow(chat, "owner", cid, flow())
    monkeypatch.setattr(evaluation, "timed_recent", lambda *args: ([], 1.0))
    with pytest.raises(RuntimeError, match="近期读取"):
        evaluation.observe_turn(
            chat,
            "owner",
            cid,
            flow()["turns"][0],
            SyntheticRouter(),
            {m["id"]: m for m in originals},
            mapping,
            0,
        )


def test_consecutive_user_sources_keep_their_original_authors(tmp_path):
    from app.database import ChatDatabase
    from scripts.memory_route_flows import flows

    selected = flows()[0]
    chat = ChatDatabase(tmp_path)
    cid = chat.create_conversation("owner", "unit", "unit", False)["id"]
    saved, mapping = evaluation.seed_flow(chat, "owner", cid, selected)
    assert len(saved) == 52
    actual = {m["id"]: m for m in saved}
    assert all(
        actual[mapping[m["id"]]]["role"] == m["role"] for m in selected["old_messages"]
    )
    assert all(
        actual[mapping[m["id"]]]["content"] == m["text"]
        for m in selected["old_messages"]
    )
    assert all(
        actual[mapping[m["id"]]]["created_at"] == m["created_at"]
        for m in selected["old_messages"]
    )


def test_cli_failure_only_reports_error_type(monkeypatch, capsys):
    monkeypatch.setattr(
        "sys.argv", ["eval", "--model", "model", "--profile", "profile"]
    )

    def fail(*args):
        raise ValueError("PRIVATE SYNTHETIC INPUT")

    monkeypatch.setattr(evaluation, "run", fail)
    with pytest.raises(SystemExit) as error:
        evaluation.main()
    assert error.value.code == 1
    assert "PRIVATE SYNTHETIC INPUT" not in capsys.readouterr().out
