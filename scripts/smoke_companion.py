"""Real HTTP checks over disposable demo identities; never reads private credentials."""

import argparse
import json
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import httpx


def checked(response: httpx.Response, expected: int = 200):
    if response.status_code != expected:
        raise AssertionError(f"HTTP {response.status_code}, expected {expected}, path={response.request.url.path}")
    return response.json() if response.content else None


def login(client: httpx.Client, key: str) -> dict:
    session = checked(client.post("/api/auth/login", json={"provider_id": "demo", "api_key": key}))
    return {"Authorization": "Bearer " + session["token"]}


def run(base_url: str) -> dict:
    report = {"checked_at": datetime.now(UTC).isoformat(), "url": base_url, "cloud_calls": 0}
    key = "smoke-companion-" + uuid4().hex
    with httpx.Client(base_url=base_url, timeout=120, trust_env=False, follow_redirects=False) as client:
        providers = checked(client.get("/api/providers"))
        assert any(provider["id"] == "demo" for provider in providers), "Enable demo in isolated test backend"
        headers = login(client, key)
        conversations, documents = [], []
        try:
            profile = {"mode": "preset", "style": "gentle", "role": "girlfriend", "name": "测试恋人", "user_name": "小舟"}
            companion = checked(client.post("/api/conversations", headers=headers,
                                          json={"companion_profile": profile, "use_rag": False}), 201)
            identifier = companion["id"]
            conversations.append(identifier)
            assert companion["profile_locked"] is True
            for update in ({"style": "steady"}, {"mode": "custom"}, {"name": "另一个名字"}):
                checked(client.patch(f"/api/conversations/{identifier}", headers=headers,
                                     json={"companion_profile": {**profile, **update}}), 409)
            checked(client.patch(f"/api/conversations/{identifier}", headers=headers,
                                 json={"companion_profile": None}), 409)
            first = checked(client.post(f"/api/conversations/{identifier}/messages", headers=headers,
                                        json={"content": "我们的纪念日是七月九日，请记住这个纪念日。"}))
            first_id = first["user_message"]["id"]
            for number in range(60):
                checked(client.post(f"/api/conversations/{identifier}/messages", headers=headers,
                                    json={"content": f"日常记录第{number}次，今天散步看见了白云。"}))
            recalled = checked(client.post(f"/api/conversations/{identifier}/messages", headers=headers,
                                           json={"content": "你还记得我们的纪念日是哪一天吗？"}))
            assert any(ref["message_id"] == first_id and "七月九日" in ref["text"]
                       for ref in recalled["memory_refs"])
            report["old_fact_recalled_after_60_turns"] = True
            report["memory_independent_of_document_rag"] = True
            page = checked(client.get(f"/api/conversations/{identifier}/history", headers=headers))
            assert len(page["messages"]) == 100 and page["has_more"]
            older = checked(client.get(f"/api/conversations/{identifier}/history", headers=headers,
                                       params={"before": page["next_before"]}))
            combined = older["messages"] + page["messages"]
            assert len(combined) == 124 and len({message["id"] for message in combined}) == 124
            assert combined[0]["id"] == first_id and not older["has_more"]
            report["paged_history_messages"] = len(combined)
            other_headers = login(client, "smoke-isolation-" + uuid4().hex)
            checked(client.get(f"/api/conversations/{identifier}/history", headers=other_headers), 404)
            checked(client.delete("/api/auth/session", headers=other_headers), 204)
            report["history_identity_isolation"] = True
            checked(client.delete("/api/auth/session", headers=headers), 204)
            headers = login(client, key)
            restored = checked(client.get("/api/conversations", headers=headers))
            assert next(item for item in restored if item["id"] == identifier)["companion_profile"]["name"] == "测试恋人"
            report["preset_and_history_restored_with_same_key"] = True

            custom = checked(client.post("/api/conversations", headers=headers,
                                         json={"companion_profile": {"mode": "custom", "instructions": "叫我小舟"}}), 201)
            conversations.append(custom["id"])
            updated = checked(client.patch(f"/api/conversations/{custom['id']}", headers=headers,
                                          json={"companion_profile": {"mode": "custom", "instructions": "请记住后来培养的习惯"}}))
            assert updated["companion_profile"]["instructions"] == "请记住后来培养的习惯"
            checked(client.patch(f"/api/conversations/{custom['id']}", headers=headers,
                                 json={"companion_profile": profile}), 409)
            report["custom_training_editable_preset_switch_rejected"] = True

            document_text = "测试专用资料：恋人纪念日安排是一同散步，然后看星星。" * 100
            document = checked(client.post("/api/documents", headers=headers,
                                          files={"file": ("companion-test.md", document_text.encode(), "text/markdown")}), 201)
            documents.append(document["id"])
            checked(client.patch(f"/api/conversations/{identifier}", headers=headers, json={"use_rag": True}))
            cited = checked(client.post(f"/api/conversations/{identifier}/messages", headers=headers,
                                       json={"content": "资料中恋人纪念日怎么安排？"}))
            expected_sources = cited["assistant_message"]["sources"]
            assert expected_sources and any("看星星" in source["text"] for source in expected_sources)
            saved = checked(client.get(f"/api/conversations/{identifier}/history", headers=headers))
            assert saved["messages"][-1]["sources"] == expected_sources
            checked(client.delete(f"/api/documents/{documents.pop()}", headers=headers), 204)
            saved = checked(client.get(f"/api/conversations/{identifier}/history", headers=headers))
            assert saved["messages"][-1]["sources"] == expected_sources
            report["compressed_sources_roundtrip_and_document_deletion"] = True
        finally:
            for identifier in conversations:
                checked(client.delete(f"/api/conversations/{identifier}", headers=headers), 204)
            for identifier in documents:
                checked(client.delete(f"/api/documents/{identifier}", headers=headers), 204)
            checked(client.delete("/api/auth/session", headers=headers), 204)
    return {**report, "status": "passed", "disposable_data_cleaned": True}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8791")
    parser.add_argument("--report", type=Path, default=Path("artifacts/companion-validation.json"))
    options = parser.parse_args()
    result = run(options.url)
    options.report.parent.mkdir(parents=True, exist_ok=True)
    options.report.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
