"""Loopback-only UI test fixture: synthetic replies, never calls a cloud provider."""

import asyncio
import os
import sys
from pathlib import Path
from tempfile import TemporaryDirectory

import uvicorn

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))

from app.config import Settings  # noqa: E402
from app.providers import ProviderError  # noqa: E402


class SyntheticProvider:
    async def validate(self, provider_id, key, model):
        if key == "invalid-key":
            raise ProviderError(401, "测试 Key 无效")

    async def complete(self, provider_id, key, model, messages, max_tokens=2048):
        await asyncio.sleep(0.5)
        if messages[-1]["content"] == "trigger-http-failure":
            raise ProviderError(429, "【界面测试】模拟服务商限流")
        return '【界面测试】已收到。<img src=x onerror="window.fakeXss=true">'


if __name__ == "__main__":
    with TemporaryDirectory(prefix="zhiyu-ui-only-") as directory:
        os.environ["ZHIYU_DATA_DIR"] = directory
        os.environ["ZHIYU_DEMO"] = "false"
        os.environ["ZHIYU_AUTO_MEMORY_ANNOTATIONS"] = "false"
        os.environ.pop("ZHIYU_EMBEDDING_MODEL", None)
        from app.main import create_app

        application = create_app(Settings(data_dir=Path(directory)), provider_client=SyntheticProvider())

        @application.get("/__ui_fixture", include_in_schema=False)
        def fixture_marker():
            return {"synthetic_provider": True}

        uvicorn.run(application, host="127.0.0.1", port=8799, access_log=False)
