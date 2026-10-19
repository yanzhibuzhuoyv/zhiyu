"""Bound actual ASGI bytes and authenticate protected endpoints before body parsing."""

from starlette.datastructures import Headers
from starlette.responses import JSONResponse

from .auth import Sessions

MAX_BODY_BYTES = 6 * 1024 * 1024
PUBLIC_API_PATHS = {"/api/providers", "/api/auth/login"}


class RequestGuard:
    def __init__(self, app, sessions: Sessions):
        self.app, self.sessions = app, sessions

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        async def secure_send(message):
            if message["type"] == "http.response.start":
                headers = list(message.get("headers", []))
                headers.extend([(b"cache-control", b"no-store"),
                                (b"x-content-type-options", b"nosniff")])
                message = {**message, "headers": headers}
            await send(message)

        async def reject(status, detail):
            auth_headers = {"X-ZhiYu-Error-Code": "session_expired", "WWW-Authenticate": "Bearer"} if status == 401 else None
            await JSONResponse(status_code=status, content={"detail": detail}, headers=auth_headers)(scope, receive, secure_send)

        headers = Headers(scope=scope)
        length = headers.get("content-length")
        try:
            if length is not None and (int(length) < 0 or int(length) > MAX_BODY_BYTES):
                await reject(413, "请求体过大")
                return
        except ValueError:
            await reject(400, "请求长度无效")
            return
        path = scope["path"].rstrip("/")
        if path.startswith("/api/") and path not in PUBLIC_API_PATHS:
            parts = headers.get("authorization", "").split()
            token = parts[1] if len(parts) == 2 and parts[0].lower() == "bearer" else ""
            if not token or len(token) > 256 or self.sessions.get(token) is None:
                await reject(401, "连接已过期或后端已重启，请使用 API Key 重新连接")
                return

        body = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            chunk = message.get("body", b"")
            if len(body) + len(chunk) > MAX_BODY_BYTES:
                await reject(413, "请求体过大")
                return
            body.extend(chunk)
            if not message.get("more_body", False):
                break
        delivered = False

        async def replay():
            nonlocal delivered
            if delivered:
                return await receive()
            delivered = True
            return {"type": "http.request", "body": bytes(body), "more_body": False}

        await self.app(scope, replay, secure_send)
