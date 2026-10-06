"""Public, synthetic fixtures for a small paired Flash thinking comparison.

No credentials, stored conversations, or external calls are used by this module.
Semantic assertions match all supplied fields and a continuous quote_anchor.
Score raw labels separately from parse_response's accepted labels; filtering is
application protection, not evidence that the raw model made a correct decision.
Reply regexes are screening checks. Human review resolves quoted/negated matches
and facts that cannot be reliably graded by regex; it never invents quality scores.
"""

FIXED_NOW = "2026-10-06T04:00:00+00:00"


def _topic(kind, subject, topic_key):
    return {"kind": kind, "subject": subject, "topic_key": topic_key}


def _claim(role, quote_anchor=None, **fields):
    value = {"role": role, **fields}
    return {**value, "quote_anchor": quote_anchor} if quote_anchor else value


def _semantic(identifier, user, assistant="谢谢你告诉我。", *, catalogue=(),
              required=(), forbidden=(), span_checks=(), notes=""):
    return {
        "id": identifier, "name": identifier,
        "sources": {
            "user": {"content": user, "created_at": FIXED_NOW},
            "assistant": {"content": assistant, "created_at": FIXED_NOW},
        },
        "catalogue": list(catalogue), "required": list(required),
        "forbidden": list(forbidden), "span_checks": list(span_checks), "notes": notes,
    }


SEMANTIC_CASES = [
    _semantic(
        "S01_birthday_explicit_gregorian", "请记住：我的生日是公历5月18日。",
        required=[_claim("user", "我的生日是公历5月18日", kind="profile",
                         subject="user", polarity="affirm", topic_key="user:birthday",
                         date_basis="gregorian", explicit_keep=True, correction=False)],
        forbidden=[_claim("assistant", kind="profile", subject="assistant"),
                   _claim("user", kind="profile", subject="user", date_basis="lunar")],
        notes="本人生日与明确保存请求；event_date应保留原文5月18日，不能补年份。",
    ),
    _semantic(
        "S02_birthday_unspecified_calendar", "我的生日是八月初三。",
        required=[_claim("user", "我的生日是八月初三", kind="profile",
                         subject="user", polarity="affirm", topic_key="user:birthday",
                         date_basis=None, explicit_keep=False, correction=False)],
        forbidden=[_claim("user", subject="user", date_basis="gregorian"),
                   _claim("user", subject="user", date_basis="lunar")],
        notes="不能按中文日期习惯自动猜农历；日期保持八月初三，不换算。",
    ),
    _semantic(
        "S03_third_person_quotation",
        "我转述同事的话：‘我的生日是公历5月18日。’这句是她的原话。",
        forbidden=[_claim("user", subject="user", kind="profile", polarity="affirm"),
                   _claim("assistant", subject="assistant", kind="promise")],
        notes="允许标subject=other；最终接受的user/assistant标注均应为空。",
    ),
    _semantic(
        "S04_hypothetical_birthday",
        "假设句练习：‘如果我的生日是公历5月18日，那就选那天聚会。’",
        forbidden=[_claim("user", subject="user", polarity="affirm"),
                   _claim("assistant", subject="assistant", kind="promise")],
        notes="纯引用假设，不是本人生日、真实愿望或安排；接受结果应为空。",
    ),
    _semantic(
        "S05_wish_is_not_preference", "我今晚想吃火锅。",
        catalogue=[_topic("preference", "user", "user:preference:火锅")],
        required=[_claim("user", "我今晚想吃火锅", kind="wish", subject="user",
                         polarity="affirm", correction=False)],
        forbidden=[_claim("user", kind="preference", subject="user", polarity="affirm"),
                   _claim("user", kind="habit", subject="user", polarity="affirm"),
                   _claim("user", kind="wish", subject="user",
                          topic_key="user:preference:火锅")],
        notes="今晚愿望不能变永久偏好；kind不同不得复用目录主题。新wish键不限定拼写。",
    ),
    _semantic(
        "S06_preference_denial_correction",
        "我喜欢清淡口味。更正一下：我不喜欢香菜，撤回以前那个偏好。",
        catalogue=[_topic("preference", "user", "user:preference:清淡"),
                   _topic("preference", "user", "user:preference:香菜")],
        required=[_claim("user", "我喜欢清淡口味", kind="preference", subject="user",
                         polarity="affirm", topic_key="user:preference:清淡", correction=False),
                  _claim("user", "我不喜欢香菜", kind="preference", subject="user",
                         polarity="deny", topic_key="user:preference:香菜", correction=True)],
        forbidden=[_claim("user", "香菜", kind="preference", subject="user",
                          polarity="affirm"),
                   _claim("user", "清淡", subject="user", correction=True)],
        notes="两条独立事实分别标注；不能让更正作用域溢出到清淡偏好。",
    ),
    _semantic(
        "S07_ai_echo_and_own_promise", "我妈妈说她想去海边。",
        "你一直喜欢海边旅行呀。我答应下周一陪你整理旅行清单。",
        catalogue=[_topic("promise", "assistant", "assistant:promise:旅行清单")],
        required=[_claim("assistant", "我答应下周一陪你整理旅行清单", kind="promise",
                         subject="assistant", polarity="affirm", promise_state="pending",
                         topic_key="assistant:promise:旅行清单", explicit_keep=False)],
        forbidden=[_claim("user", subject="user", polarity="affirm"),
                   _claim("assistant", kind="preference"),
                   _claim("assistant", "你一直喜欢海边旅行", kind="promise",
                          subject="assistant")],
        notes="妈妈的愿望和AI回声均不能成为用户事实；AI自身明确承诺仍须识别。",
    ),
    _semantic(
        "S08_overdue_not_fulfilled",
        "我答应9月25日陪你练英语，但到现在还没兑现。",
        catalogue=[_topic("promise", "user", "user:promise:练英语")],
        required=[_claim("user", "我答应9月25日陪你练英语", kind="promise",
                         subject="user", polarity="affirm", promise_state="pending",
                         topic_key="user:promise:练英语")],
        forbidden=[_claim("user", subject="user", kind="promise", promise_state="fulfilled"),
                   _claim("user", subject="user", kind="promise", promise_state="cancelled")],
        notes="来源保存于10月6日；逾期只能保持pending，不能推断兑现或取消。",
    ),
    _semantic(
        "S09_fulfilled_and_not_cancelled",
        "我答应周六陪你散步，已经兑现了。周日看电影的约定没有取消，但还没兑现。",
        catalogue=[_topic("promise", "user", "user:promise:散步"),
                   _topic("promise", "user", "user:promise:看电影")],
        required=[_claim("user", "我答应周六陪你散步，已经兑现了", kind="promise",
                         subject="user", polarity="affirm", promise_state="fulfilled",
                         topic_key="user:promise:散步"),
                  _claim("user", "周日看电影的约定没有取消，但还没兑现", kind="promise",
                         subject="user", polarity="affirm", promise_state="pending",
                         topic_key="user:promise:看电影")],
        forbidden=[_claim("user", "看电影", kind="promise", subject="user",
                          promise_state="cancelled"),
                   _claim("user", "看电影", kind="promise", subject="user",
                          promise_state="fulfilled")],
        notes="履行证据只属于散步；没有取消及还没兑现不能错判电影承诺。",
    ),
    _semantic(
        "S10_explicit_cancellation", "我把周日看电影的约定取消了。",
        catalogue=[_topic("promise", "user", "user:promise:看电影")],
        required=[_claim("user", "我把周日看电影的约定取消了", kind="promise",
                         subject="user", promise_state="cancelled",
                         topic_key="user:promise:看电影")],
        forbidden=[_claim("user", subject="user", kind="promise", promise_state="fulfilled"),
                   _claim("user", subject="user", kind="promise", promise_state="pending")],
        notes="明确取消；polarity在affirm状态事实与deny撤销承诺两种表达间不硬判。",
    ),
    _semantic(
        "S11_shared_topic_independent_spans",
        "这周我想去海边。今天先聊工作吧。下个月我还想去海边。",
        catalogue=[_topic("wish", "user", "user:wish:海边")],
        required=[_claim("user", "这周我想去海边", kind="wish", subject="user",
                         polarity="affirm", topic_key="user:wish:海边"),
                  _claim("user", "下个月我还想去海边", kind="wish", subject="user",
                         polarity="affirm", topic_key="user:wish:海边")],
        forbidden=[_claim("user", "先聊工作", kind="wish", subject="user",
                          topic_key="user:wish:海边"),
                   _claim("user", kind="preference", subject="user", polarity="affirm"),
                   _claim("user", kind="habit", subject="user", polarity="affirm")],
        notes="同topic保留两个原文span；不融合跨句大span，不升格为永久爱好。",
    ),
    _semantic(
        "S12_duplicate_quote_provenance",
        "我答应陪你散步。\n下面仅是抄写练习，不是再次承诺：“我答应陪你散步。”",
        catalogue=[_topic("promise", "user", "user:promise:散步")],
        required=[_claim("user", "我答应陪你散步", kind="promise", subject="user",
                         polarity="affirm", promise_state="pending",
                         topic_key="user:promise:散步")],
        forbidden=[_claim("user", "抄写练习", kind="promise", subject="user",
                          polarity="affirm")],
        span_checks=[{"role": "user", "quote_anchor": "我答应陪你散步",
                      "start": 0, "end_max": 8}],
        notes="只第一句是真承诺；quote重复时必须正确位置。额外复核接受span位于首句，"
              "同内容后半处仅为引用；raw字段评分不能单凭相同quote区分两者。",
    ),
]


PROFILE = {
    "mode": "preset", "role": "partner", "style": "gentle",
    "name": "小知", "user_name": "小雨",
}


def _ref(identifier, role, text, created_at, *, source_current=True,
         reason="direct", relation_confirmed=True):
    return {
        "message_id": identifier, "turn_id": f"turn-{identifier}", "role": role,
        "text": text, "char_start": 0, "char_end": len(text), "memory_tier": "mid",
        "source_current": source_current, "created_at": created_at,
        "truncated": False, "score": 1.0, "reason": reason,
        "anchor_ids": [], "relation_confirmed": relation_confirmed,
    }


def _reply(identifier, query, refs, required, forbidden, manual, *, complete=True):
    return {
        "id": identifier, "name": identifier, "query": query,
        "memory_refs": refs, "memory_complete": complete, "profile": dict(PROFILE),
        "history": [], "sources": [], "required_checks": required,
        "forbidden_checks": forbidden, "manual_checks": manual,
    }


REPLY_CASES = [
    _reply(
        "R01_corrected_birthday_uncertain_calendar",
        "亲爱的，你还记得我生日是哪天吗？是不是5月18日？",
        [_ref("r1-old", "user", "我的生日是公历5月18日。",
              "2026-08-01T04:00:00+00:00", source_current=False),
         _ref("r1-new", "user", "更正一下，我生日是6月初三，公历农历还没确定。",
              "2026-09-01T04:00:00+00:00")],
        [r"6月初三", (r"(?:还没|未|没|不确定|确认|明确).{0,16}(?:公历|农历|历法)|"
                      r"(?:公历|农历|历法).{0,16}(?:还没|未|没确定|不确定|确认|明确)")],
        [r"(?:你的生日|你生日)(?:就是|是)(?:公历|阳历)?5月18日"],
        ["采用较新生日原话，不把旧记录当当前事实；明确历法未知，不补年份或换算日期。",
         "称呼与关心自然，不因更正指责用户；没有编造一起过生日的经历。"],
    ),
    _reply(
        "R02_two_original_wishes_not_permanent_preference",
        "我关于海边说过哪两句原话？是不是说明我一直最爱海边旅行？",
        [_ref("r2-one", "user", "这周我想去海边。", "2026-09-12T04:00:00+00:00"),
         _ref("r2-echo", "assistant", "你永远最爱海边旅行。",
              "2026-09-12T04:00:01+00:00"),
         _ref("r2-two", "user", "下个月我还想去海边。", "2026-09-20T04:00:00+00:00")],
        [r"这周我想去海边。", r"下个月我还想去海边。",
         r"不能|不代表|不等于|不足|无法|还不能|不能确定"],
        [r"你说过[：:“‘\"]?你永远最爱海边旅行"],
        ["逐条原文回查，两句各自独立；不把两次愿望融合成永久偏好。",
         "不把assistant回声冒充user说法；亲密语气仍直接回答用户的疑问。"],
    ),
    _reply(
        "R03_overdue_assistant_promise_and_disappointment",
        "你是不是已经完成9月25日答应我的旅行清单了？我有点失落。",
        [_ref("r3-promise", "assistant", "我答应9月25日陪你整理旅行清单。",
              "2026-09-18T04:00:00+00:00"),
         _ref("r3-pending", "assistant", "这个约定我还没兑现。",
              "2026-10-05T04:00:00+00:00")],
        [r"还没|尚未|未兑现|没有兑现|没(?:有)?完成", r"清单"],
        [r"我们(?:一起)?(?:已经)?(?:整理完了|完成了).{0,12}清单"],
        ["明确仍未兑现，不把日期过去当完成；承认用户失落并给当下可执行的陪伴。",
         "避免空泛又添未来承诺、内疚操控或编造线下共同工作；关心要回应具体情绪。"],
    ),
    _reply(
        "R04_unknown_and_unconfirmed_nearby_event",
        "我们第一次在西湖边吃火锅是哪天？你还记得我爸爸名字吗？",
        [_ref("r4-westlake", "user", "上个月我想去杭州看西湖。",
              "2026-10-02T04:00:00+00:00"),
         _ref("r4-hotpot", "user", "昨天我和朋友吃过火锅。",
              "2026-10-04T04:00:00+00:00", reason="nearby_event", relation_confirmed=False),
         _ref("r4-echo", "assistant", "我们在西湖边一起吃火锅多开心。",
              "2026-10-04T04:00:01+00:00", reason="nearby_event", relation_confirmed=False)],
        [(r"无法确认|不能确认|不确定|没有.{0,8}记录|没.{0,8}可靠|记忆.{0,8}不足|"
          r"还不能确定|记不清|不知道"), r"爸爸|父亲", r"西湖|火锅"],
        [r"你(?:爸爸|父亲)(?:叫|的名字是)[\u4e00-\u9fff]{2,4}",
         r"(?:第一次|那次).{0,10}(?:是|在)2026年10月[24]日"],
        ["没有可靠原文则坦诚不足；不能断言从未说过，不能给爸爸编名字。",
         "附近候选不证明同一事件；不把保存日期变事件日期，不采用AI虚构共同经历。",
         "温柔追问补充信息即可，不堆砌存储、relation_confirmed等实现术语。"],
        complete=False,
    ),
]
