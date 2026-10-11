import asyncio
import json
from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response
from starlette.concurrency import run_in_threadpool

from .auth import Session
from .companion import CompanionConflict, companion_system
from .conversation_memory import conversation_context_result
from .database import HistoryCursorError
from .dependencies import current_session
from .media_service import choose_attachments, media_prompt
from .memory_contract import RetrievalState, resolve_state
from .prompt_rules import behavior_rules
from .schemas import EditConversation, NewConversation, NewMessage
from .sticker_intent import prepare_sticker_intent, resolve_sticker_intent

router = APIRouter(prefix="/api/conversations")


def owned(request: Request, session: Session, identifier: str):
    conversation = request.app.state.chat.get_conversation(session.owner, identifier)
    if conversation is None:
        raise HTTPException(404, "会话不存在")
    return conversation


def turn_lock(request: Request, identifier: str):
    return request.app.state.turn_locks.setdefault(identifier, asyncio.Lock())


@router.get("")
def conversations(request: Request, session: Session = Depends(current_session)):
    return request.app.state.chat.list_conversations(session.owner)


@router.post("", status_code=201)
def create(body: NewConversation, request: Request, session: Session = Depends(current_session)):
    profile = body.companion_profile.model_dump() if body.companion_profile else None
    return request.app.state.chat.create_conversation(session.owner, body.title, body.model or session.model, body.use_rag, profile)


@router.patch("/{identifier}")
def update(identifier: str, body: EditConversation, request: Request, session: Session = Depends(current_session)):
    owned(request, session, identifier)
    try:
        result = request.app.state.chat.update_conversation(session.owner, identifier, body.model_dump(exclude_unset=True))
    except CompanionConflict as exc:
        raise HTTPException(409, str(exc)) from exc
    if result is None:
        raise HTTPException(404, "会话不存在")
    return result


@router.delete("/{identifier}", status_code=204)
async def delete(identifier: str, request: Request, session: Session = Depends(current_session)):
    owned(request, session, identifier)
    async with turn_lock(request, identifier):
        if not await run_in_threadpool(request.app.state.chat.delete_conversation, session.owner, identifier):
            raise HTTPException(404, "会话不存在")
        request.app.state.memory_attention.forget(session.owner, identifier)
    return Response(status_code=204)


@router.get("/{identifier}/messages")
def messages(identifier: str, request: Request, session: Session = Depends(current_session)):
    owned(request, session, identifier)
    return request.app.state.chat.list_messages(session.owner, identifier)


@router.get("/{identifier}/history")
def history(identifier: str, request: Request,
            limit: Annotated[int, Query(ge=1, le=100)] = 100,
            before: Annotated[str | None, Query(min_length=1, max_length=128)] = None,
            session: Session = Depends(current_session)):
    owned(request, session, identifier)
    try:
        return request.app.state.chat.history_page(session.owner, identifier, limit, before)
    except HistoryCursorError as exc:
        raise HTTPException(404, "会话历史游标不存在") from exc


def knowledge_context(sources):
    if not sources:
        return ""
    references = [{"index": index, "name": source["name"], "text": source["text"]}
                  for index, source in enumerate(sources, 1)]
    context = json.dumps(references, ensure_ascii=False).replace("<", "\\u003c")
    return f"\n<knowledge>\n{context}\n</knowledge>"


def earlier_context(memory_refs):
    if not memory_refs:
        return ""
    memories = json.dumps(memory_refs, ensure_ascii=False).replace("<", "\\u003c")
    return f"\n<conversation_memory>\n{memories}\n</conversation_memory>"


def selected_history(history):
    # 保留完整成对轮次，避免无限增长的对话超出模型上下文。
    selected, budget = [], 48000
    end = len(history)
    while end:
        start = end - 1
        if history[start]["role"] == "assistant" and start > 0 and history[start - 1]["role"] == "user":
            start -= 1
        group = history[start:end]
        length = sum(len(message["content"]) for message in group)
        if length > budget or len(selected) + len(group) > 40:
            break
        selected = [{"role": message["role"], "content": message["content"]} for message in group] + selected
        budget -= length
        end = start
    return selected


def retrieval_status(history, memory_refs, state: RetrievalState):
    values = {"history_messages": len(history), "earlier_source_count": len(memory_refs or []),
              "state": state.value}
    return "\n<retrieval_status>\n" + json.dumps(values) + "\n</retrieval_status>"


def prompt(history: list, content: str, sources: list, memory_refs: list | None = None,
           companion_profile: dict | None = None, memory_complete: bool | None = None, media_attachments=None,
           sticker_prompt="", *, retrieval_state: RetrievalState | str | None = None,
           disabled_rule_ids=frozenset()):
    state = resolve_state(retrieval_state, memory_complete)
    scopes = {"chat"}
    if sources:
        scopes.add("knowledge")
    if memory_refs:
        scopes.add("legacy_memory")
    system = behavior_rules(scopes, disabled_ids=disabled_rule_ids)
    system += companion_system(companion_profile)
    system += f"\n当前服务器时间（UTC）：{datetime.now(UTC).isoformat()}。用户当地日期可能不同，必要时先确认。"
    system += knowledge_context(sources) + earlier_context(memory_refs)
    selected = selected_history(history)
    system += retrieval_status(selected, memory_refs, state)
    system += media_prompt(media_attachments) + sticker_prompt
    return [{"role": "system", "content": system}, *selected, {"role": "user", "content": content}]


@router.post("/{identifier}/messages")
async def send(identifier: str, body: NewMessage, request: Request, session: Session = Depends(current_session)):
    owned(request, session, identifier)
    state = request.app.state
    async with turn_lock(request, identifier), state.memory_worker.foreground():
        conversation = owned(request, session, identifier)
        sources = await run_in_threadpool(state.rag.search, session.owner, body.content, 5) if conversation["use_rag"] else []
        recent = await run_in_threadpool(state.chat.recent_messages, session.owner, identifier, 40, 48000)
        context = await run_in_threadpool(
            conversation_context_result, state.chat, session.owner, identifier, body.content,
            recent_messages=recent,
        )
        references = state.memory_attention.decorate(session.owner, identifier, context.references)
        attachments = await run_in_threadpool(choose_attachments, state, session.owner, identifier,
                                             body.content, conversation["companion_profile"], recent)
        sticker_plan = await run_in_threadpool(prepare_sticker_intent, state, session.owner, identifier,
                                              conversation["companion_profile"], recent, "表情包" in body.content)
        async with state.upstream_slots:
            answer = await state.provider.complete(session.provider_id, session.api_key, conversation["model"], prompt(recent, body.content, sources, references, conversation["companion_profile"], media_attachments=attachments, sticker_prompt=sticker_plan.prompt, retrieval_state=context.status))
        answer, stickers = resolve_sticker_intent(answer, sticker_plan)
        attachments = [*attachments, *stickers]
        deferred = {"provider_id": session.provider_id, "model": conversation["model"]} if state.chat.auto_annotations and state.chat.annotation_mode == "semantic" else None
        saved = await run_in_threadpool(state.chat.save_turn, session.owner, identifier, body.content, answer, sources,
                                       deferred_recognition=deferred, media_attachments=attachments)
        state.memory_attention.remember(session.owner, identifier, references)
        # Effective request authentication has already activated the in-memory owner.
        # Re-activate after COMMIT so the durable task exists before any cloud call.
        state.memory_worker.activate(request.state.session_token, session)
        return {**saved, "memory_refs": references,
                "memory_recognition": {"mode": state.chat.annotation_mode,
                                       "status": "pending" if deferred else "literal" if state.chat.auto_annotations else "disabled"},
                "memory_search": {"status": context.status.value, "complete": context.complete, "next_cursor": context.next_cursor}}
