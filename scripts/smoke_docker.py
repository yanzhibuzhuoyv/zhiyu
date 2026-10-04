"""真实 Docker HTTP 联调，只使用随机演示 Key，不调用云端模型。"""

import json
import os
import subprocess
from datetime import UTC, datetime
from io import BytesIO
from pathlib import Path
from uuid import uuid4

import httpx
from docx import Document
from pypdf import PdfWriter
from pypdf.generic import DecodedStreamObject, DictionaryObject, NameObject

ROOT = Path(__file__).resolve().parents[1]
URL = "http://127.0.0.1:8791"
COMPOSE = ["docker", "compose", "-p", "zhiyu-docker-test", "-f", "compose.test.yaml"]


def compose(*args, demo=True, capture=False, input_text=None):
    environment = {**os.environ, "ZHIYU_DOCKER_TEST_DEMO": str(demo).lower()}
    return subprocess.run(
        [*COMPOSE, *args], cwd=ROOT, env=environment, check=True,
        text=True, encoding="utf-8", capture_output=capture, input=input_text,
        timeout=60,
    ).stdout


def checked(client, method, path, expected=200, **kwargs):
    response = client.request(method, path, **kwargs)
    assert response.status_code == expected, f"{method} {path}: {response.status_code}"
    return response.json() if response.content else None


def login(client, key):
    payload = {"provider_id": "demo", "api_key": key, "model": "demo-local"}
    result = checked(client, "POST", "/api/auth/login", json=payload)
    assert "api_key" not in result
    client.headers["Authorization"] = "Bearer " + result["token"]
    return client.headers["Authorization"]


def fixtures():
    document = Document()
    document.add_paragraph("员工年假：工作满一年可享有五天带薪年假，提交申请后由负责人批准。")
    word = BytesIO()
    document.save(word)
    pdf = BytesIO()
    writer = PdfWriter()
    page = writer.add_blank_page(width=300, height=200)
    font = DictionaryObject({NameObject("/Type"): NameObject("/Font"),
                             NameObject("/Subtype"): NameObject("/Type1"),
                             NameObject("/BaseFont"): NameObject("/Helvetica")})
    page[NameObject("/Resources")] = DictionaryObject({NameObject("/Font"): DictionaryObject({
        NameObject("/F1"): writer._add_object(font),
    })})
    stream = DecodedStreamObject()
    stream.set_data(b"BT /F1 12 Tf 20 150 Td (Docker PDF knowledge verification.) Tj ET")
    page[NameObject("/Contents")] = writer._add_object(stream)
    writer.write(pdf)
    return [
        ("docker-guide.md", "知识库支持中文检索，可以导入 TXT、Markdown、PDF 和 DOCX。".encode()),
        ("docker-leave.docx", word.getvalue()),
        ("docker-guide.pdf", pdf.getvalue()),
        ("docker-note.txt", "Docker 容器重建后，会话和知识库保存在数据卷里。".encode()),
    ]


def verify_production_defaults(client):
    compose("up", "-d", "--wait", "--wait-timeout", "45", "backend", demo=False)
    health = checked(client, "GET", "/health")
    assert health["rag_mode"] == "bm25"
    providers = checked(client, "GET", "/api/providers")
    assert len(providers) == 11 and all(item["id"] != "demo" for item in providers)
    checked(client, "POST", "/api/auth/login", expected=400,
            json={"provider_id": "demo", "api_key": "demo-key"})
    checked(client, "GET", "/api/documents", expected=401)
    print("PASS: production defaults, 11 real API entries, demo disabled, authentication")


def verify_uploads(owner, other):
    documents = [checked(owner, "POST", "/api/documents", expected=201,
                         files={"file": (name, data)}) for name, data in fixtures()]
    assert checked(other, "GET", "/api/documents") == []
    found = checked(owner, "POST", "/api/knowledge/search", json={"query": "带薪年假"})
    assert found[0]["name"] == "docker-leave.docx"
    assert checked(other, "POST", "/api/knowledge/search", json={"query": "年假"}) == []
    checked(other, "DELETE", f"/api/documents/{documents[0]['id']}", expected=404)
    checked(owner, "POST", "/api/documents", expected=400, files={"file": ("run.exe", b"bad")})
    checked(owner, "POST", "/api/documents", expected=413,
            files={"file": ("large.txt", b"a" * (5 * 1024 * 1024 + 1))})
    chunks = (b"x" * 65536 for _ in range(97))
    checked(owner, "POST", "/api/knowledge/search", expected=413,
            content=chunks, headers={"Content-Type": "application/json"})
    print("PASS: TXT/MD/PDF/DOCX, Chinese retrieval, isolation, file and chunked body limits")
    return documents


def verify_chat(owner, other):
    conversation = checked(owner, "POST", "/api/conversations", expected=201,
                           json={"title": "Docker 年假咨询", "use_rag": True})
    path = f"/api/conversations/{conversation['id']}"
    first = checked(owner, "POST", path + "/messages", json={"content": "带薪年假有几天？"})
    answer = first["assistant_message"]
    assert "【本地演示】" in answer["content"]
    assert any(source["name"] == "docker-leave.docx" for source in answer["sources"])
    checked(owner, "PATCH", path, json={"title": "Docker 持久化验证", "use_rag": False})
    second = checked(owner, "POST", path + "/messages", json={"content": "继续聊天"})
    assert second["assistant_message"]["sources"] == []
    history = checked(owner, "GET", path + "/messages")
    assert [message["role"] for message in history] == ["user", "assistant"] * 2
    checked(other, "GET", path + "/messages", expected=404)
    checked(other, "POST", path + "/messages", expected=404, json={"content": "hello"})
    print("PASS: two chat turns, citations, RAG switch, edit, private history")
    return path, history


def verify_recovery(owner, other, owner_key, other_key, path, history, documents, recreate):
    old_authorization = owner.headers["Authorization"]
    if recreate:
        compose("up", "-d", "--force-recreate", "--wait", "--wait-timeout", "45", "backend")
    else:
        compose("restart", "backend")
        compose("up", "-d", "--wait", "--wait-timeout", "45", "backend")
    checked(owner, "GET", "/api/conversations", expected=401)
    login(owner, owner_key)
    login(other, other_key)
    assert owner.headers["Authorization"] != old_authorization
    assert checked(owner, "GET", path + "/messages") == history
    restored_documents = checked(owner, "GET", "/api/documents")
    assert {item["id"]: item for item in restored_documents} == {
        item["id"]: item for item in documents
    }
    assert checked(other, "GET", "/api/conversations") == []
    assert checked(other, "GET", "/api/documents") == []
    assert checked(owner, "POST", "/api/knowledge/search", json={"query": "带薪年假"})
    print("PASS: " + ("container recreation" if recreate else "restart") +
          ", stale token rejected, same-key history/library recovery, isolation")


def verify_container(keys):
    identifier = compose("ps", "-q", "backend", capture=True).strip()
    inspection = subprocess.run(["docker", "inspect", identifier], check=True, capture_output=True,
                                text=True, encoding="utf-8", timeout=15)
    state = json.loads(inspection.stdout)[0]
    assert state["State"]["Health"]["Status"] == "healthy"
    assert state["HostConfig"]["ReadonlyRootfs"]
    assert state["HostConfig"]["CapDrop"] == ["ALL"]
    assert state["HostConfig"]["PortBindings"]["8790/tcp"][0]["HostIp"] == "127.0.0.1"
    probe = (
        "import json, os, sys; from pathlib import Path; "
        "keys=json.load(sys.stdin); assert os.getuid()==10001; "
        "assert all(k.encode() not in f.read_bytes() for f in Path('/data').rglob('*') "
        "if f.is_file() for k in keys); "
        "print(json.dumps({'uid':os.getuid(), 'keys_absent_from_data':True}))"
    )
    result = compose("exec", "-T", "backend", "python", "-c", probe,
                     capture=True, input_text=json.dumps(keys))
    print("PASS: non-root UID 10001, read-only root, loopback port, no raw keys in data volume")
    return {"container_id": identifier, "image_id": state["Image"],
            "health": state["State"]["Health"]["Status"], **json.loads(result)}


def main():
    options = {"base_url": URL, "timeout": 30, "follow_redirects": False, "trust_env": False}
    owner_key, other_key = "docker-owner-" + uuid4().hex, "docker-other-" + uuid4().hex
    with httpx.Client(**options) as owner, httpx.Client(**options) as other:
        verify_production_defaults(owner)
        compose("up", "-d", "--wait", "--wait-timeout", "45", "backend")
        login(owner, owner_key)
        login(other, other_key)
        documents = verify_uploads(owner, other)
        path, history = verify_chat(owner, other)
        for recreate in (False, True):
            verify_recovery(owner, other, owner_key, other_key, path, history, documents, recreate)
        state = verify_container([owner_key, other_key])
        for document in documents:
            checked(owner, "DELETE", f"/api/documents/{document['id']}", expected=204)
        checked(owner, "DELETE", path, expected=204)
        assert checked(owner, "GET", "/api/documents") == []
        assert checked(owner, "GET", "/api/conversations") == []
        checked(owner, "DELETE", "/api/auth/session", expected=204)
        checked(owner, "GET", "/api/conversations", expected=401)
        print("PASS: document/conversation deletion and logout; smoke fixtures removed")
    report = {"checked_at": datetime.now(UTC).isoformat(), "url": URL,
              "http_smoke": "passed",
              "cloud_api_called": False, **state}
    output = ROOT / "artifacts" / "docker-validation.json"
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Docker smoke passed. Evidence: {output}")


if __name__ == "__main__":
    main()
