"""Integrates existing cached media only; selection never starts image generation."""

from .media_selection import PHOTO_WORDS, select_media


def choose_attachments(state, owner, conversation_id, text, companion_profile, history=None):
    if not any(word in text for word in PHOTO_WORDS):
        return []
    visual = state.visual.get(owner, conversation_id)
    daily = state.daily_scenes.get(owner, conversation_id, visual["visual_version"])
    history = history if history is not None else state.chat.recent_messages(owner, conversation_id, 40, 48000)
    recent = [attachment for message in history[-20:] for attachment in message.get("media_attachments", [])]
    hour = int(daily["current_slot"]["time"].split(":")[0])
    time_slot = "morning" if 6 <= hour < 12 else "afternoon" if 12 <= hour < 18 else "evening" if 18 <= hour < 22 else "night"
    output_number = state.chat.assistant_message_count(owner, conversation_id) + 1
    return select_media(state.media, owner, conversation_id, text, companion_profile, visual,
                        day_id=daily["day_id"], recent=recent, turn_number=output_number,
                        time_slot=time_slot, scene_tags=daily["current_slot"]["tags"])


def media_prompt(attachments):
    if not attachments:
        return ""
    return ("\n本次回复会附上经过身份筛选的缓存素材。图片是用户提供或生成的 OC 素材与虚拟日常，"
            "不能声称是真人现场拍摄或刚刚拍下的照片。自然回应即可，不虚构图片中未提供的细节。")
