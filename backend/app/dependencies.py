from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from .auth import Session

bearer = HTTPBearer(auto_error=False)
SESSION_HEADERS = {"WWW-Authenticate": "Bearer", "X-ZhiYu-Error-Code": "session_expired"}


async def current_session(request: Request, credentials: HTTPAuthorizationCredentials | None = Depends(bearer)) -> Session:
    token = credentials.credentials if credentials else ""
    if not token or len(token) > 256:
        raise HTTPException(401, "请使用 API Key 重新连接", headers=SESSION_HEADERS)
    session = request.app.state.sessions.get(token)
    if session is None:
        raise HTTPException(401, "连接已过期或后端已重启，请重新连接", headers=SESSION_HEADERS)
    request.state.session_token = token
    request.app.state.memory_worker.activate(token, session)
    return session
