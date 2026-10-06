"""Endurance harness counterexamples; actual loopback runs are explicit."""

import importlib.util
import json
import os
import sys
from pathlib import Path
from types import ModuleType, SimpleNamespace

import httpx
import pytest

SCRIPT = Path(__file__).parents[1] / "stress_long_fields.py"
SPEC = importlib.util.spec_from_file_location("stress_long_fields", SCRIPT)
stress = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(stress)


def test_exact_total_includes_twelve_probes_and_preserves_boundary_turns():
    schedule = stress.probe_schedule(10000)
    assert len(schedule) == 12
    assert set(schedule) == {*range(995, 1001), *range(9995, 10001)}
    assert stress.checkpoint_turns(10000) == [1000, 10000]
    assert stress.checkpoint_turns(1000) == [1000]
    assert stress.field_lengths(1) == (16000, 16000)
    assert stress.field_lengths(250) == (16000, 64000)
    assert 250 not in schedule
    assert stress.field_lengths(20) != stress.field_lengths(21)
    assert sum(turn not in schedule for turn in range(1, 10001)) == 9988


def test_ordinary_payload_length_ranges_and_determinism():
    for turn in (2, 22, 217, 567, 9234):
        user, assistant = stress.field_lengths(turn)
        assert 2000 <= user <= 4000 and 4000 <= assistant <= 8000
        assert len(stress.helpers.long_payload(user, turn, "user")) == user
        assert len(stress.helpers.long_payload(assistant, turn, "assistant")) == assistant
    assert stress.field_lengths(9234) == stress.field_lengths(9234)


def test_probe_requires_each_original_role_middle_tail_and_does_not_accept_forged_marker():
    message = {"id": "assistant-250", "role": "assistant", "created_at": "2026-10-05", "sources": [],
               "content": stress.helpers.long_payload(64000, 250, "assistant")}
    marker = stress.helpers.anchor_marker("assistant", "tail", 250)
    ref = {"message_id": message["id"], "turn_id": "user-250", "role": "assistant",
           "created_at": message["created_at"], "text": message["content"][-768:], "truncated": True}
    assert stress.helpers.verify_reference([ref], message, "user-250", "tail", 250)["passed"]
    for changed in ({"role": "user"}, {"message_id": "wrong"}, {"turn_id": "wrong"},
                    {"text": marker + "invented surroundings"}, {"created_at": "2025"}):
        with pytest.raises(stress.helpers.CheckFailure):
            stress.helpers.verify_reference([{**ref, **changed}], message, "user-250", "tail", 250)
    with pytest.raises(stress.helpers.CheckFailure):
        stress.helpers.verify_reference([], message, "user-250", "tail", 250)


def test_short_probe_reply_never_echoes_a_recalled_original_fact():
    import asyncio

    provider = stress.EnduranceProvider()
    marker = stress.helpers.anchor_marker("assistant", "tail", 250)
    reply = asyncio.run(provider.complete("demo", "unused-test-key", "demo", [{"role": "user", "content": marker}]))
    assert marker not in reply and len(reply) < 100
    reply = asyncio.run(provider.complete("demo", "unused-test-key", "demo", [{"role": "user", "content": stress.helpers.long_payload(16000, 250, "user")}]))
    assert len(reply) == 64000


def test_partial_checkpoint_or_missing_probe_cannot_mark_a_run_passed():
    report = {"requested_turns": 10000, "completed_turns": 1000,
              "checkpoints": [{"completed_turns": 1000, "status": "passed"}]}
    with pytest.raises(stress.helpers.CheckFailure):
        stress.verify_complete(report)
    report["completed_turns"] = 10000
    with pytest.raises(stress.helpers.CheckFailure):
        stress.verify_complete(report)


def test_complete_requires_both_real_stage_probe_counts_and_accepts_a_fully_checked_report():
    report = {"requested_turns": 10000, "completed_turns": 10000,
              "checkpoints": [{"completed_turns": turn, "status": "passed", "probe_count": 6}
                              for turn in (1000, 10000)]}
    stress.verify_complete(report)
    report["checkpoints"][-1]["probe_count"] = 5
    with pytest.raises(stress.helpers.CheckFailure):
        stress.verify_complete(report)


def test_unsupported_overlapping_stage_totals_are_rejected_before_any_server_or_file(tmp_path):
    report = tmp_path / "report.json"
    with pytest.raises(ValueError, match="1000,10000"):
        stress.run(report, tmp_path / "data", turns=1001)
    assert not report.exists() and not (tmp_path / "data").exists()


def test_history_hash_rejects_a_changed_long_role_without_storing_an_all_history_copy():
    user = {"id": "u", "role": "user", "content": "long original", "sources": [], "created_at": "2026"}
    assistant = {**user, "id": "a", "role": "assistant"}
    signatures = [stress.helpers.signature(item) for item in (user, assistant)]
    with pytest.raises(stress.helpers.CheckFailure):
        stress.helpers.verify_page([user, {**assistant, "content": "lost"}], signatures, 2, set())


def test_sensitive_transport_failure_is_not_retried_and_is_not_saved_in_report(tmp_path):
    requests = []

    def failing(request):
        requests.append(request.url.path)
        raise httpx.ReadTimeout("PRIVATE-TOKEN-AND-TEXT", request=request)

    report = stress.initial_report(1000)
    experiment = stress.EnduranceRun(tmp_path / "data", None, report, tmp_path / "report.json", 100, 0, lambda _: None)

    class Server:
        def check(self):
            pass

    experiment.server = Server()
    with httpx.Client(base_url="http://test", transport=httpx.MockTransport(failing)) as client:
        experiment.client = client
        with pytest.raises(stress.helpers.CheckFailure) as error:
            experiment.call("POST", "/api/conversations/disposable/messages", json={"content": "synthetic"})
    assert requests == ["/api/conversations/disposable/messages"]
    assert report["unexpected_http_failures"] == 1
    assert "PRIVATE" not in str(error.value) and "PRIVATE" not in json.dumps(report)


def test_cleanup_errors_mark_run_failed_keep_primary_failure_and_continue_all_steps(tmp_path, monkeypatch):
    report = stress.initial_report(1000)
    report.update({"status": "failed", "failure": {"message": "original assertion"}})
    experiment = stress.EnduranceRun(tmp_path / "data", None, report, tmp_path / "report.json", 100, 0, lambda _: None)
    experiment.identifier, experiment.headers = "own-conversation", {"Authorization": "Bearer synthetic"}
    actions = []

    class Client:
        def close(self):
            actions.append("client close")

    class Server:
        def stop(self):
            actions.append("server stop")

    def failing(method, path, **kwargs):
        actions.append(method + " " + path)
        raise ValueError("PRIVATE-CLEANUP-ERROR")

    experiment.client, experiment.server = Client(), Server()
    monkeypatch.setattr(experiment, "call", failing)
    experiment.cleanup()
    assert "DELETE /api/conversations/own-conversation" in actions
    assert "DELETE /api/auth/session" in actions
    assert actions[-2:] == ["client close", "server stop"]
    assert report["cleanup"]["completed"] is False and report["status"] == "failed"
    assert report["failure"]["message"] == "original assertion"
    assert "PRIVATE" not in json.dumps(report)


def test_atomic_checkpoint_is_parseable_and_does_not_leave_temporary_file(tmp_path):
    path = tmp_path / "report.json"
    stress.write_report(path, {"status": "running", "completed_turns": 100})
    assert json.loads(path.read_text())["completed_turns"] == 100
    assert not path.with_suffix(".json.tmp").exists()


class MockLoop:
    """Small mock sequence for harness control flow, separate from actual HTTP proof."""

    def __init__(self, tmp_path, missing_role=None):
        self.messages, self.session, self.missing_role = [], 0, missing_role
        self.experiment = stress.EnduranceRun(tmp_path / "data", None, stress.initial_report(1000),
                                              tmp_path / "report.json", 1000, 0, lambda _: None)
        self.experiment.start = self.start

    def start(self):
        self.session += 1

        class Server:
            def check(self):
                pass

            def stop(self):
                pass

        self.experiment.server = Server()
        self.experiment.client = httpx.Client(base_url="http://mock", transport=httpx.MockTransport(self.handle))

    def handle(self, request):
        path = request.url.path
        if path == "/api/auth/login":
            key = json.loads(request.content)["api_key"]
            return httpx.Response(200, json={"token": "foreign" if key.startswith("long-isolation") else f"owner-{self.session}"})
        if path == "/api/auth/session":
            return httpx.Response(204)
        if path == "/api/auth/me":
            return httpx.Response(401)
        if path == "/api/conversations":
            return httpx.Response(201, json={"id": "own"})
        if request.method == "DELETE":
            self.messages = []
            return httpx.Response(204)
        if request.headers.get("authorization") == "Bearer foreign":
            return httpx.Response(404)
        if path.endswith("/history"):
            end = len(self.messages)
            if cursor := request.url.params.get("before"):
                end = next(index for index, item in enumerate(self.messages) if item["id"] == cursor)
            page = self.messages[max(0, end - 100):end]
            return httpx.Response(200, json={"messages": page, "has_more": end > 100,
                                            "next_before": page[0]["id"] if end > 100 else None})
        if path.endswith("/messages"):
            content = json.loads(request.content)["content"]
            if len(content) == 16001:
                return httpx.Response(422)
            return self.send(content)
        raise AssertionError("Unexpected mock route")

    def send(self, content):
        turn = len(self.messages) // 2 + 1
        probe = self.experiment.schedule.get(turn)
        if probe:
            reply = "合成探针回复。"
        else:
            reply = stress.helpers.long_payload(stress.field_lengths(turn)[1], turn, "assistant")
        references = []
        if probe and probe["role"] != self.missing_role:
            start = (probe["anchor_turn"] - 1) * 2
            for original in self.messages[start:start + 2]:
                text = original["content"]
                if original["role"] == probe["role"]:
                    marker = stress.helpers.anchor_marker(probe["role"], probe["location"], probe["anchor_turn"])
                    offset = max(0, text.index(marker) - 128)
                else:
                    offset = 0
                references.append({"message_id": original["id"], "turn_id": self.messages[start]["id"],
                                   "role": original["role"], "created_at": original["created_at"],
                                   "text": text[offset:offset + 768], "truncated": True})
        pair = [{"id": f"{role}-{turn}", "role": role, "content": text, "created_at": "2026-10-06",
                 "sources": []} for role, text in (("user", content), ("assistant", reply))]
        self.messages.extend(pair)
        return httpx.Response(200, json={"user_message": pair[0], "assistant_message": pair[1], "memory_refs": references})

    def snapshot(self, path):
        return {"messages": len(self.messages), "conversations": int(bool(self.experiment.identifier)),
                "fragments": len(self.messages), "fts_rows": len(self.messages),
                "content_utf8_bytes": sum(len(message["content"].encode()) for message in self.messages)}


def test_mock_complete_thousand_turns_includes_six_probes_restarts_and_keeps_only_two_full_anchors(tmp_path, monkeypatch):
    loop = MockLoop(tmp_path)
    monkeypatch.setattr(stress.helpers, "snapshot", loop.snapshot)
    monkeypatch.setattr(stress.helpers, "verify_all_fragments", lambda _: {"messages_verified": len(loop.messages)})
    loop.experiment.execute()
    report = loop.experiment.report
    assert report["status"] == "passed" and report["completed_turns"] == 1000
    assert len(loop.messages) == 2000 and len(loop.experiment.saved) == 2
    assert len(loop.experiment.expected) == 2000 and all(len(item) == 64 for item in loop.experiment.expected)
    checkpoint = report["checkpoints"][0]
    assert checkpoint["history_before_restart"]["pages"] == checkpoint["restart"]["history"]["pages"] == 20
    assert checkpoint["probe_count"] == len(report["probes"]) == 6
    assert report["message_request_latency"]["count"] == 1000
    assert report["user_16001_rejected_without_message_or_index_write"] is True
    loop.experiment.cleanup()
    assert report["cleanup"]["completed"] is True and loop.messages == []


def test_mock_missing_assistant_original_does_not_complete_or_pass_the_checkpoint(tmp_path, monkeypatch):
    loop = MockLoop(tmp_path, missing_role="assistant")
    monkeypatch.setattr(stress.helpers, "snapshot", loop.snapshot)
    with pytest.raises(stress.helpers.CheckFailure, match="reference missing"):
        loop.experiment.execute()
    assert loop.experiment.report["completed_turns"] < 1000
    assert loop.experiment.report["status"] != "passed"
    assert loop.experiment.report["checkpoints"] == []
    loop.experiment.cleanup()


@pytest.mark.parametrize("cleanup_failure", [False, True])
def test_interruption_publishes_terminal_non_passed_report_and_restores_environment(tmp_path, monkeypatch, cleanup_failure):
    events = []

    async def close_bootstrap():
        events.append("bootstrap_closed")

    main = ModuleType("app.main")
    main.app = SimpleNamespace(state=SimpleNamespace(provider=SimpleNamespace(aclose=close_bootstrap)))
    main.create_app = lambda *args, **kwargs: None
    config = ModuleType("app.config")
    config.Settings = lambda **kwargs: None
    package = ModuleType("app")
    package.__path__ = []
    for name, module in (("app", package), ("app.main", main), ("app.config", config)):
        monkeypatch.setitem(sys.modules, name, module)

    class InterruptedRun:
        def __init__(self, data_dir, factory, report, report_path, *args):
            self.report, self.report_path = report, report_path

        def execute(self):
            self.report["completed_turns"] = 3
            raise KeyboardInterrupt("PRIVATE-KEY-OR-TEXT")

        def cleanup(self):
            events.append("cleanup_attempted")
            if cleanup_failure:
                raise ValueError("PRIVATE-CLEANUP-TEXT")
            self.report["cleanup"] = {"completed": True}

        def refresh(self):
            events.append("progress_published")
            stress.write_report(self.report_path, self.report)

    monkeypatch.setattr(stress, "EnduranceRun", InterruptedRun)
    monkeypatch.setenv("ZHIYU_DATA_DIR", "prior-test-directory")
    output, report_path = [], tmp_path / "interrupted.json"
    result = stress.run(report_path, tmp_path / "data", turns=1000, output=output.append)
    assert result["status"] == ("failed" if cleanup_failure else "aborted")
    assert result["completed_turns"] == 3 and result["finished_at"]
    assert result["failure"]["type"] == "KeyboardInterrupt"
    assert "PRIVATE" not in json.dumps(result)
    assert events == ["cleanup_attempted", "progress_published", "bootstrap_closed"]
    assert os.environ["ZHIYU_DATA_DIR"] == "prior-test-directory"
    assert json.loads(report_path.read_text()) == result
    assert output[-1]["event"] == "finished" and output[-1]["status"] != "passed"
    if cleanup_failure:
        assert result["cleanup_internal_error"] is True
