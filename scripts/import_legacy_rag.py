"""Read legacy RAG metadata and explicitly upload it to the signed-in user's library.

No source documents, FAISS indexes, .env files or legacy files are modified.
"""

import argparse
import getpass
import hashlib
import json
import os
import re
import secrets
import sys
from collections import defaultdict
from pathlib import Path, PurePosixPath
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

MAX_METADATA_BYTES = 25 * 1024 * 1024
MAX_DOCUMENT_CHARS = 1_000_000
MAX_UPLOAD_BYTES = 5 * 1024 * 1024


def read_legacy_documents(source: Path) -> tuple[list[tuple[str, bytes]], int]:
    source = source / "metadata.json" if source.is_dir() else source
    if source.stat().st_size > MAX_METADATA_BYTES:
        raise ValueError("旧索引元数据过大，请按来源拆分后迁移")
    try:
        data = json.loads(source.read_text(encoding="utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError("旧索引元数据不是有效 UTF-8 JSON") from exc
    entries = data.get("entries") if isinstance(data, dict) else None
    if not isinstance(entries, dict) or len(entries) > 100000:
        raise ValueError("旧索引缺少有效 entries 或块数过多")
    grouped = defaultdict(list)
    for entry in entries.values():
        if not isinstance(entry, dict):
            raise ValueError("旧索引包含无效文字块")
        origin, text = entry.get("source"), entry.get("text")
        position = entry.get("chunk_index", 0)
        if (
            not isinstance(origin, str)
            or not origin
            or not isinstance(text, str)
            or not text.strip()
        ):
            raise ValueError("旧索引的来源或文字块无效")
        if type(position) is not int or position < 0 or len(text) > MAX_DOCUMENT_CHARS:
            raise ValueError("旧索引的文字块顺序或长度无效")
        grouped[origin].append((position, text))
    documents = [
        (_import_name(origin), _join_chunks(sorted(chunks)).encode("utf-8"))
        for origin, chunks in grouped.items()
    ]
    if any(len(content) > MAX_UPLOAD_BYTES for _, content in documents):
        raise ValueError("迁移后的单份文档超过 5 MB，请拆分后迁移")
    return documents, len(entries)


def _import_name(origin: str) -> str:
    basename = PurePosixPath(origin.replace("\\", "/")).stem
    stem = re.sub(r"[^\w\u3400-\u9fff.-]", "_", basename)[:100] or "document"
    suffix = hashlib.sha256(origin.encode()).hexdigest()[:12]
    return f"legacy-{stem}-{suffix}.md"


def _join_chunks(chunks: list[tuple[int, str]]) -> str:
    combined = ""
    for _, text in chunks:
        overlap = 0
        for size in range(min(len(combined), len(text), 512), 0, -1):
            if combined.endswith(text[:size]):
                overlap = size
                break
        combined += text[overlap:] if overlap or not combined else "\n\n" + text
        if len(combined) > MAX_DOCUMENT_CHARS:
            raise ValueError("迁移后的文档超过 100 万字，请按来源拆分")
    return combined


def validate_api_url(value: str) -> str:
    try:
        url = urlsplit(value)
        _ = url.port
    except ValueError as exc:
        raise ValueError("后端地址无效") from exc
    if not url.hostname or url.username or url.password or url.query or url.fragment:
        raise ValueError("后端地址不得包含账户、查询参数或片段")
    if url.path not in {"", "/"}:
        raise ValueError("请输入后端根地址，例如 https://chat.example.com")
    local = url.hostname in {"127.0.0.1", "localhost", "::1"}
    if url.scheme != "https" and not (url.scheme == "http" and local):
        raise ValueError("远程后端必须使用 HTTPS；本机测试允许 HTTP")
    return value.rstrip("/")


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        # Never forward the user's bearer token to a redirected destination.
        return None


def upload_document(api_url: str, token: str, name: str, content: bytes) -> None:
    boundary = "zhiyu-" + secrets.token_hex(16)
    body = (
        (
            f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
            "Content-Type: text/markdown; charset=utf-8\r\n\r\n"
        ).encode()
        + content
        + f"\r\n--{boundary}--\r\n".encode()
    )
    request = Request(
        api_url + "/api/documents",
        data=body,
        method="POST",
        headers={
            "Authorization": "Bearer " + token,
            "Content-Type": "multipart/form-data; boundary=" + boundary,
        },
    )
    try:
        with build_opener(_NoRedirect()).open(request, timeout=60) as response:
            if response.status not in {200, 201}:
                raise RuntimeError("后端未确认文档导入成功")
    except HTTPError as exc:
        if exc.code == 401:
            raise RuntimeError("登录令牌无效或过期，请重新登录后导入") from exc
        raise RuntimeError(f"文档上传失败，HTTP {exc.code}；请检查后端状态") from exc
    except URLError as exc:
        raise RuntimeError("无法连接后端，请检查地址和网络") from exc


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="只读迁移旧 RAG metadata.json 到当前用户的知识库")
    parser.add_argument(
        "--source", required=True, type=Path, help="旧 metadata.json 文件或 rag_index 目录"
    )
    parser.add_argument("--api-url", default="http://127.0.0.1:8000", help="后端根地址")
    parser.add_argument(
        "--token-env", default="ZHIYU_TOKEN", help="保存当前用户登录令牌的环境变量名"
    )
    parser.add_argument("--dry-run", action="store_true", help="仅统计，不读取登录令牌，不发送数据")
    args = parser.parse_args(argv)
    try:
        documents, chunks = read_legacy_documents(args.source)
        print(f"检测到 {len(documents)} 个文档、{chunks} 个块。")
        if args.dry_run:
            print("预检查完成，原文件未修改，未发送数据。")
            return 0
        api_url = validate_api_url(args.api_url)
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", args.token_env):
            raise ValueError("登录令牌环境变量名无效")
        token = os.environ.get(args.token_env, "").strip()
        if not token and sys.stdin.isatty():
            token = getpass.getpass("请输入当前用户的登录令牌（隐藏输入）：").strip()
        if (
            not token
            or len(token) > 4096
            or any(ord(char) < 33 or ord(char) > 126 for char in token)
        ):
            raise ValueError("未设置有效登录令牌；请先登录并设置 ZHIYU_TOKEN")
        for index, (name, content) in enumerate(documents, 1):
            upload_document(api_url, token, name, content)
            print(f"已导入 {index}/{len(documents)} 个文档。")
        return 0
    except (OSError, ValueError, RuntimeError) as exc:
        print(f"导入终止：{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
