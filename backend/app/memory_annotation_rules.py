"""Small, opt-in literal source grammar; no semantic or calendar inference."""

import re
from datetime import date

RULE_VERSION = "literal-zh-v1"
_ITEM = r"[\u4e00-\u9fffA-Za-z0-9·]{1,24}"
_COMPOUND = re.compile(
    "不|没有|如果|假设|朋友|别人|但是|而且|因为|其实|你|他|她|说过|"
    "但|也|却|再|然后|或者|虽然|并且|已经|以前|过去|曾经|这是|那是|谎|骗|"
    "讨厌|喜欢|承诺|生日|是否|吗|呢|not|unless|but|maybe", re.I)
_BIRTHDAY = re.compile(r"我的(?P<calendar>公历)?生日是(?P<date>\d{4}-\d{2}-\d{2}|\d{2}-\d{2}|\d{1,2}月\d{1,2}日)")
_ACTION = r"(?P<action>吃|喝|看|读|听|去|买|玩|学|做)"
_WISH = re.compile(r"我(?:又)?想" + _ACTION + r"(?P<item>" + _ITEM + r")")
_PREFERENCE = re.compile(r"我(?P<denial>不|已经不)?(?:一直)?喜欢(?P<item>" + _ITEM + r")")
_PROMISE = re.compile(r"我承诺在(?P<date>\d{4}-\d{2}-\d{2})陪你" + _ACTION + r"(?P<item>" + _ITEM + r")")


def _valid_date(text):
    try:
        if "月" in text:
            month, day = text[:-1].split("月")
            date(2000, int(month), int(day))
        elif len(text) == 5:
            date.fromisoformat("2000-" + text)
        else:
            date.fromisoformat(text)
        return True
    except ValueError:
        return False


def classify_source(content: str, role: str) -> dict | None:
    """Return pointers and literal metadata, never a rewritten memory sentence.

    Unqualified birthdays retain an unknown calendar basis. Promise dates never
    cause automatic fulfillment or overdue status without verified time context.
    Repeated text has no independent episode provenance from this grammar.
    """
    if not isinstance(content, str) or not isinstance(role, str) or role not in {"user", "assistant"}:
        raise ValueError("原文识别输入或作者无效")
    sentence = content.strip().removesuffix("。").removesuffix(".")
    keep, correction = sentence.startswith("请记住："), sentence.startswith("更正：")
    if keep or correction:
        sentence = sentence[4 if keep else 3:]
    result = {"span_start": 0, "span_end": len(content), "subject": role,
              "polarity": "affirm", "explicit_keep": keep,
              "correction": correction, "promise_state": None,
              "date_basis": None, "event_date": None, "classification_version": RULE_VERSION}
    if match := _PROMISE.fullmatch(sentence):
        if not _valid_date(match["date"]) or _COMPOUND.search(match["item"]):
            return None
        return {**result, "kind": "promise", "topic_key": "promise:" + match["action"] + ":" + match["item"],
                "promise_state": "pending", "event_date": match["date"]}
    if role != "user":
        return None
    if match := _BIRTHDAY.fullmatch(sentence):
        if not _valid_date(match["date"]):
            return None
        return {**result, "kind": "profile", "topic_key": "user:birthday",
                "date_basis": "gregorian" if match["calendar"] else None}
    if match := _WISH.fullmatch(sentence):
        if _COMPOUND.search(match["item"]):
            return None
        return {**result, "kind": "wish", "topic_key": match["action"] + ":" + match["item"]}
    if match := _PREFERENCE.fullmatch(sentence):
        if _COMPOUND.search(match["item"]):
            return None
        return {**result, "kind": "preference", "topic_key": "like:" + match["item"],
                "polarity": "deny" if match["denial"] else "affirm"}
    return None
