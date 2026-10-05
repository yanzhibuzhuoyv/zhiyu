"""Harness tests use mock HTTP only; the real 10,000-turn run is explicit."""

import importlib.util
import json
from pathlib import Path

import httpx
import pytest

SCRIPT = Path(__file__).parents[1] / "stress_companion.py"
SPEC = importlib.util.spec_from_file_location("stress_companion", SCRIPT)
stress = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(stress)


def message(identifier, role, content):
    return {"id": identifier, "role": role, "content": content, "sources": [],
            "created_at": "2026-10-05T00:00:00+00:00"}


def test_checkpoints_and_probes_are_included_in_exact_total_turn_count():
    assert stress.stage_turns(10000) == [1000, 10000]
    assert stress.stage_turns(1000) == [1000]
    assert stress.stage_turns(2000) == [1000, 2000]
    schedule = stress.probe_schedule(10000)
    assert len(schedule) == 16
    assert set(schedule) == {*range(993, 1001), *range(9993, 10001)}
    assert len([n for n in range(1, 10001) if n not in schedule]) == 9984
    assert len(stress.probe_schedule(40)) == 5
    assert 6 not in stress.probe_schedule(40)


def test_long_message_probes_require_both_middle_and_tail_after_750_turns():
    schedule = stress.probe_schedule(10000)
    original = stress.turn_content(250, 47, schedule, 10000)
    assert len(original) < 16000 and len(original) > 15000
    probes = [probe for probe in schedule.values() if probe["name"].startswith("long_message_")]
    assert len(probes) == 4 and all(probe["anchor_turn"] == 250 for probe in probes)
    assert all(probe["fact"] in original for probe in probes)
    assert original.index("琉璃沙漏-250") > 7000
    assert original.index("青桥月色-250") > 15000


def test_seeded_narrative_is_varied_length_bounded_and_reproducible():
    narratives = [stress.narrative(n, 47) for n in range(10, 1010)]
    assert narratives == [stress.narrative(n, 47) for n in range(10, 1010)]
    assert narratives != [stress.narrative(n, 48) for n in range(10, 1010)]
    assert len(set(narratives)) == 1000
    assert all(256 <= len(text) <= 512 for text in narratives)
    assert all("约会" in text for text in narratives)
    assert len({text.split("今天想告诉你的是：", 1)[-1][:150] for text in narratives}) > 900


def test_percentiles_interpolate_and_empty_summary_has_no_fake_zero():
    result = stress.latency_summary([1, 2, 3, 4])
    assert result == {"count": 4, "p50_ms": 2.5, "p95_ms": 3.85,
                      "p99_ms": 3.97, "max_ms": 4, "mean_ms": 2.5}
    assert stress.latency_summary([]) == {"count": 0}


def test_history_verification_rejects_duplicate_missing_wrong_order_and_changed_content():
    original = [message("u1", "user", "第一轮"), message("a1", "assistant", "收到")]
    expected = [stress.message_signature(item) for item in original]
    assert stress.verify_history_page(original, expected, 2, set()) == 0
    for damaged in ([original[0], original[0]], original[::-1],
                    [original[0], {**original[1], "content": "changed"}]):
        with pytest.raises(stress.ValidationFailure):
            stress.verify_history_page(damaged, expected, 2, set())
    with pytest.raises(stress.ValidationFailure):
        stress.verify_history_page(original[:1], expected, 2, set())


def test_history_verification_tracks_ids_across_pages():
    original = [message("u1", "user", "一"), message("a1", "assistant", "二"),
                message("u2", "user", "三"), message("a2", "assistant", "四")]
    expected, seen = [stress.message_signature(item) for item in original], set()
    position = stress.verify_history_page(original[2:], expected, 4, seen)
    assert position == 2
    assert stress.verify_history_page(original[:2], expected, position, seen) == 0
    assert len(seen) == 4
    with pytest.raises(stress.ValidationFailure):
        stress.verify_history_page(original[:2], expected, position, seen)


def test_probe_requires_original_user_id_fact_and_matching_timestamp():
    original = message("anchor", "user", "我们的纪念日是七月九日。")
    ref = {"message_id": "anchor", "turn_id": "anchor", "role": "user",
           "text": original["content"], "created_at": original["created_at"],
           "score": 2, "truncated": False}
    probe = {"name": "anniversary", "anchor_turn": 1, "fact": "七月九日"}
    anchors = {1: original}
    assert stress.check_probe(probe, [ref], anchors)["passed"] is True
    for altered in ({"role": "assistant"}, {"message_id": "someone-else"},
                    {"text": "无关文字"}, {"created_at": "2026-01-01"}):
        assert stress.check_probe(probe, [{**ref, **altered}], anchors)["passed"] is False
    assert stress.check_probe(probe, [], anchors)["passed"] is False
    forged = {**ref, "text": "七月九日，但原文没有说过这句话。"}
    assert stress.check_probe(probe, [forged], anchors)["passed"] is False


def compound_fixture():
    anchors = {
        turn: message(f"anchor-{turn}", "user", stress.turn_content(turn, 47, {}, 1000))
        for turn in (10, 11, 12)
    }
    for turn, anchor in anchors.items():
        anchor["created_at"] = f"2026-10-05T00:00:{turn:02d}+00:00"
    references = [
        {"message_id": anchor["id"], "turn_id": anchor["id"], "role": "user",
         "text": anchor["content"], "created_at": anchor["created_at"], "score": 1,
         "truncated": False}
        for turn, anchor in anchors.items() if turn != 12
    ]
    probe = stress.probe_schedule(1000)[1000]
    return probe, anchors, references


def test_compound_probe_has_two_independent_raw_sources_and_forbidden_birthday():
    probe, anchors, references = compound_fixture()
    assert probe["name"] == "related_raw_birthday_and_chicken"
    assert probe["content"] == "我想吃鸡翅"
    assert anchors[10]["content"] == "前天是我生日，家里给我办了小聚会。"
    assert anchors[11]["content"] == "我前天晚上吃了鸡翅，是烤的。"
    assert anchors[12]["content"] == "明天是隔壁朋友的生日，我打算去买礼物。"
    result = stress.check_probe(probe, references, anchors)
    assert result["passed"] is True
    checks = result["required_source_checks"]
    assert len(checks) == 2 and {check["anchor_turn"] for check in checks} == {10, 11}
    assert all(check["passed"] for check in checks)
    assert {check["expected_message_id"] for check in checks} == {"anchor-10", "anchor-11"}
    assert result["forbidden_source_checks"] == [{"anchor_turn": 12, "expected_message_id": "anchor-12", "absent": True}]


@pytest.mark.parametrize("absent_turn", [10, 11])
def test_compound_probe_fails_if_either_related_original_message_is_missing(absent_turn):
    probe, anchors, references = compound_fixture()
    references = [ref for ref in references if ref["message_id"] != f"anchor-{absent_turn}"]
    result = stress.check_probe(probe, references, anchors)
    assert result["passed"] is False
    assert sum(check["passed"] for check in result["required_source_checks"]) == 1


@pytest.mark.parametrize("turn,changed", [
    (10, {"message_id": "another-message"}), (11, {"turn_id": "another-turn"}),
    (10, {"created_at": "2026-01-01"}), (11, {"role": "assistant"}),
    (10, {"text": "前天是我生日，家里给我办了小聚会。并且我说了这些不存在的话。"}),
    (11, {"text": "我前天晚上吃了鸡翅，是烤的。并且我说了这些不存在的话。"}),
])
def test_compound_probe_verifies_each_original_id_turn_time_role_and_exact_excerpt(turn, changed):
    probe, anchors, references = compound_fixture()
    references = [{**ref, **changed} if ref["message_id"] == f"anchor-{turn}" else ref for ref in references]
    assert stress.check_probe(probe, references, anchors)["passed"] is False


def test_compound_probe_rejects_unrelated_neighbor_birthday_even_when_required_sources_found():
    probe, anchors, references = compound_fixture()
    distractor = anchors[12]
    references.append({"message_id": distractor["id"], "turn_id": distractor["id"], "role": "user",
                       "text": distractor["content"], "created_at": distractor["created_at"]})
    result = stress.check_probe(probe, references, anchors)
    assert all(check["passed"] for check in result["required_source_checks"])
    assert result["passed"] is False and result["forbidden_source_checks"][0]["absent"] is False


@pytest.mark.parametrize("url", ["http://user:secret@localhost", "http://localhost?key=x",
                                "http://localhost#fragment", "file:///tmp/socket"])
def test_url_validation_keeps_credentials_and_queries_out_of_report(url):
    with pytest.raises(ValueError):
        stress.validated_url(url)


def test_atomic_report_does_not_include_in_memory_identity_or_raw_http_failure(tmp_path):
    report = tmp_path / "result.json"
    identity = "never-log-api-key-secret"

    def handler(request):
        if request.url.path == "/api/providers":
            return httpx.Response(200, json=[{"id": "demo"}])
        if request.url.path == "/api/auth/login":
            return httpx.Response(200, json={"token": "never-log-token-secret"})
        if request.method == "DELETE":
            return httpx.Response(204)
        return httpx.Response(503, text=identity + " never-log-token-secret")

    output = []
    with httpx.Client(base_url="http://test", transport=httpx.MockTransport(handler)) as client:
        result = stress.run("http://test", report, turns=40, client=client,
                            identity=identity, emit=output.append)
    serialized = report.read_text(encoding="utf-8")
    assert result["status"] == "failed"
    assert "HTTP 503" in result["failure"]["message"]
    assert identity not in serialized and "never-log-token-secret" not in serialized
    assert identity not in json.dumps(output) and "never-log-token-secret" not in json.dumps(output)
    assert not report.with_suffix(".json.tmp").exists()
    assert result["cleanup"]["session_revoked"] is True


def test_transport_error_is_not_retried_or_logged_with_sensitive_exception_text(tmp_path):
    calls = []

    def handler(request):
        calls.append(request.url.path)
        raise httpx.ConnectError("token-private-value", request=request)

    with httpx.Client(base_url="http://test", transport=httpx.MockTransport(handler)) as client:
        result = stress.run("http://test", tmp_path / "result.json", turns=40,
                            client=client, emit=lambda _: None)
    assert calls == ["/api/providers"]
    assert result["http"]["failed_requests"] == 1
    assert "token-private-value" not in json.dumps(result)


class DemoServer:
    def __init__(self, missing_probe=False, cleanup_failure=False, turns=40, missing_related=None):
        self.messages = []
        self.schedule = stress.probe_schedule(turns)
        self.deleted = []
        self.missing_probe = missing_probe
        self.cleanup_failure = cleanup_failure
        self.missing_related = missing_related
        self.session_revocations = 0

    def handle(self, request):
        path = request.url.path
        if path == "/api/providers":
            return httpx.Response(200, json=[{"id": "demo"}])
        if path == "/api/auth/login":
            foreign = json.loads(request.content)["api_key"].startswith("stress-isolation-")
            return httpx.Response(200, json={"token": "foreign" if foreign else "owner"})
        if path == "/api/auth/session":
            self.session_revocations += 1
            return httpx.Response(204)
        if path == "/api/conversations" and request.method == "POST":
            first = "companion_profile" in json.loads(request.content)
            return httpx.Response(201, json={"id": "main" if first else "sibling", "profile_locked": first})
        if path == "/api/conversations":
            return httpx.Response(200, json=[{"id": "main", "companion_profile": {**stress.PROFILE, "instructions": ""}}])
        if request.method == "DELETE":
            self.deleted.append(path)
            return httpx.Response(503 if self.cleanup_failure and path.endswith("main") else 204)
        if request.headers.get("authorization") == "Bearer foreign" or path.startswith("/api/conversations/sibling/"):
            return httpx.Response(404)
        if path.endswith("/history"):
            if request.url.params.get("limit") == "101":
                return httpx.Response(422)
            position = len(self.messages)
            if before := request.url.params.get("before"):
                position = next(index for index, item in enumerate(self.messages) if item["id"] == before)
            page = self.messages[max(0, position - 100):position]
            has_more = position > 100
            return httpx.Response(200, json={"messages": page, "has_more": has_more,
                                            "next_before": page[0]["id"] if has_more else None})
        if path.endswith("/messages"):
            return self.send(request)
        raise AssertionError(f"Unexpected mock route {request.method} {path}")

    def send(self, request):
        turn = len(self.messages) // 2 + 1
        references = []
        if turn in self.schedule and not self.missing_probe:
            probe = self.schedule[turn]
            anchors = [source["anchor_turn"] for source in probe.get("required_sources", [probe])]
            for anchor in anchors:
                if anchor == self.missing_related:
                    continue
                for offset in (0, 1):
                    original = self.messages[(anchor - 1) * 2 + offset]
                    text = original["content"]
                    if len(text) > 768:
                        fact = probe["fact"]
                        start = max(0, text.index(fact) - 192)
                        text = text[start:start + 768]
                    references.append({"message_id": original["id"], "role": original["role"],
                                       "turn_id": self.messages[(anchor - 1) * 2]["id"], "text": text,
                                       "created_at": original["created_at"], "score": 1,
                                       "truncated": len(text) < len(original["content"])})
        user = message(f"u{turn}", "user", json.loads(request.content)["content"])
        assistant = message(f"a{turn}", "assistant", "【mock demo】已收到日常心情。")
        self.messages.extend([user, assistant])
        return httpx.Response(200, json={"user_message": user, "assistant_message": assistant, "memory_refs": references})


def test_complete_mock_run_exact_turns_five_probes_and_404_excluded_from_latency(tmp_path):
    server, output = DemoServer(), []
    with httpx.Client(base_url="http://test", transport=httpx.MockTransport(server.handle)) as client:
        result = stress.run("http://test", tmp_path / "result.json", turns=40, client=client,
                            progress_every=10, emit=output.append)
    assert result["status"] == "passed", result.get("failure")
    assert result["completed_turns"] == 40 and len(server.messages) == 80
    assert result["http"]["message_request_latency"]["count"] == 40
    assert result["http"]["failed_requests"] == 0
    assert result["checkpoints"][0]["history"]["messages"] == 80
    assert len(result["probes"]) == 5 and all(probe["passed"] for probe in result["probes"])
    assert result["user_content_length_chars"]["count"] == 40
    assert "p50_chars" in result["user_content_length_chars"]
    assert result["cleanup"]["completed"] is True
    assert len(server.deleted) == 2 and server.session_revocations == 3
    assert [item["completed_turns"] for item in output if item["event"] == "progress"] == [10, 20, 30, 40]


def test_missing_old_fact_fails_even_after_all_requested_turns_and_cleanup(tmp_path):
    server = DemoServer(missing_probe=True)
    with httpx.Client(base_url="http://test", transport=httpx.MockTransport(server.handle)) as client:
        result = stress.run("http://test", tmp_path / "result.json", turns=40,
                            client=client, emit=lambda _: None)
    assert result["status"] == "failed" and result["completed_turns"] == 40
    assert result["checkpoints"][0]["status"] == "failed"
    assert all(probe["passed"] is False for probe in result["probes"])
    assert result["cleanup"]["completed"] is True
    assert len(server.deleted) == 2


def test_cleanup_failure_prevents_pass_and_does_not_skip_other_cleanup(tmp_path):
    server = DemoServer(cleanup_failure=True)
    with httpx.Client(base_url="http://test", transport=httpx.MockTransport(server.handle)) as client:
        result = stress.run("http://test", tmp_path / "result.json", turns=40,
                            client=client, emit=lambda _: None)
    assert result["status"] == "failed"
    assert result["cleanup"]["completed"] is False
    assert result["cleanup"]["conversations_deleted"] == 1
    assert len(server.deleted) == 2 and server.session_revocations == 3


def test_long_payload_contains_middle_tail_and_unicode_within_message_limit():
    content = stress.long_narrative(250, 4705)
    assert 15800 <= len(content) <= 16000
    assert "琉璃沙漏-250" in content[7800:8100]
    assert "青桥月色-250" in content[-80:]
    assert len(content.encode()) > len(content)


@pytest.mark.parametrize("missing_related,expected_status", [(None, "passed"), (10, "failed"), (11, "failed")])
def test_thousand_mock_turns_verify_all_eight_probes_and_both_related_sources(tmp_path, missing_related, expected_status):
    server = DemoServer(turns=1000, missing_related=missing_related)
    with httpx.Client(base_url="http://test", transport=httpx.MockTransport(server.handle)) as client:
        result = stress.run("http://test", tmp_path / "result.json", turns=1000,
                            client=client, progress_every=1000, emit=lambda _: None)
    assert result["status"] == expected_status, result.get("failure")
    assert result["completed_turns"] == 1000 and len(server.messages) == 2000
    assert result["checkpoints"][0]["history"]["messages"] == 2000
    assert result["checkpoints"][0]["history"]["pages"] == 20
    assert result["checkpoints"][0]["probe_count"] == len(result["probes"]) == 8
    composite = result["probes"][-1]
    assert composite["name"] == "related_raw_birthday_and_chicken"
    assert composite["passed"] is (missing_related is None)
    assert sum(check["passed"] for check in composite["required_source_checks"]) == (2 if missing_related is None else 1)
    assert result["cleanup"]["completed"] is True
