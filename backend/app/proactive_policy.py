"""Deterministic contact timing; no extra model call to classify every turn."""

from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

ZONE = ZoneInfo("Asia/Shanghai")
LIMITS = {"steady": (1, 12), "gentle": (2, 6), "warm_v1": (3, 4),
          "warm_v2": (3, 4), "playful": (4, 3), "anxious": (12, 3), "distant": (1, 24)}
UNANSWERED_LIMITS = {"steady": 1, "gentle": 3, "warm_v1": 4, "warm_v2": 4,
                     "playful": 4, "distant": 1}


def contact_limits(profile):
    return LIMITS.get((profile or {}).get("style"), LIMITS["gentle"])


def local_day(now: datetime):
    if now.tzinfo is None:
        raise ValueError("联系时间必须包含时区")
    return now.astimezone(ZONE).date().isoformat()


def decision(now: datetime, *, enabled=False, quiet_start=22, quiet_end=8,
             profile=None, today_count=0, unanswered=0, last_activity=None, last_contact=None):
    if type(enabled) is not bool or any(type(v) is not int or not 0 <= v <= 23
                                        for v in (quiet_start, quiet_end)) or quiet_start == quiet_end:
        raise ValueError("主动联系设置无效")
    if any(type(v) is not int or v < 0 for v in (today_count, unanswered)):
        raise ValueError("联系计数无效")
    day = local_day(now)
    local = now.astimezone(ZONE)
    cap, hours = contact_limits(profile)
    style = (profile or {}).get("style", "gentle")
    if style == "anxious":
        hours = max(0.5, hours / (2 ** min(unanswered, 3)))
    quiet = (quiet_start <= local.hour < quiet_end if quiet_start < quiet_end
             else local.hour >= quiet_start or local.hour < quiet_end)
    end = local.replace(hour=quiet_end, minute=0, second=0, microsecond=0)
    if end <= local:
        end += timedelta(days=1)
    reason, next_due = "ready", now
    if not enabled:
        reason, next_due = "disabled", now + timedelta(days=1)
    elif quiet:
        reason, next_due = "quiet_hours", end
    elif style in UNANSWERED_LIMITS and unanswered >= UNANSWERED_LIMITS[style]:
        reason, next_due = "awaiting_reply", now + timedelta(hours=hours)
    elif style == "distant" and last_activity is None:
        reason, next_due = "reserved_style", now + timedelta(hours=hours)
    elif last_activity and now - last_activity < timedelta(minutes=45):
        reason, next_due = "recent_chat", last_activity + timedelta(minutes=45)
    elif today_count >= cap:
        reason, next_due = "daily_limit", (local + timedelta(days=1)).replace(
            hour=quiet_end, minute=0, second=0, microsecond=0)
    elif last_contact and now - last_contact < timedelta(hours=hours):
        reason, next_due = "spacing", last_contact + timedelta(hours=hours)
    return {"eligible": reason == "ready", "reason": reason, "day_id": day,
            "next_due_at": next_due.isoformat()}
