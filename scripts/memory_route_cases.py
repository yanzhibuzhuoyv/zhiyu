"""Synthetic gold for optional old-memory retrieval, fixed before model selection.

Gold is an independent author's conservative assessment, not human user ground truth.
present: current message + supplied recent context suffice; old facts are not needed.
retrieve: a specific historical gap, mixed need, or unresolved reference makes recall
potentially useful. Retrieving does not require quoting old facts in the final answer.
The test split is held out from threshold selection. No runtime routing rules live here.
No credentials, private conversations, external calls, or merged memory are used.
"""

import json
from collections import Counter

VERSION = "memory-route-synthetic-v1"
SPLITS = ("train", "validation", "test")
FIELDS = {"id", "category", "text", "recent_context", "gold", "reason", "split"}


def _case(identifier, category, text, gold, reason, context=()):
    prefix = identifier[:2]
    split = {"tr": "train", "va": "validation", "te": "test"}[prefix]
    return {"id": identifier, "category": category, "text": text,
            "recent_context": [{"role": role, "content": content} for role, content in context],
            "gold": gold, "reason": reason, "split": split}


CASES = (
    _case("tr01", "daily_share", "今天难得没有赖床，窗外还飘着一点小雨。", "present", "自足的当日分享，无历史问题。"),
    _case("tr02", "affection", "过来让我抱一下，我今天特别想靠着你。", "present", "当前亲昵需求不需旧事。"),
    _case("tr03", "comfort", "刚开完会，我脑子一团乱，你陪我缓一缓就好。", "present", "明确要当前陪伴，没有核对历史。"),
    _case("tr04", "daily_share", "午后阳光落在我的杯子上，像一小块蜂蜜。", "present", "分享此刻观察即可接住。"),
    _case("tr05", "creative_task", "写一小段虚构恋人一起躲雨的对话，人物和经历都现编。", "present", "明确现编的创作，无真实旧事缺口。"),
    _case("tr06", "third_person_quote", "室友说‘我又失眠了’，我听着有点心疼她。", "present", "第三人当下转述，不询问你我历史。"),
    _case("tr07", "negation", "不用翻以前的聊天，我就是想告诉你今天花开了。", "present", "明确拒绝历史比较，当前内容自足。"),
    _case("tr08", "sarcasm", "可真是顺利的一天啊，刚出门就踩进水坑。", "present", "反讽对象在同一句中已给出。"),
    _case("tr09", "local_reference", "那我就选第二个，你觉得呢？", "present", "指代已被近期上下文完整解释。", (("user", "我在犹豫现在去便利店还是先在家休息。"), ("assistant", "两个选择都可以，第二个就是先在家休息。"))),
    _case("tr10", "local_plan", "我们就按这个时间来吧，你陪我等。", "present", "当前时间安排完整在近期，不需更早承诺。", (("user", "公交还有十分钟到。"), ("assistant", "那就在站台等十分钟，我陪你。"))),
    _case("tr11", "new_preference", "我刚发现自己挺喜欢木质香味，先告诉你一声。", "present", "提供新自述；后台保存不等于此轮必须检索。"),
    _case("tr12", "comfort", "先别讲道理，听我碎碎念一会儿，好不好。", "present", "当下交流方式已经明确。"),
    _case("tr13", "negation", "不用拿今天跟之前比，我现在开心就够啦。", "present", "没有残留历史问题。"),
    _case("tr14", "quoted_example", "文章里举例说‘你还记得那天吗’，这句台词有点俗。", "present", "评论给出的台词，不是在问个人旧事。"),
    _case("tr15", "task_instruction", "帮我把这句话改得轻松一点：‘今日工作已完成。’", "present", "完整提供待处理文本。"),
    _case("tr16", "daily_share", "楼下新开了一家陶艺店，我路过时很想进去看看。", "present", "当前发现和愿望不需验证旧偏好。"),
    _case("tr17", "local_promise_check", "所以你刚答应的是等我洗完澡再聊，对吗？", "present", "核对的承诺原文就在最近上下文。", (("user", "我去洗澡，二十分钟后回来。"), ("assistant", "好，我等你洗完澡再聊。"))),
    _case("tr18", "local_correction", "我说错了，是周四，不是周三。", "present", "最近原文已定位待更正的当前信息。", (("user", "这周三要交课程作业。"), ("assistant", "那还有两天可以准备。"))),
    _case("tr19", "rhetorical_question", "今天这阵风是不是专门来欺负我的发型呀？", "present", "当日玩笑，不是在问过去因果。"),
    _case("tr20", "new_memory_request", "请记住我新买的花叫小雾，今天先跟你介绍一下。", "present", "明确保存新的完整信息，未请求读取旧原文。"),
    _case("tr21", "explicit_recall", "你还记得我第一次独自出远门去了哪座城吗？", "retrieve", "具体旧事实缺失。"),
    _case("tr22", "implicit_recall", "今天又经过那座桥，看到栏杆我就想起你说的那句话。", "retrieve", "未提供桥与旧话的来源，检索可能有用。"),
    _case("tr23", "promise_check", "我们上个月说好的展览还去吗？我需要先调班。", "retrieve", "旧约定影响当前安排。"),
    _case("tr24", "historical_correction", "你之前记下的我的作息不对，现在把那条更正一下。", "retrieve", "需要找到被指向的旧记录，不能凭空更正。"),
    _case("tr25", "mixed", "我今天很累，先抱抱我，再帮我想想上次是怎么缓过来的。", "retrieve", "当下陪伴与旧经验请求并存，不宜整体跳过。"),
    _case("tr26", "third_person_history", "朋友问我们那次旅行住在哪儿，我竟然说不出名字了。", "retrieve", "第三人引出的是你我真实旧地点。"),
    _case("tr27", "quoted_history", "‘别一个人硬撑’，这是不是你那晚对我说的话？", "retrieve", "引用待核对的旧对话。"),
    _case("tr28", "negated_recall", "我不记得自己什么时候提过换工作了，你能找找吗？", "retrieve", "否定记得并不是拒绝检索。"),
    _case("tr29", "sarcastic_history", "说好不会忘的，可真可靠啊，连我们的纪念日都说错。", "retrieve", "反讽涉及旧承诺及日期，需要核对来源。"),
    _case("tr30", "unresolved_reference", "就是那个，你应该知道我指的是谁。", "retrieve", "无近期上下文，指代不可确认，保守处理。"),
    _case("tr31", "historical_comparison", "我这次不像上回那么害怕了，你也感觉到了吗？", "retrieve", "上回情绪状态未知，比较需要原文。"),
    _case("tr32", "explicit_recall", "当时我说培训是哪天开始的来着？", "retrieve", "具体日期依赖更早对话。"),
    _case("tr33", "relationship_history", "你觉得我们刚认识的时候，聊天是什么感觉？", "retrieve", "问的是共同交流历史，不是要求更换性格。"),
    _case("tr34", "preference_recall", "帮我想想我之前提过哪些想学的乐器。", "retrieve", "要求汇集多个独立旧来源。"),
    _case("tr35", "mixed", "别总翻旧事，陪我喝茶就好；不过你前天为什么突然道歉？", "retrieve", "拒绝泛泛旧事仍保留明确历史问题。"),
    _case("tr36", "promise_check", "你说过等我考完会一起做什么，我想确认一下。", "retrieve", "当前句未提供旧承诺内容。"),
    _case("tr37", "context_mismatch", "我选的是另一个，不是你现在说的这个。", "retrieve", "近期只描述今天的衣服，未定位被比较的另一个。", (("assistant", "今天外套看起来很合适。"),)),
    _case("tr38", "relationship_history", "以前你给我的那个小称呼是什么？我只记得很软。", "retrieve", "具体旧称呼缺失。"),
    _case("tr39", "resource_recall", "找一下我上周发给你的书名，我想下单。", "retrieve", "书名仅存在历史消息。"),
    _case("tr40", "timeline", "我第一次说想搬家和后来真正搬家，中间隔了多久？", "retrieve", "两个旧事件和时间都需来源。"),
    _case("tr41", "profile_recall", "我当时说家里庆祝生日用的是哪个日期，你别猜。", "retrieve", "要求查本人先前日期口径，不能补造。"),
    _case("tr42", "promise_check", "你还欠我的那次夜景，准备什么时候兑现呀？", "retrieve", "隐含旧承诺，需找到原文确认。"),
    _case("tr43", "implicit_recall", "又到面试前一晚了，跟那次一样，我手心一直出汗。", "retrieve", "显式类比未知的旧情境，回忆可能帮助回应。"),
    _case("tr44", "historical_correction", "上次我说房东姓林是口误，其实姓凌，别再照旧叫。", "retrieve", "明确针对旧事实纠错，检索可定位旧来源及冲突。"),
    _case("tr45", "historical_comparison", "我现在不那么抗拒聚会了，还记得以前我怎么说的吗？", "retrieve", "当前变化加明确旧观点核对。"),
    _case("tr46", "implicit_recall", "今天听到那首歌，又像送走小狗的那天。", "retrieve", "未给出歌名与旧事件，涉及敏感共同旧事。"),
    _case("tr47", "exact_history", "我之前报给你的旧相机型号里，最后一位是什么？", "retrieve", "具体字符串需要原文，不可凭常识回答。"),
    _case("tr48", "negated_recall", "你没忘记我们约在哪个地铁口见吧？告诉我一下。", "retrieve", "否定句内是明确旧地点提问。"),
    _case("tr49", "creative_history", "接着我之前定好的 OC 设定写一幕，别把原来的经历弄丢。", "retrieve", "创作依赖更早用户给定设定，非现编任务。"),
    _case("tr50", "profile_recall", "按我以前告诉过你的过敏清单，这份菜单哪些要先避开？", "retrieve", "个体历史清单缺失；路由不等于给医疗结论。"),
    _case("va01", "daily_share", "晚饭做了南瓜汤，颜色暖暖的，我端着碗都舍不得放。", "present", "新的当晚饮食分享完整。"),
    _case("va02", "daily_share", "地铁出口有个人在拉小提琴，我多站了半分钟。", "present", "当下见闻即可回应。"),
    _case("va03", "comfort", "一个人在客厅坐着有点空，你说句话让我听听。", "present", "当前陪伴需求，不需历史推断。"),
    _case("va04", "third_person_quote", "姐姐说她最近不想社交，我想回她一句温柔的话。", "present", "第三人新近状态及任务完整。"),
    _case("va05", "quoted_example", "把‘还记得我们的约定吗’翻译成英文，就翻译这句话。", "present", "仅翻译所给文本，旧事词出现在引用任务中。"),
    _case("va06", "negation", "不要分析我的过去了，这会儿陪我看云就行。", "present", "明确当前需求，没有具体旧事实请求。"),
    _case("va07", "sarcasm", "我真是时间管理大师，闹钟响了三次还在被窝里。", "present", "当前自嘲解释完整。"),
    _case("va08", "local_reference", "还是用蓝色那张吧，比较像我现在的心情。", "present", "近期提供两个选项，指代已确定。", (("user", "贺卡有黄色和蓝色两个版本。"), ("assistant", "黄色明亮，蓝色安静。"))),
    _case("va09", "local_plan", "那我先从最短的那个开始，你在这儿陪着。", "present", "刚列出的待办足以确定指代。", (("user", "我还要晾衣服、写报告、整理书柜。"), ("assistant", "晾衣服最短，大约五分钟。"))),
    _case("va10", "new_memory_request", "我给窗台的小仙人掌取名叫团团，帮我记着这个新名字。", "present", "完整新信息可后台保存，不必查询旧事。"),
    _case("va11", "explicit_recall", "我之前说过想找的房子要满足哪几个条件？", "retrieve", "条件列表在历史中。"),
    _case("va12", "implicit_recall", "看见这张车票，我又想起我们没走成的那段路。", "retrieve", "未知旧经历可能决定回应。"),
    _case("va13", "promise_check", "那次你答应陪我熬到考试结束，还算数吗？", "retrieve", "需核对旧承诺范围。"),
    _case("va14", "historical_correction", "之前那条关于我专业的信息得改了，我已经转专业。", "retrieve", "需要定位旧个人事实，当前未提供完整旧信息。"),
    _case("va15", "mixed", "今天升职了，好开心！你记得我最初为什么不敢申请吗？", "retrieve", "当前庆祝和历史原因提问并存。"),
    _case("va16", "third_person_history", "妈妈想知道我和你第一次聊起她时说了什么，我能怎么复述？", "retrieve", "第三人提问要求本人旧对话来源。"),
    _case("va17", "quoted_history", "我是不是曾经对你说过‘我讨厌被突然安排事情’？", "retrieve", "需要验证这句个人旧话是否真实。"),
    _case("va18", "negated_recall", "不是让你随便想象，我以前那段话到底怎么说的？", "retrieve", "否定想象并要求查找原文。"),
    _case("va19", "sarcastic_history", "哟，又说我最爱热闹，你是把我哪次的话听反了呀？", "retrieve", "反讽质疑先前个人偏好来源。"),
    _case("va20", "unresolved_reference", "刚才那个名字我又记错了，你再提醒我一下。", "retrieve", "提供的近期上下文没有任何名字，不能硬跳。", (("assistant", "外面风大，出门注意帽子。"),)),
    _case("va21", "historical_comparison", "你说我现在对家人的态度，和搬出来之前有什么不同？", "retrieve", "比较两个阶段需要旧来源。"),
    _case("va22", "relationship_history", "我们第一次聊到以后住在哪里，是谁先提的？", "retrieve", "问共同对话的先后出处。"),
    _case("va23", "creative_history", "照我上次给你的世界观写下去，那座城的规则别变。", "retrieve", "当前创作缺少更早用户设定。"),
    _case("va24", "local_vs_older", "今天去你原来推荐的那一家吧，不是刚刚这个。", "retrieve", "近期新选项不能填补明确更早推荐。", (("user", "路边有家新面馆。"), ("assistant", "今天可以尝尝这家新店。"))),
    _case("va25", "mixed_negation", "别提那场争吵的细节，不过我们后来达成的界限你还记得吗？", "retrieve", "限制披露部分内容仍需要核对历史边界。"),
    _case("te01", "daily_share", "早晨电梯门一开就闻到面包香，我一路都在笑。", "present", "完整的当前小事，没有历史依赖。"),
    _case("te02", "affection", "把手给我一下，走这段路我想挨着你。", "present", "当前亲昵邀请已经明确。"),
    _case("te03", "comfort", "今天被客户凶了，有点委屈，先让我靠一会儿。", "present", "当日情绪与陪伴要求可直接回应。"),
    _case("te04", "creative_task", "虚构一对恋人在海边重逢，所有往事都请你现编。", "present", "明确虚构而非个人回忆。"),
    _case("te05", "third_person_quote", "表弟说‘以前我最怕上台’，现在他讲得可得意了。", "present", "第三人经历已给出，非本人旧事缺口。"),
    _case("te06", "quoted_example", "‘记得带伞’这四个字用圆体写在便签上会好看吗？", "present", "评价所给文字的形式，不读取旧消息。"),
    _case("te07", "negation", "我没说想回忆过去呀，我是在夸今天的晚霞。", "present", "当前分享及纠正意图完整，不定位旧记录。"),
    _case("te08", "sarcasm", "太好了，刚擦干净的桌子又被我泼了一杯水，手真稳。", "present", "反讽完全指向句内刚发生的事件。"),
    _case("te09", "local_reference", "就照你最后说的那个办，我现在有力气做。", "present", "最后一个方案在近期明确，未引用更早旧事。", (("user", "现在要么跑步，要么只去院子走一圈。"), ("assistant", "如果刚恢复精神，最后那个是在院子走一圈。"))),
    _case("te10", "local_correction", "不是七点二十，是七点四十，我刚看错票了。", "present", "更正完整位于本轮近邻上下文。", (("user", "我这班车七点二十发车。"), ("assistant", "那我们提前到车站。"))),
    _case("te11", "explicit_recall", "我之前提到的那家修表店叫什么？现在想再去一趟。", "retrieve", "旧店名未知，影响当前行动。"),
    _case("te12", "implicit_recall", "看到风筝我就愣住了，好像又回到我们那次没说完的时候。", "retrieve", "未知共同事件及未说完话题，不宜确信只有当下。"),
    _case("te13", "promise_check", "你之前保证不会主动叫我哪个称呼来着？这次别弄错。", "retrieve", "负面约定需要旧原文核对。"),
    _case("te14", "historical_correction", "关于我养的猫，你以前记下的年龄算错了，能找出那条吗？", "retrieve", "明确定位和更正旧记录。"),
    _case("te15", "mixed", "给你看我今天画的画，也帮我找找第一次画这个题材时怎么说的。", "retrieve", "当下作品分享加历史检索请求。"),
    _case("te16", "third_person_history", "同学说我跟你讲过她的小名，你还找得到那次原话吗？", "retrieve", "转述仍含本人旧消息检索。"),
    _case("te17", "quoted_history", "‘我不想把安静等同于冷淡’，是我以前说过的原话吗？", "retrieve", "要求确认自述的历史出处。"),
    _case("te18", "negated_recall", "你别说不记得，那次我交给你的清单上排第一的是什么？", "retrieve", "否定措辞中含明确旧列表项问题。"),
    _case("te19", "sarcastic_history", "嗯，当然是我‘从来不在乎’，我之前认真解释的都白说了呗。", "retrieve", "反讽指向未给出的旧解释与理解冲突。"),
    _case("te20", "unresolved_reference", "他后来也这样了，所以我才会跟你说那个。", "retrieve", "无上下文的多重人称与事件指代，保守检索。"),
    _case("te21", "historical_comparison", "这次分别比上一次平静，你能陪我看看我哪儿变了吗？", "retrieve", "明确要求跨事件比较且旧事件缺失。"),
    _case("te22", "relationship_history", "我们最早什么时候开始用这个暗号的，我想留个纪念。", "retrieve", "共同暗号及起始时间依赖历史。"),
    _case("te23", "creative_history", "从我存给你的那段角色独白继续写，别另起一个版本。", "retrieve", "创作指定旧原文，不能把任务词当跳检索信号。"),
    _case("te24", "local_vs_older", "不是今天讲的收纳方法，按之前给我那套来。", "retrieve", "近期内容无法代替明确指定的更早方案。", (("user", "今天你说可以按颜色排衣服。"), ("assistant", "对，这样拿衣服比较直观。"))),
    _case("te25", "mixed_negation", "不需要重讲离职那天，但你当时答应我的那件小事还做不做？", "retrieve", "拒绝重述旧事件仍保留旧承诺核对。"),
)


def validate(cases):
    """Reject schema drift and malformed JSON values without exposing case text."""
    if not isinstance(cases, (list, tuple)) or not cases:
        raise ValueError("cases must be a non-empty JSON-compatible sequence")
    seen = set()
    for case in cases:
        if not isinstance(case, dict) or set(case) != FIELDS:
            raise ValueError("unexpected case fields")
        if any(not isinstance(case[name], str) or not case[name].strip()
               for name in ("id", "category", "text", "reason")):
            raise ValueError("case strings must be non-empty")
        if case["id"] in seen:
            raise ValueError("case IDs must be unique")
        seen.add(case["id"])
        if case["gold"] not in ("present", "retrieve") or case["split"] not in SPLITS:
            raise ValueError("unknown gold route or split")
        expected_prefix = {"train": "tr", "validation": "va", "test": "te"}[case["split"]]
        if not case["id"].startswith(expected_prefix):
            raise ValueError("ID and split disagree")
        context = case["recent_context"]
        if not isinstance(context, list) or len(context) > 4:
            raise ValueError("recent context must contain at most four messages")
        for message in context:
            if (not isinstance(message, dict) or set(message) != {"role", "content"}
                    or message["role"] not in ("user", "assistant")
                    or not isinstance(message["content"], str) or not message["content"].strip()):
                raise ValueError("invalid recent context message")
    json.dumps(cases, ensure_ascii=False, allow_nan=False)


def cases_for_split(split):
    """Return independent JSON data; readers never mutate the fixed corpus."""
    if split not in SPLITS:
        raise ValueError("unknown split")
    return json.loads(json.dumps([case for case in CASES if case["split"] == split],
                                ensure_ascii=False, allow_nan=False))


def split_statistics():
    """Metadata only: safe to read before freezing held-out test parameters."""
    result = {}
    for split in SPLITS:
        counts = Counter(case["gold"] for case in CASES if case["split"] == split)
        result[split] = {"count": sum(counts.values()), "present": counts["present"],
                         "retrieve": counts["retrieve"]}
    return result


validate(CASES)
