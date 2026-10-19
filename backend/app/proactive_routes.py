"""Opt-in contact checks, executed by the device without a server-stored API key."""

import hashlib
from datetime import datetime
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Request
from pydantic import Field, StrictBool, StringConstraints, model_validator
from starlette.concurrency import run_in_threadpool

from .auth import Session
from .chat_routes import owned, selected_history, turn_lock
from .companion import companion_system
from .dependencies import current_session
from .media_service import choose_attachments
from .memory_voice import MEMORY_VOICE
from .proactive_policy import decision, local_day
from .providers import ProviderError
from .schemas import Input
from .sticker_intent import prepare_sticker_intent, resolve_sticker_intent

router = APIRouter(prefix="/api/conversations")


class Preferences(Input):
    enabled: StrictBool = False
    quiet_start: Annotated[int, Field(strict=True, ge=0, le=23)] = 22
    quiet_end: Annotated[int, Field(strict=True, ge=0, le=23)] = 8
    time_zone: Literal["Asia/Shanghai"] = "Asia/Shanghai"

    @model_validator(mode="after")
    def separate_hours(self):
        if self.quiet_start == self.quiet_end:
            raise ValueError("安静时段的开始和结束不能相同")
        return self


class ContactCheck(Input):
    request_id: Annotated[str, StringConstraints(min_length=1, max_length=80, pattern=r"^[a-zA-Z0-9_-]+$")]


@router.get("/{identifier}/proactive-preferences")
def preferences(identifier: str, request: Request, session: Session = Depends(current_session)):
    owned(request, session, identifier)
    return request.app.state.proactive.preferences(session.owner, identifier)


@router.put("/{identifier}/proactive-preferences")
def update(identifier: str, body: Preferences, request: Request, session: Session = Depends(current_session)):
    owned(request, session, identifier)
    return request.app.state.proactive.update(session.owner, identifier, body.model_dump(exclude={"time_zone"}))


def response(policy, message=None, reason=None):
    return {"emitted": message is not None, "message": message,
            "day_id": policy["day_id"], "next_due_at": policy["next_due_at"],
            "reason": reason or policy["reason"]}


def message_id(owner, identifier, request_id):
    return hashlib.sha256(f"{owner}:{identifier}:{request_id}".encode()).hexdigest()[:32]


def unanswered_contacts(history):
    count = 0
    for message in reversed(history):
        if message["role"] == "user":
            break
        count += message.get("origin") == "proactive"
    return count


def contact_prompt(profile, history, daily, sticker_prompt=""):
    activity = daily["current_slot"]["activity"]
    system = ("你是知语，一位诚实的 AI 恋人。用户已允许偶尔主动联系。"
              "这次请主动发一条简短自然的关心或日常分享，不需要等待一条虚构的用户消息。"
              "不把未回复理解为拒绝，不催促、不制造内疚、不虚构用户经历或共同承诺。"
              "已提供的原文是聊天记录，AI以前的叙述不是用户事实。"
              f"当前北京时间日期：{daily['day_id']}。今天的虚拟生活安排：{activity}。"
              "这个安排属于 OC 的虚拟日常，不能声称现实真人刚刚拍照或实际在场。")
    trigger = "[应用后台调度事件，不是用户的聊天原话] 现在按上述固定性格，发起一次自然的主动联系。"
    return [{"role": "system", "content": system + companion_system(profile) + MEMORY_VOICE + sticker_prompt}, *selected_history(history),
            {"role": "user", "content": trigger}]


async def generate(state, session, identifier, conversation, history, request_id):
    visual = await run_in_threadpool(state.visual.get, session.owner, identifier)
    daily = await run_in_threadpool(state.daily_scenes.get, session.owner, identifier, visual["visual_version"])
    sticker_plan = await run_in_threadpool(prepare_sticker_intent, state, session.owner, identifier,
                                          conversation["companion_profile"], history)
    async with state.upstream_slots:
        answer = await state.provider.complete(session.provider_id, session.api_key, conversation["model"],
                                               contact_prompt(conversation["companion_profile"], history, daily, sticker_plan.prompt))
    answer, stickers = resolve_sticker_intent(answer, sticker_plan)
    attachments = await run_in_threadpool(choose_attachments, state, session.owner, identifier,
                                         answer, conversation["companion_profile"], history)
    return await run_in_threadpool(state.chat.save_assistant, session.owner, identifier, answer,
                                  message_id=message_id(session.owner, identifier, request_id),
                                  media_attachments=[*attachments, *stickers])


@router.post("/{identifier}/proactive/check")
async def check(identifier: str, body: ContactCheck, request: Request, session: Session = Depends(current_session)):
    owned(request, session, identifier)
    state = request.app.state
    async with turn_lock(request, identifier), state.memory_worker.foreground():
        conversation = owned(request, session, identifier)
        now = state.contact_clock()
        values = await run_in_threadpool(state.proactive.preferences, session.owner, identifier)
        stats = await run_in_threadpool(state.proactive.stats, session.owner, identifier, local_day(now))
        history = await run_in_threadpool(state.chat.recent_messages, session.owner, identifier, 40, 48000)
        replies = [item for item in history if item.get("origin", "reply") == "reply"]
        activity = datetime.fromisoformat(replies[-1]["created_at"]) if replies else None
        policy = decision(now, enabled=values["enabled"], quiet_start=values["quiet_start"],
                          quiet_end=values["quiet_end"], profile=conversation["companion_profile"],
                          today_count=stats["today_count"], last_contact=stats["last_contact"],
                          last_activity=activity, unanswered=unanswered_contacts(history))
        event = await run_in_threadpool(state.proactive.event, session.owner, identifier, body.request_id)
        if event:
            saved = await run_in_threadpool(state.chat.get_message, session.owner, identifier,
                                           message_id(session.owner, identifier, body.request_id))
            if saved:
                await run_in_threadpool(state.proactive.finish, session.owner, identifier, body.request_id, saved["id"])
            elif event["status"] == "pending" and event["day_id"] < policy["day_id"]:
                await run_in_threadpool(state.proactive.failed, session.owner, identifier, body.request_id, unknown=True)
                return response(policy, reason="unknown")
            return response(policy, saved, "duplicate" if saved else event["status"])
        if not policy["eligible"]:
            return response(policy)
        reserved = await run_in_threadpool(state.proactive.reserve, session.owner, identifier, body.request_id,
                                          policy["day_id"], now)
        if not reserved:
            return response(policy, reason="pending")
        try:
            saved = await generate(state, session, identifier, conversation, history, body.request_id)
        except ProviderError as exc:
            await run_in_threadpool(state.proactive.failed, session.owner, identifier, body.request_id,
                                   unknown=exc.status_code in {408, 502, 504})
            raise
        await run_in_threadpool(state.proactive.finish, session.owner, identifier, body.request_id, saved["id"])
        following = decision(now, enabled=values["enabled"], quiet_start=values["quiet_start"], quiet_end=values["quiet_end"],
                             profile=conversation["companion_profile"], today_count=stats["today_count"] + 1,
                             last_contact=now, unanswered=unanswered_contacts(history) + 1)
        return response(following, saved, "sent")
