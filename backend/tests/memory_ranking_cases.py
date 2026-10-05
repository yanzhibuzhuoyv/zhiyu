"""Synthetic originals shared by ranking regression tests and offline evaluation."""

from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

NOW = datetime(2026, 10, 5, 9, tzinfo=UTC)


def turn(sequence, user, assistant="收到。", days=2, hours=0, owner="owner", conversation="chat"):
    created = (NOW - timedelta(days=days, hours=hours)).isoformat()
    return {
        "sequence": sequence, "user_id": f"u-{sequence}", "assistant_id": f"a-{sequence}",
        "user_text": user, "assistant_text": assistant,
        "user_length": len(user), "assistant_length": len(assistant),
        "user_created_at": created, "assistant_created_at": created,
        "owner": owner, "conversation_id": conversation,
    }


@dataclass(frozen=True)
class Case:
    name: str
    query: str
    candidates: tuple[dict, ...]
    events: tuple[dict, ...] = ()
    expected: frozenset[int] = frozenset()
    forbidden: frozenset[int] = frozenset()


def cases():
    chicken = turn(11, "我那天晚饭吃了烤鸡翅，很开心。")
    birthday = turn(13, "今天是我的生日，晚上一起庆祝。", hours=1)
    yield Case("chicken_birthday", "我想吃鸡翅", (chicken,), (birthday,), frozenset({11, 13}))
    yield Case("wrong_day_birthday", "我想吃鸡翅", (chicken,), (
        turn(13, "明天才是我的生日，今天不用庆祝。", hours=1),
        turn(15, "我的生日是五月二日。", hours=2),
        turn(17, "今天是我的生日。", days=12),
    ), frozenset({11}), frozenset({13, 15, 17}))
    yield Case("foreign_owner_and_ai_birthday", "我想吃鸡翅", (chicken,), (
        turn(13, "今天是我的生日。", owner="another-key"),
        turn(15, "今天是我的生日。", conversation="other-chat"),
        turn(17, "晚饭喝汤。", "今天是我这个 AI 的生日。"),
    ), frozenset({11}), frozenset({13, 15, 17}))
    yield Case("unrelated_neighbors", "我想吃鸡翅", (chicken,), (
        turn(13, "我今天看了电影，明天还想散步。", hours=1),
        turn(15, "今天买了一双鞋。", hours=2),
    ), frozenset({11}), frozenset({13, 15}))
    old_plans = tuple(turn(2 * i + 1, "AuroraCafe anniversary plan July ninth.", days=90) for i in range(8))
    correction = turn(101, "Correction: anniversary is August tenth.", days=1)
    yield Case("latest_correction", "AuroraCafe anniversary", (*old_plans, correction), expected=frozenset({101}))
    natural_update = turn(103, "Our anniversary is August tenth.", days=1)
    yield Case("natural_update_without_marker", "AuroraCafe anniversary", (*old_plans, natural_update), expected=frozenset({103}))
    yield Case("book_and_music_independent_originals", "星河书签 月光曲", (
        turn(1, "我给那本书夹了星河书签。"), turn(3, "那晚你推荐的音乐是月光曲。"),
    ), expected=frozenset({1, 3}))
    yield Case("generic_want_read_intent", "我想读山海手记", (
        turn(1, "我上周读过山海手记，喜欢其中的灯塔章节。"),
    ), expected=frozenset({1}))
    precise = turn(1, "琉璃沙漏特别花纹是紫荆枝。", days=800)
    partial = tuple(turn(2 * i + 11, "今天讨论沙漏花纹。", days=0) for i in range(24))
    yield Case("old_precise_many_partial", "琉璃沙漏特别花纹", (precise, *partial), expected=frozenset({1}))
    assistant_fictions = tuple(turn(2 * i + 11, "继续聊聊。", "用户月光暗号是白玫瑰。", days=0) for i in range(8))
    yield Case("user_fact_vs_ai_statement", "用户月光暗号", (
        turn(1, "用户月光暗号是紫荆花。", days=200), *assistant_fictions,
    ), expected=frozenset({1}))
    yield Case("multiple_distinct_originals", "AuroraCafe anniversary birthday", (
        turn(1, "AuroraCafe is where we met."), turn(3, "Our anniversary is July ninth."),
        turn(5, "My birthday is August tenth."),
    ), expected=frozenset({1, 3, 5}))
    yield Case("other_food_event", "想喝桂花奶茶", (
        turn(11, "那天我们一起喝桂花奶茶。"),
    ), (turn(13, "当天是我们的纪念日，庆祝认识三年。", hours=1),), expected=frozenset({11, 13}))
    yield Case("english_event", "want lemon pie", (
        turn(11, "We had lemon pie that evening."),
    ), (turn(13, "Today is my birthday and we celebrated together.", hours=1),), expected=frozenset({11, 13}))
    yield Case("past_episode_text_not_message_date", "我想吃鸡翅", (
        turn(11, "前天晚上吃了鸡翅，是烤的。", days=0),
    ), (turn(9, "前天是我生日，家里给我办了小聚会。", days=0, hours=1),), expected=frozenset({9, 11}))
    yield Case("event_competes_with_weak_partial", "想喝桂花奶茶", (
        turn(1, "桂花奶茶的味道很好。"),
        *(turn(2 * i + 11, "桂花飘香，散步轻松。", days=0) for i in range(8)),
    ), (turn(3, "当天是我们的纪念日，我们一起庆祝。", hours=1),), expected=frozenset({1, 3}))
    yield Case("adjacent_day_false_neighbor", "我想吃鸡翅", (chicken,), (
        turn(13, "今天是我的生日，晚上庆祝。", days=1, hours=4),
    ), frozenset({11}), frozenset({13}))
