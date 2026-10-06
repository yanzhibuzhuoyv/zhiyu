from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from starlette.concurrency import run_in_threadpool

from .auth import Session
from .chat_routes import turn_lock
from .conversation_memory import memory_terms
from .dependencies import current_session
from .memory_management import MemoryConflict, MemoryManagement, MemoryMissing
from .memory_schemas import ColdSearch, MemoryEdit

router = APIRouter(prefix="/api/conversations/{identifier}/memory")


async def invoke(request, method, *args):
    try:
        return await run_in_threadpool(getattr(MemoryManagement(request.app.state.chat), method), *args)
    except MemoryMissing as error:
        raise HTTPException(404, str(error)) from error
    except MemoryConflict as error:
        raise HTTPException(409, str(error)) from error


@router.get("")
async def page(identifier: str, request: Request,
               limit: Annotated[int, Query(ge=1, le=100)] = 50,
               before: Annotated[str | None, Query(min_length=1, max_length=128)] = None,
               session: Session = Depends(current_session)):
    return await invoke(request, "page", session.owner, identifier, limit, before)


@router.get("/messages/{message_id}")
async def metadata(identifier: str, message_id: str, request: Request,
                   session: Session = Depends(current_session)):
    return await invoke(request, "get", session.owner, identifier, message_id)


@router.put("/messages/{message_id}")
async def update(identifier: str, message_id: str, body: MemoryEdit, request: Request,
                 session: Session = Depends(current_session)):
    async with turn_lock(request, identifier):
        return await invoke(request, "update", session.owner, identifier, message_id, body)


@router.post("/search")
async def search(identifier: str, body: ColdSearch, request: Request,
                 session: Session = Depends(current_session)):
    terms = memory_terms(body.query)
    if not terms:
        raise HTTPException(422, "请提供具体的记忆内容")
    return await invoke(request, "search", session.owner, identifier, terms, body.cursor)
