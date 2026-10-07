from fastapi import APIRouter, Depends, HTTPException, Request, Response
from fastapi.security import HTTPAuthorizationCredentials

from .auth import Session
from .dependencies import bearer, current_session
from .providers import PROVIDERS, list_providers
from .schemas import Login

router = APIRouter(prefix="/api")


@router.get("/providers")
def providers(request: Request):
    return list_providers(include_demo=request.app.state.settings.allow_demo)


@router.post("/auth/login")
async def login(body: Login, request: Request):
    state = request.app.state
    address = request.client.host if request.client else "unknown"
    if not state.login_limiter.allow(address):
        raise HTTPException(429, "连接尝试过于频繁，请一分钟后重试", headers={"Retry-After": "60"})
    if body.provider_id == "demo":
        if not state.settings.allow_demo:
            raise HTTPException(400, "演示模式未开启")
        model = body.model or "demo"
    else:
        provider = PROVIDERS.get(body.provider_id)
        if provider is None:
            raise HTTPException(400, "未知 AI 服务商")
        model = body.model or provider.default_model
        if not model:
            raise HTTPException(400, "请填写已开通的模型名称或接入点 ID")
    key = body.api_key.get_secret_value()
    async with state.memory_worker.foreground(), state.upstream_slots:
        await state.provider.validate(body.provider_id, key, model)
    try:
        token, session = state.sessions.issue(body.provider_id, key, model)
    except ValueError as error:
        raise HTTPException(503, str(error)) from error
    state.memory_worker.activate(token, session)
    return {"token": token, "provider_id": session.provider_id, "model": session.model,
            "expires_at": session.expires_at.isoformat()}


@router.get("/auth/me")
def me(session: Session = Depends(current_session)):
    return {"provider_id": session.provider_id, "model": session.model,
            "expires_at": session.expires_at.isoformat()}


@router.delete("/auth/session", status_code=204)
async def logout(request: Request, session: Session = Depends(current_session),
           credentials: HTTPAuthorizationCredentials = Depends(bearer)):
    request.app.state.sessions.revoke(credentials.credentials)
    await request.app.state.memory_worker.revoke(credentials.credentials)
    return Response(status_code=204)
