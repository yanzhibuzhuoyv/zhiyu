"""Actual foreground API path with disposable data and the app's normal limits."""

import hashlib
import json
import os
import sys
import time
from pathlib import Path
from tempfile import TemporaryDirectory

from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "backend"))

from deepseek_flash_eval import MODEL, load_credentials  # noqa: E402

from app.config import Settings  # noqa: E402
from app.providers import ProviderClient  # noqa: E402


class ObservingProvider(ProviderClient):
    def __init__(self):
        super().__init__()
        self.observations = []

    async def complete(self, provider_id, key, model, messages, max_tokens=2048):
        started = time.perf_counter()
        content = await super().complete(provider_id, key, model, messages, max_tokens)
        self.observations.append({"messages": messages, "max_tokens": max_tokens,
                                  "seconds": time.perf_counter() - started})
        return content


def exercise(client, key):
    auth = client.post("/api/auth/login", json={"provider_id": "deepseek", "api_key": key, "model": MODEL})
    if auth.status_code != 200:
        raise RuntimeError("real_authentication_failed")
    headers = {"Authorization": "Bearer " + auth.json()["token"]}
    profile = {"mode": "preset", "style": "warm_v2", "name": "小语"}
    created = client.post("/api/conversations", headers=headers, json={"companion_profile": profile})
    if created.status_code != 201 or not created.json()["profile_locked"]:
        raise RuntimeError("preset_creation_failed")
    url = f"/api/conversations/{created.json()['id']}"
    results = []
    for content in ("这是合成试用：今天散步时看见一片像小船的叶子，很想分享给你。",
                    "这是合成资料：我生日是公历十一月二十三日。下次问起来别猜哦。",
                    "我刚才告诉你的生日是哪天？你还记得吗？"):
        started = time.perf_counter()
        response = client.post(url + "/messages", headers=headers, json={"content": content})
        if response.status_code != 200:
            raise RuntimeError("actual_chat_path_failed")
        results.append({"seconds": time.perf_counter() - started, "response": response.json()})
    if len(client.get(url + "/messages", headers=headers).json()) != 6:
        raise RuntimeError("original_pair_persistence_failed")
    if client.patch(url, headers=headers, json={"companion_profile": None}).status_code != 409:
        raise RuntimeError("preset_lock_failed")
    if client.delete("/api/auth/session", headers=headers).status_code != 204:
        raise RuntimeError("logout_failed")
    return results


def main():
    credentials = load_credentials(ROOT.parent / "rag-system/.env")
    output = ROOT / "artifacts/companion-preset-v2-production-smoke.json"
    with TemporaryDirectory(prefix="zhiyu-real-preset-smoke-") as directory:
        os.environ["ZHIYU_DATA_DIR"] = directory
        os.environ["ZHIYU_AUTO_MEMORY_ANNOTATIONS"] = "false"
        os.environ.pop("ZHIYU_EMBEDDING_MODEL", None)
        from app.main import create_app

        provider = ObservingProvider()
        with TestClient(create_app(Settings(data_dir=Path(directory)), provider_client=provider)) as client:
            results = exercise(client, credentials.key)
        report = {"passed": True, "cloud_calls": 4, "synthetic_data_only": True, "preset": "warm_v2",
                  "production_token_budget": 2048, "background_annotations": False,
                  "observations": provider.observations, "turns": results}
        if hashlib.sha256((ROOT.parent / "rag-system/.env").read_bytes()).hexdigest() != credentials.file_sha256:
            raise RuntimeError("credential_file_changed")
        text = json.dumps(report, ensure_ascii=False, indent=2)
        if credentials.key in text:
            raise ValueError("secret_in_report")
        output.write_text(text + "\n", encoding="utf-8")
    print(json.dumps({"passed": True, "cloud_calls": 4, "output": str(output)}))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(json.dumps({"passed": False, "error_type": type(error).__name__}))
        raise SystemExit(1) from None
