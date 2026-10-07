import asyncio
import inspect
from contextlib import asynccontextmanager
from weakref import WeakValueDictionary

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .auth import LoginLimiter, Sessions
from .auth_routes import router as auth_router
from .chat_routes import router as chat_router
from .config import Settings
from .database import ChatDatabase
from .knowledge_routes import router as knowledge_router
from .memory_attention import MemoryAttention
from .memory_routes import router as memory_router
from .memory_worker import MemoryWorker
from .providers import ProviderClient, ProviderError
from .rag import RagService, RagStorageError
from .rag_semantic import RagSemanticError
from .request_guard import RequestGuard
from .source_codec import ChatStorageError
from .trial_routes import router as trial_router


def create_app(settings: Settings | None = None, provider_client=None) -> FastAPI:
    settings = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(application):
        try:
            yield
        finally:
            try:
                await application.state.memory_worker.aclose()
            finally:
                close = getattr(application.state.provider, "aclose", None)
                if close:
                    result = close()
                    if inspect.isawaitable(result):
                        await result

    application = FastAPI(title="知语 API", version="0.1.1", lifespan=lifespan)
    state = application.state
    state.settings = settings
    state.sessions = Sessions(settings.data_dir, settings.session_hours)
    state.login_limiter = LoginLimiter()
    state.chat = ChatDatabase(settings.data_dir, auto_annotations=settings.auto_memory_annotations,
                              annotation_mode=settings.memory_annotation_mode)
    state.rag = RagService(settings.data_dir, embedding_model=settings.embedding_model)
    state.provider = provider_client or ProviderClient(allow_demo=settings.allow_demo)
    state.upstream_slots = asyncio.Semaphore(8)
    state.parse_slots = asyncio.Semaphore(2)
    state.turn_locks = WeakValueDictionary()
    state.memory_worker = MemoryWorker(state.chat, state.sessions, state.provider)
    state.memory_attention = MemoryAttention()

    @application.exception_handler(ProviderError)
    async def provider_error(request: Request, error: ProviderError):
        return JSONResponse(status_code=error.status_code, content={"detail": error.message})

    @application.exception_handler(RagStorageError)
    async def storage_error(request: Request, error: RagStorageError):
        return JSONResponse(status_code=503, content={"detail": "知识库暂时无法读写，请检查后端数据目录后重试"})

    @application.exception_handler(RagSemanticError)
    async def semantic_error(request: Request, error: RagSemanticError):
        return JSONResponse(status_code=503, content={"detail": "本地语义模型暂时不可用，请检查模型配置后重试"})

    @application.exception_handler(ChatStorageError)
    async def chat_storage_error(request: Request, error: ChatStorageError):
        return JSONResponse(status_code=503, content={"detail": "聊天资料暂时无法读写，请检查后端数据库后重试"})

    @application.exception_handler(RequestValidationError)
    async def validation_error(request: Request, error: RequestValidationError):
        # Pydantic 默认包含原始 input；这里避免把 API Key 回显给 UI 或日志。
        fields = ", ".join(".".join(str(part) for part in item["loc"]) for item in error.errors())
        return JSONResponse(status_code=422, content={"detail": f"输入无效，请检查：{fields}"})

    application.add_middleware(RequestGuard, sessions=state.sessions)

    @application.get("/health")
    def health():
        return {"status": "ok", "version": "0.1.1", "rag_mode": state.rag.mode}

    application.include_router(auth_router)
    application.include_router(chat_router)
    application.include_router(memory_router)
    application.include_router(knowledge_router)
    application.include_router(trial_router)
    return application


app = create_app()
