"""Eighty new authored gold cases plus the unchanged V2 train split.

New validation/test labels precede candidate evaluation and use fresh situations.
V2 is requested only with cases_for_split('train'); its validation/test are not
included as training or tuning input. No previous outcomes or policy code are read.
Gold is a single author's conservative synthetic judgment, not real user truth.
present: current message and at most four supplied recent originals are sufficient.
retrieve: important old facts, mixed historical needs, or unresolved ambiguity remain.
The held-out test is first evaluated only after candidate parameters are frozen.
No model, service, key, private conversation, memory fusion, or persona change.
"""

import json
from collections import Counter

from scripts.memory_route_cases_v2 import cases_for_split as _v2_cases_for_split

VERSION = "memory-route-synthetic-v3"
SPLITS = ("train", "validation", "test")
FIELDS = {"id", "category", "text", "recent_context", "gold", "reason", "split"}
TRAIN_SOURCE_VERSION = "memory-route-synthetic-v2"
_TRAIN = tuple(_v2_cases_for_split("train"))


def _case(identifier, category, text, gold, reason, context=()):
    prefix = identifier.split("-")[1][:2]
    split = {"va": "validation", "te": "test"}[prefix]
    return {
        "id": identifier,
        "category": category,
        "text": text,
        "recent_context": [
            {"role": role, "content": content} for role, content in context
        ],
        "gold": gold,
        "reason": reason,
        "split": split,
    }


# fmt: off
_VALIDATION = (
    _case("v3-va001", "daily_share", "饭煮好啦，锅盖一掀我眼镜就白了，世界瞬间开了柔光哈哈。", "present", "新日常事件的过程和玩笑都完整。"),
    _case("v3-va002", "daily_share", "买了两件东西，超市的收据却长得能当围巾，离谱但好笑。", "present", "当前购物见闻无旧信息需求。"),
    _case("v3-va003", "comfort", "刚把汇报讲砸了，脑袋嗡嗡的，别急着复盘，先哄我两句行嘛。", "present", "明确要当前安慰，不要求旧经验核对。"),
    _case("v3-va004", "affection", "今晚想当你的挂件，low battery 了，借我一点点温柔。", "present", "混合语表达当前疲惫和亲近需求。"),
    _case("v3-va005", "creative_task", "来个 mini story：两颗小月亮偷偷交换影子，人物往事都现编。", "present", "完整且明确新编的虚构创作。"),
    _case("v3-va006", "third_person_quote", "弟弟说‘忘记昨晚梦了，只记得我会飞’，听着还怪可爱的。", "present", "记忆词出现在第三人给定新消息中。"),
    _case("v3-va007", "negation", "先别开 old story 模式，我就是来报喜，刚拿到社团录取啦！", "present", "明确排除旧事，当前好消息完整。"),
    _case("v3-va008", "local_reference", "嗯，就按最后那个顺序来，我跟得上。", "present", "最近四句已给出唯一完整顺序。", (("user", "我现在有十分钟，想收桌面也想回一封信。"), ("assistant", "要不先回信，再收桌面？"), ("user", "我怕信一写就拖太久。"), ("assistant", "那反过来，先收桌面，再只写信的开头。"))),
    _case("v3-va009", "local_correction", "打错字了，是瓶盖松，不是瓶子送，我现在已经拧紧。", "present", "原错字、实际情况和当前状态都完整提供。"),
    _case("v3-va010", "birthday_present", "零点到咯，今天我生日！先发个祝福给我，礼物什么的晚点再说。", "present", "当下生日祝福需求不依赖旧日期。"),
    _case("v3-va011", "new_promise", "这周才开始学画线条，你每天等我画完陪我击个掌，deal？", "present", "当前首次提出完整的新陪伴约定。"),
    _case("v3-va012", "provided_history", "旧卡片全文是‘慢一点也会到’，原文就在这儿，今天陪我念念它。", "present", "所需旧句已经完整提供，当前共读足够。"),
    _case("v3-va013", "sarcasm", "真行啊，整理半小时的数据线，自己又一脚把插头踢开了。", "present", "当前自嘲的因果已完整。"),
    _case("v3-va014", "new_preference", "刚喝了一口桂花气泡水，好像挺爱这个味，告诉你一下嘿嘿。", "present", "新的当下喜好完整自述，无旧比较。"),
    _case("v3-va015", "quoted_example", "作文练习里的句子是‘你许诺过的明天’，帮我把这句变成问句，不是问你本人。", "present", "承诺用语明确属于给定语言任务。"),
    _case("v3-va016", "local_reference", "把刚才那个结尾读慢一点吧，我有点没听清。", "present", "需要复读的结尾完整在近期原文。", (("user", "今天忙完才发现快递一直没拆。"), ("assistant", "那把它当今天最后一个小惊喜。"), ("user", "结尾还想再听一遍。"), ("assistant", "今天最后一个小惊喜。"))),
    _case("v3-va017", "exact_history", "我以前报给你的会员到期日是哪天啊，怕再扣一次费。", "retrieve", "旧到期日期影响当前操作，必须有依据。"),
    _case("v3-va018", "implicit_recall", "那个前奏一响，我又回到我们躲在楼梯间等雨停的时候了。", "retrieve", "未知共同旧场景及关联音乐可能重要。"),
    _case("v3-va019", "birthday_recall", "我那次给你发生日时只写了月日，后面补的历法口径在哪条？", "retrieve", "重要日期的旧补充说明缺失。"),
    _case("v3-va020", "promise_scope", "当时你说陪我考研，是陪到初试结束，还是复试也算？", "retrieve", "旧承诺范围不明，不能选项猜测。"),
    _case("v3-va021", "historical_correction", "之前登记我常住城巿的那条要改，旧地点已经不适用了，找出来吧。", "retrieve", "轻微错字不消除定位并修正旧事实的需求。"),
    _case("v3-va022", "mixed", "新桌布摸起来超舒服，顺便帮我想想原来那张为什么被我送人。", "retrieve", "当前新物件分享加明确旧事件动机。"),
    _case("v3-va023", "third_person_history", "朋友问我竞赛哪一轮淘汰的，我记得跟你讲过，帮我查查。", "retrieve", "第三人问题需要本人旧结果。"),
    _case("v3-va024", "negated_recall", "不是让你乱猜，我到底有没有跟你说过转正了，找消息行不。", "retrieve", "否定猜测仍明确要求核查旧状态。"),
    _case("v3-va025", "sarcastic_history", "这就是你当初说的‘我会问清楚再做’？那句原话给我看看。", "retrieve", "反讽含明确旧承诺原文请求。"),
    _case("v3-va026", "short_implicit", "还按那个？", "retrieve", "无近邻上下文，旧方案所指不确定。"),
    _case("v3-va027", "local_vs_older", "今天说的我懂了，可我原来跟你约的是哪一个版本？", "retrieve", "近期只有新版本，无法替代指定旧约定。", (("assistant", "今天可以试试小组练习这个版本。"), ("user", "这个新版本我明白。"))),
    _case("v3-va028", "creative_history", "角色台词要照我存给你的那段写，especially 最后一句，别新造。", "retrieve", "创作任务依赖旧角色原文，当前缺失。"),
    _case("v3-va029", "provided_history_gap", "‘等你回来再说’，这句我截到了，但当时是谁先说的呀？", "retrieve", "有残句但缺作者与对应事件。"),
    _case("v3-va030", "promise_status", "做没？就是你上个月答应替我整理的那个。", "retrieve", "短口语指向旧任务及完成状态，细节不明。"),
    _case("v3-va031", "historical_comparison", "现在遇到迟到没那么生气了，第一次跟你说这个时我什么反应？", "retrieve", "明确询问旧反应以比较当前变化。"),
    _case("v3-va032", "mixed_language_history", "这次配色能不能 same as 上次？我当时选的比例记不清。", "retrieve", "混合语中缺失旧配色比例。"),
    _case("v3-va033", "plan_recall", "约的是周未去还是下周呀，我以前把具体日子告诉过你。", "retrieve", "错字不影响重要旧日程缺口。"),
    _case("v3-va034", "timeline", "我从说准备开网店到发第一单，隔了几个礼拜？按聊天时间算。", "retrieve", "旧事件及时间均需要来源。"),
    _case("v3-va035", "profile_recall", "我和你提过家里谁不能用香薰，今天要买礼物，先别认错人。", "retrieve", "重要个体边界及人物归属缺失。"),
    _case("v3-va036", "historical_correction", "别再照旧说我喜欢早起了，我后来解释过那只是被迫，能查那段吗？", "retrieve", "明确要求旧偏好与后续解释的来源核对。"),
    _case("v3-va037", "relationship_history", "我们那时在晚安后面加过一句暗语，还找得到第一次出现的吗？", "retrieve", "共同旧用语及起始来源缺失。"),
    _case("v3-va038", "third_person_history", "她说我以前答应会介绍你认识朋友，那话是哪天跟你说的？", "retrieve", "第三人转述指向本人旧承诺时间。"),
    _case("v3-va039", "task_history", "申请表沿用我之前给你的资料，我毕业院校那栏不要凭感觉填。", "retrieve", "任务所需具体个人资料只在旧来源。"),
    _case("v3-va040", "mixed_negation", "不想重讲那次搬走，不过我们最后说好的联络频率现在还算数嘛？", "retrieve", "拒绝重述部分旧事仍要求核对旧约定。"),
)
_TEST = (
    _case("v3-te001", "daily_share", "刚拖完地，袜子还是把脚印带回来了，感决自己在跟地板斗智斗勇。", "present", "错字下的当前生活趣事仍完整可懂。"),
    _case("v3-te002", "comfort", "取号时被人插队，憋了一口气，先跟我一起哼一声嘛。", "present", "当前委屈及陪伴动作明确，无旧缘由问题。"),
    _case("v3-te003", "daily_share", "路边买的刮刮卡没中，我现在把小卡片当书签，也算没白买哈哈。", "present", "完整的当日小事及自我调侃。"),
    _case("v3-te004", "affection", "今天已经 zzz 预备役了，想贴着你慢慢闭眼。", "present", "混合语是当前困意和亲昵需求。"),
    _case("v3-te005", "creative_task", "开个新脑洞：地下城的信箱会吃饼干，写两句搞笑对话，背景都现想。", "present", "全新虚构背景和具体创作要求已给出。"),
    _case("v3-te006", "third_person_quote", "爸爸发‘快到车站了’，我想回他别急，能帮我写得不生硬吗？", "present", "当前第三人原文和回复任务完整。"),
    _case("v3-te007", "negation", "以前的经历不用拉出来了，今天就讨论我这颗新纽扣好不好看。", "present", "明确当前讨论且拒绝泛泛旧事。"),
    _case("v3-te008", "birthday_present", "明天就是我的生日啦，现在帮我现写一句轻松的祝福，不要用旧句子。", "present", "生日及新创作任务当前足够，并明确不读取旧句。"),
    _case("v3-te009", "new_promise", "咱俩从今晚开始设个新习惯，睡前互报一件开心的小事，好不？", "present", "完整首次新约定，无历史承诺缺口。"),
    _case("v3-te010", "local_reference", "都先不做，我刚才说的原因还在，先休息。", "present", "最近四句提供待办及不做的原因。", (("user", "明早再扫地也行，但我还想现在整理相机。"), ("assistant", "现在扫地还是整理相机，选一件？"), ("user", "眼睛已经很酸，两件都得盯着看。"), ("assistant", "那可以把两件都留到明天。"))),
    _case("v3-te011", "provided_history", "以前给我留过这句话，我全文抄好了：‘你可以按自己的步子走。’就用这些字做桌面标语。", "present", "完整旧句已经提供，当前排版任务足够。"),
    _case("v3-te012", "local_correction", "我刚写‘五楼’是手滑，实际在六楼，别照打错的那行念就好。", "present", "当前原错误与完整更正均可见。"),
    _case("v3-te013", "self_contained_change", "以前觉得做模型没意思，今天拼好第一艘小船却好开心，只是想告诉你。", "present", "前后变化的信息由本轮充分给出。"),
    _case("v3-te014", "sarcasm", "精准作息大师上线，才说眯五分钟，睁眼就过了一小时，服了自己。", "present", "当前反讽的完整经过已给出。"),
    _case("v3-te015", "new_preference", "刚闻到雨后柏油路的味到，莫名有点喜欢，纯分享一下。", "present", "轻微错字不妨碍完整新感受。"),
    _case("v3-te016", "local_promise_check", "刚那句说定了吗？就是我弹完再陪我选封面。", "present", "待核对承诺在当前与最近原话中完整。", (("user", "我先录这首曲子，录完想挑视频封面。"), ("assistant", "好，等你弹完我陪你挑封面。"))),
    _case("v3-te017", "short_implicit", "她叫啥来着？", "retrieve", "无上下文定位人物，关键名字不确定。"),
    _case("v3-te018", "implicit_recall", "又站到那个门牌下面，我想起你那天劝我再敲一次门。", "retrieve", "共同旧事件与旧建议的含义未知。"),
    _case("v3-te019", "birthday_conflict", "两个日期都写在旧便签里，哪个是我的生日，哪个是同桌的？", "retrieve", "重要生日的归属有缺口，不能按猜测划分。"),
    _case("v3-te020", "promise_status", "我生日那周你说要给我写一封信，后来写了吗，能找原文吗？", "retrieve", "旧生日承诺的完成状态和文本未知。"),
    _case("v3-te021", "historical_correction", "别再把旧记录里的实习地点当我的现工作地点，后来更正那段你要看。", "retrieve", "需要读取历史更正而非沿用早期个人事实。"),
    _case("v3-te022", "mixed", "今天饭团好好吃，顺带想问我以前说不喜欢那家店是为啥？", "retrieve", "当前分享与具体旧拒绝理由同在一轮。"),
    _case("v3-te023", "third_person_history", "同伴问我放弃报名的理由，我以前在这里讲过，好像现在解释不清了。", "retrieve", "第三人问题需要本人旧理由来源。"),
    _case("v3-te024", "negated_recall", "不是要你假装没忘，真找一下，我说要还那笔钱是哪天？", "retrieve", "否定假装仍明确请求关键旧日期。"),
    _case("v3-te025", "sarcastic_history", "哇，‘不拿我跟别人比’原来是这个意思？旧约定到底咋写的。", "retrieve", "反讽指向重要共同边界原文。"),
    _case("v3-te026", "mixed_language_history", "之前那个 meetup 我们定在 where 来着，我只记得要带书。", "retrieve", "混合语中旧地点是明确缺失项。"),
    _case("v3-te027", "plan_recall", "我之前说申请截至是哪天，别把截止和面试的日子混了。", "retrieve", "错字不改变多个重要旧日期需核对。"),
    _case("v3-te028", "local_vs_older", "这四句说的是今天，我问的还是以前那套分配，哪部分是归我管的？", "retrieve", "近期完整但只涉及当前安排，旧分工仍缺失。", (("user", "今天我负责贴标签。"), ("assistant", "另一位同学负责打包。"), ("user", "新安排就是这样。"), ("assistant", "今天各做各的部分即可。"))),
    _case("v3-te029", "creative_history", "继续我存过的那章漫画台本，不要把旧角色的误会改没了。", "retrieve", "创作依赖未知旧台本和关系来源。"),
    _case("v3-te030", "provided_history_gap", "只留下半句‘我会等到最后’，这是你还是我说的，在哪次谈话里？", "retrieve", "残片不足以确认作者和旧事件。"),
    _case("v3-te031", "historical_comparison", "现在不那么怕向人求助了，我第一次跟你谈这个的时候是什么样？", "retrieve", "当前变化加明确旧反应比较。"),
    _case("v3-te032", "promise_scope", "你答应陪我整理资料是到这个月底吗，还是只陪第一周？", "retrieve", "旧承诺期限不明，不能从二选一推断。"),
    _case("v3-te033", "task_history", "给报名表补我以前发过的获奖信息，奖项名一个字也别变。", "retrieve", "精确个人旧资料是任务必需来源。"),
    _case("v3-te034", "negated_recall", "我没说过自己喜欢被叫那个名字吧？你找找依据，别只说印象里。", "retrieve", "需要验证旧边界来源，否定句不是跳检索授权。"),
    _case("v3-te035", "ambiguous_dependency", "那他也能这样吗？", "retrieve", "人物、行为和类比依据均无近邻定位。"),
    _case("v3-te036", "timeline", "第一次提到那笔积蓄和说终于存够了，两段之间有多久？", "retrieve", "多个历史来源及时间差缺失。"),
    _case("v3-te037", "saved_history", "那段我叫你一定保留的鼓励，想再读一次，完整的别浓缩。", "retrieve", "明确要求读回保存的独立旧原文。"),
    _case("v3-te038", "implicit_recall", "手又停在那扇窗口前了，跟当时你说‘可以不回头’的时候一样。", "retrieve", "部分引用无法填补具体旧事件及重要含义。"),
    _case("v3-te039", "local_context_insufficient", "这一步结束后要找哪个人？就是我原先跟你提的负责人，不是现在这位。", "retrieve", "近期流程有下一步但未提供指定更早人物。", (("assistant", "今天先把申请签好，签完要交给负责人。"),)),
    _case("v3-te040", "mixed_negation", "旧矛盾别 whole replay，我只想确认咱们那时有没有答应互留一天冷静。", "retrieve", "混合语拒绝重演仍保留旧关系约定核对。"),
)
# fmt: on

CASES = _TRAIN + _VALIDATION + _TEST


def validate(cases):
    """Reject schema drift without printing any example's content."""
    if not isinstance(cases, (list, tuple)) or not cases:
        raise ValueError("cases must be a non-empty JSON-compatible sequence")
    seen = set()
    for case in cases:
        if not isinstance(case, dict) or set(case) != FIELDS:
            raise ValueError("unexpected case fields")
        if any(
            not isinstance(case[name], str) or not case[name].strip()
            for name in ("id", "category", "text", "reason")
        ):
            raise ValueError("case strings must be non-empty")
        if case["id"] in seen:
            raise ValueError("case IDs must be unique")
        seen.add(case["id"])
        if case["gold"] not in ("present", "retrieve") or case["split"] not in SPLITS:
            raise ValueError("unknown gold route or split")
        prefix = {"train": "v2-tr", "validation": "v3-va", "test": "v3-te"}[
            case["split"]
        ]
        if not case["id"].startswith(prefix):
            raise ValueError("ID and split disagree")
        context = case["recent_context"]
        if not isinstance(context, list) or len(context) > 4:
            raise ValueError("recent context must contain at most four messages")
        for message in context:
            if (
                not isinstance(message, dict)
                or set(message) != {"role", "content"}
                or message["role"] not in ("user", "assistant")
                or not isinstance(message["content"], str)
                or not message["content"].strip()
            ):
                raise ValueError("invalid recent context message")
    json.dumps(cases, ensure_ascii=False, allow_nan=False)


def cases_for_split(split):
    if split not in SPLITS:
        raise ValueError("unknown split")
    return json.loads(
        json.dumps(
            [case for case in CASES if case["split"] == split],
            ensure_ascii=False,
            allow_nan=False,
        )
    )


def split_statistics():
    """Metadata only; old holdouts are absent from the combined case bank."""
    result = {}
    for split in SPLITS:
        counts = Counter(case["gold"] for case in CASES if case["split"] == split)
        result[split] = {
            "count": sum(counts.values()),
            "present": counts["present"],
            "retrieve": counts["retrieve"],
        }
    return result


validate(CASES)
