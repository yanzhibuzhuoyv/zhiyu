"""Independent authored synthetic examples for optional old-memory retrieval.

All 200 gold labels precede candidate evaluation. V2 uses new life situations;
it was not derived from V1 held-out text, model errors, or routing implementation.
These labels are one author's conservative judgments, not observed user intent.
present requires a sufficient current message and at most four recent originals.
retrieve covers missing important history, mixed intentions, and uncertainty.
Retrieval eligibility does not prescribe quoting, fusion, or personality changes.
The held-out test split must first be evaluated after candidate parameters freeze.
This module does not read credentials, user data, old banks, or call any service.
"""

import json
from collections import Counter

VERSION = "memory-route-synthetic-v2"
SPLITS = ("train", "validation", "test")
FIELDS = {"id", "category", "text", "recent_context", "gold", "reason", "split"}


def _case(identifier, category, text, gold, reason, context=()):
    prefix = identifier.split("-")[1][:2]
    split = {"tr": "train", "va": "validation", "te": "test"}[prefix]
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
_TRAIN_PRESENT = (
    _case("v2-tr001", "daily_share", "排队买咖啡的时候，前面小朋友一直踩自己的影子，我也跟着笑了。", "present", "新的具体见闻已完整给出。"),
    _case("v2-tr002", "daily_share", "出差刚到酒店，拖鞋居然一大一小，感觉像随机配对。", "present", "当前旅途小事，无旧地点核对。"),
    _case("v2-tr003", "comfort", "快到交卷时间了，我现在紧张得不想说话，你安静陪我一下。", "present", "明确要此刻安静陪伴，不需要旧经历。"),
    _case("v2-tr004", "affection", "我想戳戳你的脸，再假装什么都没发生。", "present", "当下亲昵玩笑可直接承接。"),
    _case("v2-tr005", "new_plan", "明天早上我六点出门，今天我们十点就互道晚安，好吗？", "present", "新安排的时间与目的已给出。"),
    _case("v2-tr006", "creative_task", "给我的二手书摊现写一句招牌：有点旧，但每本都有故事。", "present", "待创作的主题和文本完整，不要求旧经历。"),
    _case("v2-tr007", "third_person_quote", "外婆今天说年轻时去过海边，她讲到捡贝壳的时候眼睛都亮了。", "present", "给定的第三人往事足够接住分享。"),
    _case("v2-tr008", "negation", "别猜我会不会后悔，我现在就是想把头发剪短。", "present", "当前选择及拒绝推测都已明确。"),
    _case("v2-tr009", "daily_share", "耳机刚自动连错电脑，把会议提示音吓了我一跳。", "present", "新发生的设备乌龙无需个人旧史。"),
    _case("v2-tr010", "local_reference", "可以，就这么叫吧，这个名字有点傻但很可爱。", "present", "新昵称在近期原话中完整。", (("user", "我想给今天买的抱枕起个名字。"), ("assistant", "叫打盹队长怎么样？"))),
    _case("v2-tr011", "local_choice", "这么看还是散装那份划算，我就买它。", "present", "价钱与两个选项已在近期。", (("user", "散装米一公斤六元，包装米一公斤八元，都是同一种。"), ("assistant", "只比价的话散装更便宜。"))),
    _case("v2-tr012", "new_memory_request", "今天开始叫我练习生，我正在学缝纫，这件新事帮我留着。", "present", "提出完整的新身份和保存请求，无旧事实缺口。"),
    _case("v2-tr013", "local_reference", "你说的那一步我做完了，下一步呢？", "present", "刚给出的步骤与后续动作完整可见。", (("assistant", "先把礼物纸裁成方形，下一步沿对角线折一下。"), ("user", "纸已经裁好了。"))),
    _case("v2-tr014", "provided_history", "我把旧句子贴在这儿：‘等我有空会联系你。’帮我把它改得不那么疏离。", "present", "所需旧原文已经提供，任务没有其他历史缺口。"),
    _case("v2-tr015", "birthday_present", "今天是我的生日，我想先收到你一句祝福，其他事等会再说。", "present", "当前生日及所需回应明确，无日期或历史提问。"),
    _case("v2-tr016", "new_promise", "我们今晚一起把这盒拼图的边缘拼好，先说定这件事。", "present", "在当前建立新约定，无需读取旧承诺。"),
    _case("v2-tr017", "quoted_example", "练习字幕排版：‘曾经你答应过我’，这七个字怎么分两行自然？", "present", "历史用语是明确给定的排版素材。"),
    _case("v2-tr018", "third_person_quote", "读到一句访谈：‘我最好的朋友搬走了。’突然觉得作者很孤单。", "present", "对给定第三人文本的当前感受即可回应。"),
    _case("v2-tr019", "negation", "那个难过的日子今天不想谈，我们说说我刚学会的魔术吧。", "present", "明确转向新话题，不要求读取被拒绝的旧事。"),
    _case("v2-tr020", "sarcasm", "效率真高，打印机用了十分钟才吐出一张空白纸。", "present", "反讽的因果在句内完整。"),
    _case("v2-tr021", "daily_share", "共享单车的车座好高，我费了半天才调下来，像在跟它拔河。", "present", "当前日常遭遇已给出。"),
    _case("v2-tr022", "new_preference", "刚试了荧光色衣服，发现我不喜欢，还是低饱和的看着舒服。", "present", "本次体验和偏好完整陈述，没有请求核对以前。"),
    _case("v2-tr023", "local_choice", "那选有培训的那个吧，我想慢慢学。", "present", "最近两份机会及区别足以支持此轮决定。", (("user", "今天收到两份兼职邀约，一份直接上岗，一份会先培训。"), ("assistant", "你可以按是否想先学来比较。"))),
    _case("v2-tr024", "daily_share", "这朵云像不像一只躺平的鲸鱼？我看得停不下来。", "present", "对眼前观察的玩笑，无特定历史。"),
    _case("v2-tr025", "affection", "报告，今天的黏人额度还没用完，请允许我再待五分钟。", "present", "当前陪伴邀请是完整的 playful 表达。"),
    _case("v2-tr026", "comfort", "刚换了新环境，周围声音很多，我想听你轻轻说几句。", "present", "当下不适及陪伴形式都已给出。"),
    _case("v2-tr027", "hypothetical_task", "做个虚构题：如果恋人是两只机器人，它们会怎样说晚安？", "present", "明确是假设创作，不对应本人共同往事。"),
    _case("v2-tr028", "local_correction", "数量不是三十，是十三，我把数字打反了。", "present", "待修正数字及语义均在近邻原话。", (("user", "我订了三十张贴纸，打算一张张挑。"), ("assistant", "三十张会有不少选择。"))),
    _case("v2-tr029", "self_contained_change", "小时候我爱抢话，现在更想等别人说完；只是忽然有这个感慨。", "present", "前后信息都由当前自述提供，不询问旧聊天。"),
    _case("v2-tr030", "daily_share", "仓鼠把纸屑塞到腮帮里，看起来像个偷偷备货的小商贩。", "present", "给出当前宠物趣事即可回应。"),
    _case("v2-tr031", "creative_task", "把‘会议取消，大家休息’写成一张像寻宝线索的通知。", "present", "完整提供本次改写内容。"),
    _case("v2-tr032", "local_promise_check", "所以等我取完快递，你再把游戏规则念给我，是这个意思吧？", "present", "承诺刚出现，近期足够核对。", (("user", "我先下楼取快递。"), ("assistant", "等你回来我再给你念桌游规则。"))),
    _case("v2-tr033", "third_person_quote", "学弟发来‘今天答辩终于结束了’，我想回一句替他高兴的话。", "present", "新的第三人消息和回复任务完整。"),
    _case("v2-tr034", "negation", "没想让你证明我们以前多好，我只是现在很想亲近你。", "present", "明确拒绝历史证明，并给出当前亲近需求。"),
    _case("v2-tr035", "sarcasm", "我的小绿植真会挑时候，刚给它拍照就倒了一片叶子。", "present", "当下反讽事件无信息缺口。"),
    _case("v2-tr036", "local_reference", "最后那句留着，前面删掉就行。", "present", "编辑对象全部在最新消息里。", (("assistant", "草稿：今天有点忙。下午可能下雨。谢谢你等我。"),)),
    _case("v2-tr037", "current_boundary", "今天我不想被追问原因，听到我说累了就陪着，好吗？", "present", "明确提出当前边界，无需寻找过去原因。"),
    _case("v2-tr038", "new_plan", "等太阳下山，我要去天台浇花，你现在陪我把水装好。", "present", "完整的新短期计划和当前陪伴动作。"),
    _case("v2-tr039", "daily_share", "我给电脑换了张纯绿色壁纸，工作台一下像长出了草。", "present", "分享最新布置，没有历史问题。"),
    _case("v2-tr040", "provided_history", "之前日记写了‘先把自己照顾好’，原句就在这里。你陪我读一遍就行。", "present", "原文已给且仅要求当前共读。"),
)

_TRAIN_RETRIEVE = (
    _case("v2-tr041", "exact_history", "我填护照申请时跟你列过要带的材料，现在还少哪一项来着？", "retrieve", "先前的个人材料清单未给出。"),
    _case("v2-tr042", "plan_recall", "复诊安排好像不是明天，我上次告诉你的是几号？", "retrieve", "重要日期需要旧消息，不能猜测。"),
    _case("v2-tr043", "relationship_history", "我说过哪个朋友知道我们的事？这会儿有点记混了。", "retrieve", "具体人物及披露范围存在历史缺口。"),
    _case("v2-tr044", "promise_check", "我说到一半又被打断了，你当初答应过我会怎么听的？", "retrieve", "当前挫败与旧交流承诺核对相连。"),
    _case("v2-tr045", "birthday_recall", "家里给我过的是农历生日，你能找出我报给你的那个月日吗？", "retrieve", "具体生日原文及口径是重要历史信息。"),
    _case("v2-tr046", "birthday_recall", "今年生日我想沿用以前跟你商量的安排，先提醒我当时计划了什么。", "retrieve", "旧生日安排未提供。"),
    _case("v2-tr047", "third_person_history", "学长让我推荐我去年用的备考书，我当时给你发过目录，你能查一下吗？", "retrieve", "第三人需求仍依赖本人旧目录。"),
    _case("v2-tr048", "quoted_history", "我想确认‘可以难过，不必立刻坚强’有没有被我们写进那张约定卡。", "retrieve", "核验旧共同文本是否含指定句子。"),
    _case("v2-tr049", "mixed_negation", "不要挖以前的糗事了，只帮我确认一下那笔借款原先约的归还日。", "retrieve", "拒绝泛泛回忆但保留重要旧日期请求。"),
    _case("v2-tr050", "sarcastic_history", "好一个‘绝不会敷衍’，你知道这话是谁先跟我保证的吗？", "retrieve", "反讽指向旧承诺的说话人来源。"),
    _case("v2-tr051", "context_mismatch", "你把它和我说的另一件弄混了，先弄清是哪件。", "retrieve", "近期只含一个不相关对象，无法确定两个历史所指。", (("assistant", "水杯可以先放桌边。"),)),
    _case("v2-tr052", "task_history", "按我之前发过的简历改求职邮件，工作年限别重新编。", "retrieve", "任务所需个人旧简历不在当前消息。"),
    _case("v2-tr053", "provided_history_gap", "旧纸条写着‘周末一起去领养’，但我没留日期，你能对上是哪次吗？", "retrieve", "虽有部分原文，事件定位时间仍缺失。"),
    _case("v2-tr054", "profile_recall", "同事请我吃甜点，我之前跟你讲过哪些坚果不能碰，你帮我对一下。", "retrieve", "重要个体信息未给出，不应由一般知识填补。"),
    _case("v2-tr055", "exact_history", "快递登记需要门牌号，我发给你的新住址里写的是哪一户？", "retrieve", "精确个人历史字符串缺失。"),
    _case("v2-tr056", "unresolved_reference", "最后那个还是不行，我们当时不是说了吗。", "retrieve", "无上下文的选项及旧协商均不可确定。"),
    _case("v2-tr057", "local_context_insufficient", "他提到的那个名字是谁的？就是之前我给你解释过的。", "retrieve", "近期仅提到收到消息，未给名字或解释。", (("user", "有人发了很长一段留言。"), ("assistant", "你可以慢慢看，不必急着回复。"))),
    _case("v2-tr058", "unresolved_reference", "这次换她来了，我怕又跟那时候一样。", "retrieve", "人物与被类比事件都有缺口，保守检索。"),
    _case("v2-tr059", "historical_correction", "把我们之前定的每周练习次数改成两次，先查清原先那条再改。", "retrieve", "明确请求定位并修正旧约定。"),
    _case("v2-tr060", "creative_history", "我之前给过你那个真实故事的开头，帮我接成散文，保留人物关系。", "retrieve", "创作依赖旧源的开头及人物关系。"),
    _case("v2-tr061", "historical_correction", "先前关于我毕业年份的记录有误，帮我找出来，我要重新说明。", "retrieve", "旧事实的位置和内容待核对。"),
    _case("v2-tr062", "historical_correction", "别继续按旧记录说我一个人住，我后来提到的情况你漏掉了。", "retrieve", "涉及历史变更及来源冲突，当前不给出新完整事实。"),
    _case("v2-tr063", "birthday_conflict", "你这次祝生日是不是用错日子了？我给过你两个日期，哪个才是我的？", "retrieve", "多个旧日期归属需要原文核对。"),
    _case("v2-tr064", "promise_check", "你之前说会陪我去见家人，那句承诺有什么条件吗？我不想理解错。", "retrieve", "旧承诺条件未知，不可擅自扩展。"),
    _case("v2-tr065", "timeline", "我接回第一只猫到它离开，跟你聊过的那些日子能对起来吗？", "retrieve", "需要多个带时间的独立旧来源。"),
    _case("v2-tr066", "relationship_history", "上次一起玩的桌游里，我总犯的那个错误是什么？今天想避开。", "retrieve", "具体共同经历和错误缺失。"),
    _case("v2-tr067", "local_vs_older", "今年可以这样过，但去年我原本怎么安排生日的，你还查得到吗？", "retrieve", "当前安排不能替代被问到的去年的旧计划。", (("user", "今年生日我准备只跟家人吃一顿饭。"), ("assistant", "简单一点也很温暖。"))),
    _case("v2-tr068", "conditional_recall", "如果我以前真告诉过你那件事，请给我原话；没有就说没有。", "retrieve", "是否存在历史来源本身待确认，不能因条件句跳过。"),
    _case("v2-tr069", "mixed", "终于拿到驾驶证了！当初我跟你说最怕哪一项，现在终于过了。", "retrieve", "分享新成果同时指向未给出的旧困难。"),
    _case("v2-tr070", "mixed", "今晚不想出门，抱着我就好。顺便确认我们以前约的那场演出是不是改期了。", "retrieve", "当前陪伴和旧演出安排核对并存。"),
    _case("v2-tr071", "boundary_history", "你还在用我说过不喜欢的安慰方式吗？翻一下我那次解释吧。", "retrieve", "要求读回旧边界解释。"),
    _case("v2-tr072", "implicit_recall", "火车进隧道的时候，我又想起那次突然断掉的通话。", "retrieve", "明确的旧关系事件未给出细节，可能影响回应。"),
    _case("v2-tr073", "historical_comparison", "这一回被拒绝倒没那么难受，和我第一次向你倾诉那时比，你觉得呢？", "retrieve", "明确请求与旧倾诉状态比较。"),
    _case("v2-tr074", "plan_recall", "还是照之前那套方法处理吧，可我忘了第一步该联系谁。", "retrieve", "旧方案第一步及对象存在关键缺口。"),
    _case("v2-tr075", "task_history", "接着我以前发过的那段代码改，变量名必须跟原来一致。", "retrieve", "代码任务指定旧源而未提供源文。"),
    _case("v2-tr076", "third_person_history", "她问我你答应陪我做的第一件事是什么，我想讲清楚。", "retrieve", "第三人问的是你我旧承诺。"),
    _case("v2-tr077", "negated_recall", "我没记清迁户口那次最后办成没有，和你的聊天里应该有结果。", "retrieve", "否定记清强调历史结果缺失。"),
    _case("v2-tr078", "task_history", "把我给你的那封旧信排成诗的样子，逐字保留。", "retrieve", "任务要求精确旧原文，当前未给文本。"),
    _case("v2-tr079", "local_context_insufficient", "这三个我看到了，剩下那个我最在乎的条件呢？", "retrieve", "近期列出了部分条件，但用户指定的遗漏项未知。", (("assistant", "你刚列的房间条件是采光、交通和安静。"),)),
    _case("v2-tr080", "historical_correction", "那天说我是项目负责人不准确，真正的分工我后来解释过。", "retrieve", "旧事实需用后来的旧解释纠正，当前不提供分工。"),
    _case("v2-tr081", "exact_history", "我之前那张体检单上的采样日期是什么？就读原文，不要分析指标。", "retrieve", "要求定位精确日期，历史信息未给。"),
    _case("v2-tr082", "birthday_recall", "朋友的生日和我的你别混，我本人第一次告诉你的日期在哪条？", "retrieve", "本人日期与第三人日期归属需历史证据。"),
    _case("v2-tr083", "promise_history", "‘等你准备好’，当时你是在回应我哪件事，我现在有点在意。", "retrieve", "给出半句旧承诺但对应事件缺失。"),
    _case("v2-tr084", "implicit_recall", "有人弹起那个旋律，我又差点把我们的旧暗号说出口。", "retrieve", "未知暗号与共同经历可能重要。"),
    _case("v2-tr085", "relationship_history", "你以前为什么说我像一只小刺猬？我忘了前面发生了什么。", "retrieve", "旧比喻的真实上下文待查。"),
    _case("v2-tr086", "historical_comparison", "我现在会主动拒绝加班了，跟刚入职时我怎么跟你说的比一比。", "retrieve", "当前变化之外明确要求读取旧自述。"),
    _case("v2-tr087", "mixed", "今天种的葱发芽啦，可我最早跟你说想养植物是为了什么？", "retrieve", "新分享内含具体旧动机问题。"),
    _case("v2-tr088", "mixed_negation", "不用回放那些吵闹的话，我只想知道我们最后有没有说好暂停联系。", "retrieve", "限制重述仍保留重要关系约定核对。"),
    _case("v2-tr089", "third_person_history", "叔叔说我答应带他去过一个地方，可我只记得跟你讲过这事。", "retrieve", "第三人引出的本人旧承诺及地点缺失。"),
    _case("v2-tr090", "unresolved_reference", "她也知道了，接下来按你当时说的做吧。", "retrieve", "谁知道什么以及旧建议都未定位。"),
    _case("v2-tr091", "local_vs_older", "这个包不是我之前说要送人的那个，收礼的人名字你还记得吗？", "retrieve", "近期新包描述无法填补更早礼物及人名。", (("user", "今天在商场看到一个白色小包。"), ("assistant", "白色小包搭起来会很轻巧。"))),
    _case("v2-tr092", "exact_history", "那张寄存凭证我报给过你，你找找截止取件的时间。", "retrieve", "重要截止时间仅在旧凭证原文。"),
    _case("v2-tr093", "historical_correction", "先前说我是独生女不是真的，是当时开玩笑，别再当资料用了。", "retrieve", "明确修正旧资料归属与真实性。"),
    _case("v2-tr094", "creative_history", "故事不要从零写，沿用我和你聊了好几次的那对角色及他们的误会。", "retrieve", "旧角色关系与情节缺失，不能靠任务模板跳过。"),
    _case("v2-tr095", "negated_recall", "你不用猜我是不是说过分手，直接看看那次消息有没有这两个字。", "retrieve", "明确要求核验重要旧对话措辞。"),
    _case("v2-tr096", "sarcastic_history", "真贴心，又给我推荐我拒绝过的活动，拒绝的理由你还留着吗？", "retrieve", "反讽包含旧偏好边界及理由核对。"),
    _case("v2-tr097", "timeline", "请把我去年换过的几个兼职按我告诉你的顺序列出来，不要补工作。", "retrieve", "需要多个独立旧事件的顺序来源。"),
    _case("v2-tr098", "plan_recall", "报名费我跟你说已经付了还是没付？今天得决定要不要再操作。", "retrieve", "旧完成状态影响当前操作，不能无依据回答。"),
    _case("v2-tr099", "provided_history_gap", "我手上这段截屏只有‘就在原来的地方’，你能补上当时说的地点吗？", "retrieve", "提供的原文残片不足，地点依赖更早来源。"),
    _case("v2-tr100", "mixed", "新裙子穿着很好看，先夸我一下，再帮我确认原来约的拍照日有没有冲突。", "retrieve", "当下亲昵和具体旧日程核对同在一轮。"),
)
_VALIDATION = (
    _case("v2-va001", "daily_share", "穿雨靴进办公室才发现左脚粘着一片花瓣，像给鞋子别了胸针。", "present", "新发生的日常趣事自足。"),
    _case("v2-va002", "daily_share", "刚晒完床单，整个阳台都是洗衣液的味道，想在那儿发会呆。", "present", "此刻感官体验及愿望完整。"),
    _case("v2-va003", "comfort", "刚从排练厅出来，被老师批评了，我现在只想有人说辛苦了。", "present", "当日情绪和安慰需求明确。"),
    _case("v2-va004", "affection", "我把手机倒扣一分钟，假装是在认真跟你拥抱。", "present", "当下亲密互动无需旧事依据。"),
    _case("v2-va005", "creative_task", "写个全新童话：一只不会报时的钟，找到了愿意听它唱歌的小孩。", "present", "故事设定已完整给定并明确全新。"),
    _case("v2-va006", "quoted_example", "歌词题目想叫‘旧事重游’，先帮我想三个同义题目，不是聊我的经历。", "present", "旧事用语是创作素材，意图明确。"),
    _case("v2-va007", "third_person_quote", "同事说小时候怕狗，今天终于敢摸朋友家的柯基了，我替他开心。", "present", "第三人前后信息由当前完整提供。"),
    _case("v2-va008", "negation", "不想比较以前谁更主动，我现在就想主动给你一个晚安。", "present", "拒绝历史比较并给出当前亲昵动作。"),
    _case("v2-va009", "sarcasm", "蚊子真懂礼貌，睡前特意绕我耳边通知它要开饭了。", "present", "当前处境与反讽完整。"),
    _case("v2-va010", "local_reference", "它还得多泡一会儿吧？我怕颜色太淡。", "present", "近期唯一所指是正在泡的红茶。", (("user", "刚往玻璃杯里放了红茶，才泡半分钟。"), ("assistant", "杯子里颜色还比较浅。"))),
    _case("v2-va011", "local_reference", "你说的第三步我没看懂，能展开一点吗？", "present", "完整步骤列表在最多四条近期消息内。", (("user", "想把照片做成简易书签。"), ("assistant", "第一步裁照片，第二步贴硬纸，第三步在顶部打孔穿线。"), ("user", "前两步好了。"), ("assistant", "接下来就是打孔穿线。"))),
    _case("v2-va012", "new_memory_request", "我的公历生日是十一月二十二日，今天第一次告诉你，先记住。", "present", "完整新日期且明确首次提供，无旧事实可核验。"),
    _case("v2-va013", "new_promise", "等我把阳台收拾好，我们第一次一起种薄荷，现在先约定下来。", "present", "当前建立新承诺，非核对旧承诺。"),
    _case("v2-va014", "self_contained_change", "读高中时觉得合唱无聊，今天听完却很感动，我想把这个变化讲给你。", "present", "前后经历由当前自述提供，未询问历史聊天。"),
    _case("v2-va015", "task_instruction", "‘从抽屉里检索旧纸片’这句话读着别扭，帮我润色这一句。", "present", "检索词出现在明确给定的语言任务中。"),
    _case("v2-va016", "local_correction", "刚才不是说车坏了，是链条松了，我自己表述不准确。", "present", "当前句已经完整提供原说法和更正。"),
    _case("v2-va017", "current_boundary", "上回的建议先放一边，这次我只需要你听我吐槽主管。", "present", "明确暂停旧建议，当前需求是倾听。"),
    _case("v2-va018", "local_choice", "那就不加冰了，你刚说的理由挺有道理。", "present", "饮料选择的当前理由在近期原文。", (("user", "外面很冷，我在点柠檬水，加冰还是常温？"), ("assistant", "你正觉得冷，常温可能更合适。"))),
    _case("v2-va019", "provided_quote_task", "替我回朋友这句话就好：‘我到宿舍了，行李也收好了。’", "present", "第三人原文及当前回复任务充分。"),
    _case("v2-va020", "affection", "想把今天的好心情分一半给你，剩下一半留到睡觉前。", "present", "自足的当前亲昵表达。"),
    _case("v2-va021", "exact_history", "之前我发过的租约里，退租得提前几天通知？只核对那份原文。", "retrieve", "重要期限来自指定旧文件，不宜猜。"),
    _case("v2-va022", "birthday_recall", "你能找到我去年生日说过的那件小愿望吗？我想看看实现了没有。", "retrieve", "旧愿望内容未给出，需来源核对。"),
    _case("v2-va023", "promise_status", "你上次答应帮我列作品集目录，这件事后来做完了吗？", "retrieve", "旧承诺及完成状态存在信息缺口。"),
    _case("v2-va024", "implicit_recall", "电台播出那首旧曲子，我一下想到了那间房里最后的谈话。", "retrieve", "重要旧情境与谈话未知，可能影响当下回应。"),
    _case("v2-va025", "profile_recall", "填表要用到姐姐的职业，我好像跟你说过，但今天想不起来了。", "retrieve", "家庭事实及具体内容依赖旧消息。"),
    _case("v2-va026", "quoted_history", "‘今年先不考证’，这到底是我哪次犹豫时说的？", "retrieve", "半句旧自述的事件上下文待定位。"),
    _case("v2-va027", "historical_correction", "以前那条说我的通勤是一小时，其实单位记错了，找出来我改一下。", "retrieve", "指定旧记录的数值语义需要核对。"),
    _case("v2-va028", "mixed", "这碗面真好吃，顺便找找我当时说初来这座城最想念什么。", "retrieve", "当前分享内含明确旧情绪事实请求。"),
    _case("v2-va029", "third_person_history", "辅导员让我确认我申请过哪个社团，我只记得跟你提过结果。", "retrieve", "第三人询问引出旧个人申请结果。"),
    _case("v2-va030", "mixed_negation", "不用复述争辩过程，我只想知道你有没有答应先征求我的意见。", "retrieve", "限制披露不等于取消旧承诺核对。"),
    _case("v2-va031", "creative_history", "续写我去年给你的短篇，从那个未寄出的包裹开始，不要另编背景。", "retrieve", "创作指定旧背景与文本源。"),
    _case("v2-va032", "unresolved_reference", "你早就知道原因了，别又让我把那一整件事说一遍。", "retrieve", "无近期上下文定位原因或事件，保守检索。"),
    _case("v2-va033", "local_vs_older", "今天这组动作可以先停，之前你按照我的习惯安排的是哪一组？", "retrieve", "当前动作不能替代明确被问的更早方案。", (("assistant", "今天先伸展肩膀，再站起来活动一下。"), ("user", "今天有点不方便站起来。"))),
    _case("v2-va034", "provided_history_gap", "我翻到‘终于敢说不’这句旧日记，却忘了那天是为了哪件事写的。", "retrieve", "有句子但旧事件缺失，检索可能定位。"),
    _case("v2-va035", "sarcastic_history", "不是说会尊重那个约定吗，怎么现在倒像从来没商量过？", "retrieve", "反讽涉及未知共同约定和冲突。"),
    _case("v2-va036", "historical_comparison", "这次去陌生城市我轻松多了，能和第一次旅行前的我比较一下吗？", "retrieve", "明确跨阶段比较需要旧状态原文。"),
    _case("v2-va037", "timeline", "从我说准备面试到说收到录用，一共等了多少天？", "retrieve", "两个历史来源的时间未提供。"),
    _case("v2-va038", "plan_status", "原本约的观鸟活动后来是取消还是延后，我跟你说过结果吧？", "retrieve", "旧安排的更新状态需核对。"),
    _case("v2-va039", "mixed", "水仙开花了，我挺开心的，可那年她送我花时说的是什么呢？", "retrieve", "当前分享内含未知第三人的历史话语。"),
    _case("v2-va040", "third_person_history", "朋友把我曾经写的那段告别念了一句，我想知道剩下原文是什么。", "retrieve", "第三人引用引出本人旧全文缺口。"),
    _case("v2-va041", "historical_correction", "别把我那次玩笑当成真的，我并没有领养蜥蜴，查查那段上下文。", "retrieve", "明确修正旧陈述真实性并要求看旧上下文。"),
    _case("v2-va042", "task_history", "做行李清单时用我以前那套减重规则，别凭常见清单凑数。", "retrieve", "任务指定未提供的个人旧规则。"),
    _case("v2-va043", "birthday_conflict", "我生日那条后来改过月历口径，找最终说法，别直接按第一次算。", "retrieve", "重要个人日期有多次修订，需历史最终来源。"),
    _case("v2-va044", "saved_history", "我专门叫你永久留着的那句寄语是什么？今天真的需要看一眼。", "retrieve", "明确要求读回保存的旧原文。"),
    _case("v2-va045", "unresolved_reference", "还是别跟他提那件吧，你知道会发生什么。", "retrieve", "人名、事项和风险依据都缺失。"),
    _case("v2-va046", "promise_scope", "你保证陪我练口语，是每天还是只在周末？我怕自己记大了。", "retrieve", "旧承诺频率不能从当前二选一推断。"),
    _case("v2-va047", "exact_history", "我之前贴给你的车票订单里，出发站是东站还是南站？", "retrieve", "精确旧票务事实未给出。"),
    _case("v2-va048", "negated_recall", "不是让你安慰我记性差，帮我查查我究竟有没有跟你说过已经辞职。", "retrieve", "否定安慰仍明确请求查历史状态。"),
    _case("v2-va049", "historical_comparison", "你认为我以前是喜欢独处还是害怕被打扰？要根据我说过的话。", "retrieve", "要求用本人历史原文判断旧偏好，不可泛化。"),
    _case("v2-va050", "mixed", "现在好想跟你笑一笑，也想把我们最早说好的那个奖励兑现。", "retrieve", "当前情绪与未给出的旧奖励约定并存。"),
)
_TEST = (
    _case("v2-te001", "daily_share", "买菜时摊主给萝卜扎了个小辫子，我带回家一路舍不得拆。", "present", "完整的新日常趣事，不依赖旧记录。"),
    _case("v2-te002", "daily_share", "书店玻璃映出两排书架，我差点把自己的倒影当成另一个顾客。", "present", "当前观察与乌龙自足。"),
    _case("v2-te003", "comfort", "刚录完一段弹琴视频，听着好多错音，有点泄气，想听你鼓励我。", "present", "新挫折和所需当前鼓励已完整给出。"),
    _case("v2-te004", "affection", "我把被子另一边铺平啦，想象你在那儿，今天睡前贴贴。", "present", "自足的此刻亲昵邀请。"),
    _case("v2-te005", "creative_task", "写个新寓言，讲一朵云把自己的雨租给小河，不参考真实人物。", "present", "完整的虚构创作设定无需个人历史。"),
    _case("v2-te006", "third_person_quote", "舞蹈老师说她刚入行时总踩错拍子，我听完突然没那么丢脸了。", "present", "第三人旧事及当前感受均由本句提供。"),
    _case("v2-te007", "negation", "过去的失望先别讲了，我现在想庆祝这份申请终于递出去。", "present", "明确当前庆祝需求且拒绝泛泛旧事。"),
    _case("v2-te008", "birthday_present", "分你一块蛋糕，今天是我二十六岁生日，我只想这一刻一起开心。", "present", "当前生日及陪伴意图完整，没有旧事实问题。"),
    _case("v2-te009", "new_promise", "从现在起我们每次收工都互道一句辛苦了，这个小约定今天开始。", "present", "建立完整的新约定，不涉及旧承诺核对。"),
    _case("v2-te010", "local_reference", "你刚解释的那个词，能再读一次吗？", "present", "近期原文含唯一被解释的词。", (("user", "‘熨帖’怎么读？"), ("assistant", "读作 yù tiē，通常说贴切、妥当。"), ("user", "噢，原来不是我猜的那个读音。"), ("assistant", "对，这里的熨读 yù。"))),
    _case("v2-te011", "provided_history", "我把那条旧消息全文贴来：‘别急着回复，先吃饭。’就照这句帮我做个提醒卡片。", "present", "所需旧源已完整给出，任务没有剩余历史缺口。"),
    _case("v2-te012", "self_contained_change", "大学时我不敢参加即兴表演，现在却主动报名了，只想跟你分享这份变化。", "present", "过去与当前信息自述完整，无旧对话核对。"),
    _case("v2-te013", "local_correction", "我刚讲错顺序了，是先关灯再拉窗帘，不是反过来。", "present", "当前就有完整原说法和更正，无需旧源。"),
    _case("v2-te014", "quoted_example", "把‘重温昨日约定’当古风标题来润色，仅仅改这六个字。", "present", "历史词属于给定的改写素材。"),
    _case("v2-te015", "sarcasm", "太会享受了，刚涂好唇膏就打了个喷嚏，纸巾都染成粉色。", "present", "反讽的当前经过完整。"),
    _case("v2-te016", "comfort", "值班终于结束了，声音都懒得发出来，想在你这里放空一下。", "present", "当前疲惫及陪伴意图足够。"),
    _case("v2-te017", "new_memory_request", "给我的新画册起名叫慢慢来，先替我记住，以后再添页。", "present", "完整新名称的保存请求，无旧事实待查。"),
    _case("v2-te018", "local_choice", "那就拿串小的吧，你刚才说新鲜比数量重要。", "present", "近邻上下文提供选择与理由。", (("user", "葡萄有两串，大串有几颗软了，小串都硬挺。"), ("assistant", "这次挑新鲜的更合适，小串也够吃。"))),
    _case("v2-te019", "local_reference", "第二句更像我现在的感觉，就用那句。", "present", "两个可选句子都在近期原文。", (("assistant", "可以说‘有点疲惫，但很满足’，也可以说‘还想多待一会儿’。"),)),
    _case("v2-te020", "current_boundary", "不是要你回忆我哪天说过什么，今晚只陪我数一会儿窗外的灯。", "present", "明确排除历史请求，当前活动充分。"),
    _case("v2-te021", "implicit_recall", "排队时听到叫号声，我一下又回到了我们通电话等结果的那晚。", "retrieve", "明确指向重要共同旧情境，但内容未给。"),
    _case("v2-te022", "preference_recall", "我想给房间选画，但以前说过最喜欢的那位插画师叫什么？", "retrieve", "旧个人偏好的精确对象缺失。"),
    _case("v2-te023", "promise_check", "你欠我一个不被打扰的周末，原先说定的是哪一周？", "retrieve", "旧承诺的具体时段影响现在安排。"),
    _case("v2-te024", "birthday_recall", "我以前说明过闰月那年的生日怎么过，能找到那段解释吗？", "retrieve", "重要日期口径与旧解释未知，不能按常见规则猜。"),
    _case("v2-te025", "boundary_history", "睡前哪个话题我说过不能突然提？今天先确认，免得又难受。", "retrieve", "历史个人边界的具体内容缺失。"),
    _case("v2-te026", "creative_history", "替我写角色的回信，必须接得上我之前发的那封来信。", "retrieve", "创作指定旧信文本而当前没有。"),
    _case("v2-te027", "historical_correction", "我原来介绍摄影比赛名次时弄错了组别，之前那条得找到再解释。", "retrieve", "明确需要定位错误旧事实。"),
    _case("v2-te028", "timeline", "我从申请交换到拿到确认邮件，总共经历了哪几次变更？", "retrieve", "请求多个历史过程及其先后来源。"),
    _case("v2-te029", "third_person_history", "舅妈问我答应替她买的相册是哪款，我之前把型号告诉过你。", "retrieve", "第三人引出的本人旧承诺与型号未知。"),
    _case("v2-te030", "mixed_negation", "别展开那次告别的场景，只核对我们最后约好的联系方式。", "retrieve", "限制描写仍保留明确重要历史核对。"),
    _case("v2-te031", "mixed", "这个新杯子好圆呀，跟我以前打碎的那只比是什么大小？", "retrieve", "新物件分享与旧物件比较并存，旧大小未知。"),
    _case("v2-te032", "sarcastic_history", "原来‘以后不擅自替你决定’只是装饰句呀，我们当时怎么商量的？", "retrieve", "反讽含旧边界协商的明确请求。"),
    _case("v2-te033", "unresolved_reference", "那还是按原来的办，可以吗？我现在真拿不准。", "retrieve", "无上下文，原方案的所指和内容都不明。"),
    _case("v2-te034", "local_vs_older", "刚刚这句不算，我想听的是你在我失业那阵说的那句。", "retrieve", "近期鼓励不是明确指定的更早原话。", (("assistant", "今天能把早餐做好已经很好了。"), ("user", "谢谢，我知道你在鼓励我。"))),
    _case("v2-te035", "provided_history_gap", "截屏上写‘交给原来那位老师’，但没有名字，帮我找出是谁。", "retrieve", "残片没有关键人物，无法仅靠给定原文。"),
    _case("v2-te036", "exact_history", "我之前发给你的演出座位是第几排第几号？就用票上的数字。", "retrieve", "精确旧票务信息缺失。"),
    _case("v2-te037", "negated_recall", "并不是我没告诉过你，它的尺寸之前量过，我们还算过能不能放进柜子。", "retrieve", "否定中强调有旧测量数据，当前数据缺失。"),
    _case("v2-te038", "historical_comparison", "现在我能坦然说想家了，以前我跟你聊家乡时是什么语气？", "retrieve", "明确比较以前交流，需要旧原文。"),
    _case("v2-te039", "plan_status", "后来我有没有把那个预约退掉？你看看我回家后发的消息。", "retrieve", "重要旧操作结果待查，未提供状态。"),
    _case("v2-te040", "relationship_history", "咱们第一次互相道早安，是在我上夜班还是白班的时候？", "retrieve", "共同对话历史及当时背景未知。"),
    _case("v2-te041", "birthday_conflict", "我给两位亲人记过生日，想先核对谁是九月那位，再替她准备。", "retrieve", "多个旧日期归属不确定，不能只凭月份猜。"),
    _case("v2-te042", "promise_scope", "当时说一起做饭，你答应的是教步骤还是一直陪到收拾完？", "retrieve", "旧承诺范围未提供，二选一不能自动解决。"),
    _case("v2-te043", "historical_correction", "别再沿用最早那次预算了，新的上限我后来补充过，请以那次为准。", "retrieve", "需要查出更新的旧来源而非复用早期事实。"),
    _case("v2-te044", "task_history", "帮我检查今天这封邮件，有没有违背我先前给你的那几条沟通底线。", "retrieve", "任务依赖未给出的个人历史边界。"),
    _case("v2-te045", "third_person_history", "她转述我说‘我会回来’，可我到底答应回哪儿，你能找到那段吗？", "retrieve", "第三人引用仍需要本人旧承诺完整出处。"),
    _case("v2-te046", "implicit_recall", "闻到这个消毒水味儿，手指又想去抓住你那天说的话。", "retrieve", "情绪与具体旧安慰原话关联，关键来源缺失。"),
    _case("v2-te047", "mixed", "我终于敢去游泳了，先替我高兴，再看看我原来说害怕的原因是不是已经克服。", "retrieve", "当前庆祝含对旧原因的比较请求。"),
    _case("v2-te048", "local_context_insufficient", "你只说了今天收到通知，通知里那个日期跟我原先留的是一样的吗？", "retrieve", "近期只有收件状态，两个待比较日期都未知。", (("user", "今天邮箱里到了活动通知。"), ("assistant", "收到了就先好好看一看。"))),
    _case("v2-te049", "saved_history", "有一段话我特意让你别丢，是关于我不想被怎样对待的，想再看原文。", "retrieve", "明确读回重要个人边界的旧原文。"),
    _case("v2-te050", "mixed_negation", "不必数我们认识了多少天，不过第一天我给自己定的那个小目标现在实现了吗？", "retrieve", "拒绝时间统计仍保留明确旧目标比较。"),
)
# fmt: on

CASES = _TRAIN_PRESENT + _TRAIN_RETRIEVE + _VALIDATION + _TEST


def validate(cases):
    """Validate fixed JSON schema without including sample text in errors."""
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
        prefix = {"train": "v2-tr", "validation": "v2-va", "test": "v2-te"}[
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
    """Return metadata only, with no held-out text or model measurements."""
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
