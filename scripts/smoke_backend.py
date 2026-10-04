"""本机真实 HTTP 演示烟测；只使用 demo-key，不读取任何云端密钥。"""

import argparse
from pathlib import Path

import httpx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8790")
    options = parser.parse_args()
    with httpx.Client(base_url=options.url, timeout=120, follow_redirects=False, trust_env=False) as client:
        health = client.get("/health")
        health.raise_for_status()
        assert health.json()["status"] == "ok"
        auth = client.post("/api/auth/login", json={"provider_id": "demo", "api_key": "demo-key", "model": "demo-local"})
        auth.raise_for_status()
        client.headers["Authorization"] = "Bearer " + auth.json()["token"]
        guide = Path(__file__).resolve().parents[1] / "docs" / "demo-guide.md"
        document = client.post("/api/documents", files={"file": (guide.name, guide.read_bytes())})
        document.raise_for_status()
        search = client.post("/api/knowledge/search", json={"query": "RAG", "top_k": 3})
        search.raise_for_status()
        assert search.json() and search.json()[0]["name"] == guide.name
        conversation = client.post("/api/conversations", json={"title": "知识库示例", "use_rag": True})
        conversation.raise_for_status()
        identifier = conversation.json()["id"]
        answer = client.post(f"/api/conversations/{identifier}/messages", json={"content": "RAG 知识库可以导入哪些文件？"})
        answer.raise_for_status()
        assert answer.json()["assistant_message"]["sources"]
        assert "【本地演示】" in answer.json()["assistant_message"]["content"]
        history = client.get(f"/api/conversations/{identifier}/messages")
        history.raise_for_status()
        assert len(history.json()) == 2
        print("HTTP smoke passed: health, demo login, upload, search, chat citations, persisted history")


if __name__ == "__main__":
    main()
