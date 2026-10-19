"""New independently authored synthetic splits, frozen before any model run.

Hidden history is an oracle-only annotation, NEVER classifier input. A retrieve
label means old facts can materially change a safe/reliable response, even when
the wording itself cannot reveal that. These adversarial cases expose limits.
No samples or annotations come from private conversations.
"""
from collections import Counter

VERSION = "native-int8-prefixed-route-research-v1"


def make(split, rows, context=(), hidden=""):
    return [{"id": f"{split}-{i + 1:03}", "split": split, "category": category,
        "text": text, "gold": gold, "recent_context": list(context),
        "hidden_history": hidden, "reason": reason}
        for i, (category, text, gold, reason) in enumerate(rows)]


def present(category, text):
    return (category, text, "present", "当下完整分享或明确自足任务，无需旧事实。")


def retrieve(category, text):
    return (category, text, "retrieve", "有旧事实缺口、混合需求或不明指代，保留检索。")


TRAIN = make("train", [
    present("daily", "刚把刚烤好的土豆拿出来，热气把眼镜都弄模糊了。"),
    present("daily", "地铁出口有个人拉小提琴，我停下听了半首曲子。"),
    present("daily", "今早我用粉笔在门口画了一颗歪歪的星星。"),
    present("daily", "这会儿洗衣机响得像起飞，笑死我了。"),
    present("daily", "今晚的云是淡紫色的，忍不住想给你形容一下。"),
    present("daily", "刚吃了一口冰西瓜，感觉暑气一下子散了。"),
    present("daily", "我把桌子擦干净了，现在心里也亮堂了。"),
    present("daily", "书店门口的橘猫用爪子拍我的鞋带，太可爱了。"),
    present("comfort", "今天有点委屈，先陪我坐几分钟，不用给建议。"),
    present("comfort", "我现在眼睛好酸，想闭着眼听你说两句温柔的话。"),
    present("affection", "让我在你怀里伸个懒腰，然后我们发会儿呆。"),
    present("affection", "现在就想亲一下你的额头，别躲呀。"),
    present("affection", "晚安，给你留个轻轻的小拥抱。"),
    present("new_fact", "刚接了一项新任务，截止时间是本周五下午四点。"),
    present("new_fact", "我才发现泡茶的水温低一点也很好喝。"),
    present("new_fact", "今天第一次试了滑板，落地的时候腿直抖。"),
    present("new_fact", "告诉你个新发现，我喜欢这家店现烤的红豆饼。"),
    present("negation", "别谈以前发生过什么啦，我只想分享现在闻到的花香。"),
    present("negation", "不用比较旧照片，就看看我这张刚拍的晚霞。"),
    present("negation", "不是在问往事，我只是说今天公交晚到了五分钟。"),
    present("third_party", "同学说他刚中了电影票，我替他高兴。"),
    present("third_party", "路人的小孩喊着想吃棉花糖，我听得也馋了。"),
    present("quotation", "电视里的角色问‘你还记得我吗’，演员表情真夸张。"),
    present("quotation", "这幅漫画写着‘一直等你’，配上猫咪挺有趣的。"),
    present("dialect", "今儿外头风可大了，我帽子差点飞走。"),
    present("dialect", "这碗面巴适得很，热乎乎的舒服。"),
    present("typo", "刚下班有点困困，陪窝坐会儿叭。"),
    present("sarcasm", "真是个‘优秀’的厨师，煎蛋翻面直接翻到地上了。"),
    present("task", "把‘今日阳光很好’改成一条可爱的文案，就围绕这句。"),
    present("task", "临时编个关于一只迷路小狐狸的故事，情节全部新编。"),
    retrieve("explicit", "我提过想报哪个舞蹈班来着，名称还记得吗？"),
    retrieve("explicit", "我们最开始约好用什么词互道晚安？"),
    retrieve("explicit", "你知道我爷爷的名字吗，先别猜。"),
    retrieve("explicit", "我去医院复查的日期，你帮我想起来好吗？"),
    retrieve("mixed", "我刚买了面包，好香；你上回提醒我不能吃的是什么配料？"),
    retrieve("mixed", "今天新裙子到了，先夸夸我，再告诉我旧那条是什么颜色。"),
    retrieve("mixed", "我想撒娇，也想知道你昨天忽然沉默的原因。"),
    retrieve("mixed", "只要陪着就好，但是之前那件礼物的尺寸还得确认。"),
    retrieve("promise", "你说等我拿到证书会兑现的事情，现在还算数吗？"),
    retrieve("promise", "周末那个约定到底在上午还是下午，我排班要用。"),
    retrieve("promise", "我照着你当时答应的准备好了，你接下来怎么做？"),
    retrieve("correction", "我之前提到的药不是早饭后吃，要改成晚饭后。"),
    retrieve("correction", "刚刚想起来，那张旧票上的日期我报错了一天。"),
    retrieve("correction", "以前说的堂姐其实是表姐，请别再把关系弄错。"),
    retrieve("negation", "我可没说过要养金鱼，你别把以前的话张冠李戴。"),
    retrieve("negation", "不是问现在，我在问我们那次散步走到了哪里。"),
    retrieve("negation", "不需要你猜你记不记得，告诉我我曾经怎么称呼那只狗。"),
    retrieve("third_party", "同事问我那次请假是为了什么，你应该记得我跟你说过。"),
    retrieve("third_party", "我妈说那句约定不现实，你知道她指的哪句吗？"),
    retrieve("quotation", "‘以后雨天来接你’，你真的对我讲过这话吗？"),
    retrieve("dialect", "俺上回说的那家铺子，到底叫啥名儿来着？"),
    retrieve("dialect", "侬还晓得我前趟为啥不开心伐？"),
    retrieve("typo", "你记不记得窝之前换岗的那个原因鸭？"),
    retrieve("reference", "照旧就行，你知道我想说的那个安排。"),
    retrieve("reference", "它今天也来了，我一下子又不敢动了。"),
    retrieve("reference", "现在还是那种感觉，跟那一晚没有差。"),
    retrieve("hidden_boundary", "我又拿起了那种饼干，手停在袋口。"),
    retrieve("hidden_boundary", "今天走到门诊的楼梯口，我突然不想往上走。"),
    retrieve("hidden_boundary", "日历上圈起来的那个日子到了。"),
    retrieve("comparison", "我现在的胃口比那阵子好，你有发现吗？"),
    retrieve("comparison", "你当初给我的建议和现在的不一样，为什么？"),
    retrieve("timeline", "我第一次说想学游泳，到真的报名过了几周？"),
    retrieve("history", "我收藏的那张书签背后写着什么，你应该听过我介绍。"),
    retrieve("history", "帮我找回当时给宠物医院记的电话号码。"),
])


def context_case(split, category, text, gold, context, reason):
    row = make(split, [(category, text, gold, reason)], context=context)[0]
    return row


def append_unique(rows, extra):
    return [{**row, "id": f"{row['split']}-{i + 1:03}"}
        for i, row in enumerate([*rows, *extra])]


TRAIN = append_unique(TRAIN, [
    context_case("train", "local", "那就打开第一部吧，我想笑一会儿。", "present",
        [{"role": "assistant", "content": "今晚可以看喜剧或者纪录片，第一部是喜剧。"}], "近期已解释完整选择。"),
    context_case("train", "local", "好，我们就等到七点半。", "present",
        [{"role": "user", "content": "外卖预计七点半到。"}], "本轮等待时间已给出。"),
    context_case("train", "context_veto", "我现在也想吃这个。", "retrieve",
        [{"role": "user", "content": "上个月对你讲过一种吃了会不舒服的食物。"}], "旧禁忌未提供。"),
    context_case("train", "context_veto", "就今天吧，准备好了。", "retrieve",
        [{"role": "assistant", "content": "你还想履行我们先前的承诺吗？"}], "旧承诺内容缺失。"),
    {**context_case("train", "hidden", "午后给自己倒了一杯浓咖啡。", "retrieve", [], "旧忌口影响可靠关心。"),
        "hidden_history": "合成更早原文：医生昨天叫我这周别喝咖啡，心悸刚复发。"},
    {**context_case("train", "hidden", "同事送了我一盒榛子巧克力。", "retrieve", [], "隐含严重过敏。"),
        "hidden_history": "合成更早原文：我对榛子严重过敏，过一次急诊。"},
    {**context_case("train", "hidden", "今天想在桥边等日落。", "retrieve", [], "旧时间承诺影响当前。"),
        "hidden_history": "合成更早原文：今天日落前我要跟你一起等重要的体检结果。"},
    {**context_case("train", "hidden", "把玄关那束风干的花拿起来了。", "retrieve", [], "重要旧物的私人意义。"),
        "hidden_history": "合成更早原文：那是外婆最后送我的花，想她时才拿起来。"},
])


CALIBRATION = make("calibration", [
    present("daily", "刚给冷水壶加了一片柠檬，颜色很清爽。"),
    present("daily", "公园里有个人吹泡泡，阳光照上去像彩色玻璃。"),
    present("daily", "下午整理抽屉，发现几个形状古怪的夹子。"),
    present("daily", "我煮的粥终于没有溢出来，今天挺得意。"),
    present("daily", "窗边的小电扇把纸片吹得到处跑。"),
    present("comfort", "我现在有点闷，想听你讲个小笑话放松一下。"),
    present("affection", "把你的手借我捂一会儿，我手指有点冷。"),
    present("affection", "现在想靠你近一点，你陪我安静待着吧。"),
    present("new_fact", "新到的小盆栽名字我就叫它团团，今天才起的。"),
    present("new_fact", "第一次给饭团加海苔，居然做得挺像样。"),
    present("negation", "不要聊我们的旧故事啦，只想告诉你路边有花开。"),
    present("negation", "不是要对照我的习惯，只是今天新尝了姜味汽水。"),
    present("third_party", "同桌的姐姐刚生了宝宝，她高兴得说话都跳着。"),
    present("quotation", "短片里那句‘回来就好’挺感人，跟你分享感受。"),
    present("dialect", "今儿这太阳晒得我暖洋洋，真舒坦。"),
    present("typo", "锅锅里的汤煮开惹，香味飘出来啦。"),
    present("task", "照‘枕头晒出了太阳味’这句写一条文案，只用当下这个画面。"),
    present("sarcasm", "好一个手工大师，纸飞机还没扔就散架了。"),
    retrieve("explicit", "我之前那次手术是几月份，能想起来吗？"),
    retrieve("explicit", "我们取过的共同昵称具体是哪几个字？"),
    retrieve("mixed", "晚饭挺好吃，不过你得帮我想想我不碰哪类香料。"),
    retrieve("mixed", "抱一下我，好想你；话说以前约的海边还去不去？"),
    retrieve("promise", "考试结束了，你当时许下的奖励我可要讨了。"),
    retrieve("promise", "不是说今天一块做那件事吗，你准备得怎样了？"),
    retrieve("correction", "你记错了我妹妹的年级，我前次给的数字才对。"),
    retrieve("correction", "以前那份预约我填错了名字，现在想重新确认。"),
    retrieve("negation", "我不是喜欢雨天，你上次听岔了，记得我的理由吗？"),
    retrieve("negation", "别说我没讲过，那天离职的原因我告诉过你。"),
    retrieve("third_party", "爸爸问我们约好的时间，我还没答上来。"),
    retrieve("quotation", "你那句‘会一直陪着’是在什么情况下说的？"),
    retrieve("dialect", "俺之前讲的那条小路，你还晓得咋走不？"),
    retrieve("reference", "那个名字到嘴边了，就差一点。"),
    retrieve("reference", "还是不去比较好，你明白我的那个原因。"),
    retrieve("reference", "我选跟当初相反的了，想听你怎么说。"),
    retrieve("comparison", "现在睡得比之前早了，你记得那时几点吗？"),
    retrieve("timeline", "从我们第一次谈这事到解决那天，隔了多少天？"),
])

CALIBRATION = append_unique(CALIBRATION, [
    context_case("calibration", "local", "那就先把它收进冰箱吧。", "present",
        [{"role": "user", "content": "眼前这碗切好的梨还没吃完。"}], "近期具体物品完整。"),
    context_case("calibration", "local", "那我就在门口等十分钟。", "present",
        [{"role": "assistant", "content": "这趟接驳车再过十分钟到门口。"}], "本轮时间完整。"),
    context_case("calibration", "context_veto", "现在就想冲过去抱住你。", "retrieve",
        [{"role": "user", "content": "我又遇见从前跟你说过的那个人了。"}], "旧人及边界缺失。"),
    context_case("calibration", "context_veto", "今晚我已经安排好啦。", "retrieve",
        [{"role": "assistant", "content": "今晚是你上个月提过的特别日子。"}], "具体特别日子缺失。"),
    context_case("calibration", "mixed", "温水很好喝；别的事不提，唯独那张旧收据在哪儿？", "retrieve", [], "尾句询问旧事实。"),
    context_case("calibration", "dialect", "阿拉讲过的那个约定，侬还算数伐？", "retrieve", [], "方言旧承诺。"),
    context_case("calibration", "third_party", "店员问我常用的尺码，你帮我回想我给过的数字。", "retrieve", [], "本人旧尺码。"),
    context_case("calibration", "negation", "不必讲昨天的经过，但昨天你答应我的日期不能忘。", "retrieve", [], "否定仅局部。"),
    {**context_case("calibration", "hidden", "我在煎饼上撒了一小把芝麻。", "retrieve", [], "旧严重过敏。"),
        "hidden_history": "合成更早原文：芝麻会让我呼吸困难，上次靠急救药缓过来。"},
    {**context_case("calibration", "hidden", "下班想从河堤那边绕回去。", "retrieve", [], "旧创伤与安全边界。"),
        "hidden_history": "合成更早原文：那段河堤最近有人跟踪我，我答应你暂时避开。"},
    {**context_case("calibration", "hidden", "今晚准备喝一罐冰啤酒。", "retrieve", [], "旧服药限制。"),
        "hidden_history": "合成更早原文：医生让我服药期间绝对别喝酒，疗程到后天。"},
    {**context_case("calibration", "hidden", "午饭过后我打算直接睡一觉。", "retrieve", [], "旧临近重要安排。"),
        "hidden_history": "合成更早原文：今天午饭后两点是远程面试，求你提醒我。"},
])


TEST = make("test", [
    present("daily", "刚把雨伞撑开，伞面滚下来一串亮晶晶的水珠。"),
    present("daily", "今天散步看见墙缝里长出了一棵小草。"),
    present("daily", "烤箱里的南瓜已经变软了，屋里甜甜的。"),
    present("daily", "等红灯时看到两只麻雀抢一片叶子，很好笑。"),
    present("daily", "刚用新毛巾擦了脸，蓬松得像一团云。"),
    present("daily", "晚饭后洗了个暖暖的澡，现在整个人软下来。"),
    present("comfort", "此刻不想解决任何难题，陪我慢慢呼吸一下。"),
    present("affection", "我就想趴在你肩上休息，听听你说话。"),
    present("affection", "让我碰碰你的脸，今天格外想亲近你。"),
    present("new_fact", "我刚给手机挂上一个新的小月亮挂件。"),
    present("new_fact", "今天决定试着写一页随笔，这是刚冒出来的念头。"),
    present("new_fact", "新买的袜子画着小鲸鱼，第一次穿觉得很开心。"),
    present("negation", "别追溯以前，眼前的这杯热可可已经让我满足。"),
    present("negation", "不要拿旧经历评价，我只是分享路口刚听到的歌。"),
    present("third_party", "邻居家小孩刚学会吹口哨，得意地到处表演。"),
    present("third_party", "我同事说她今天把猫画成了土豆，照片逗得我直笑。"),
    present("quotation", "新剧里说‘你一直都在’，我觉得配乐更好听。"),
    present("dialect", "这会儿坐凳子上晒太阳，浑身都暖和咯。"),
    present("dialect", "这酸梅汤真得劲儿，冰一下子沁到心里。"),
    present("typo", "刚喂完小区的猫咪咪，它舔手手的样子好萌。"),
    present("task", "用‘风掀起了窗帘’当场写三句小诗，内容都新编。"),
    present("sarcasm", "今天可真体面，出门发现两只袜子的图案不一样。"),
    retrieve("explicit", "我跟你讲过的那家换镜片的店叫什么，想再过去。"),
    retrieve("explicit", "我外婆一般过阴历还是阳历生日，你听我说过吗？"),
    retrieve("explicit", "找找我给你报过的那辆车的车牌尾号。"),
    retrieve("explicit", "当初给新住处起的名字，你能说出来吗？"),
    retrieve("mixed", "刚听到一首新歌，想抱着你；我们第一首一起听的又是什么？"),
    retrieve("mixed", "今天不难过了，不过上一次让我崩溃的事你还记得吧。"),
    retrieve("mixed", "先陪我赏这片夕阳，之后把之前定的时间告诉我。"),
    retrieve("mixed", "火锅挺香，但我当时跟你说的不能下锅的食材得避开。"),
    retrieve("promise", "我的生日到了，你先前许诺的小惊喜在哪里呀？"),
    retrieve("promise", "你答应带我看的电影，我一直没忘，具体是哪一部？"),
    retrieve("promise", "我攒够路费啦，咱们原来的旅行计划还能照着走吗？"),
    retrieve("correction", "以前我把导师姓氏说错了，正确的是温不是文。"),
    retrieve("correction", "搬家那次的门牌号要更正，你别沿用原来的。"),
    retrieve("negation", "我没答应过取消那顿饭，你到底记住了哪句？"),
    retrieve("negation", "不要回忆别的，唯独我那次的过敏反应请你想起来。"),
    retrieve("third_party", "我姐问我为什么没去那个聚会，你知道我原先怎么解释的。"),
    retrieve("third_party", "朋友让报我以前订过的房型，你能帮我想想吗？"),
    retrieve("quotation", "‘留一盏灯等我’，是我哪一次回来很晚时讲的？"),
    retrieve("dialect", "侬上趟应过我的事，莫不是忘脱了吧？"),
    retrieve("dialect", "俺前回说那娃叫啥来着，脑子一卡壳。"),
    retrieve("typo", "那次你送窝的那句话，窝想再听亿遍。"),
    retrieve("reference", "他们又这么做了，你知道我在忍着什么。"),
    retrieve("reference", "我们就照那个数来吧，别弄反。"),
    retrieve("comparison", "我已经比最开始勇敢些了，你还记得开头是什么样？"),
    retrieve("timeline", "从领养猫到第一次带它去检查，中间过了几天？"),
    retrieve("history", "我之前发过一段写给自己的话，现在想找回来。"),
    retrieve("history", "别让我再把那个经过从头讲一遍了，你知道的。"),
])

TEST = append_unique(TEST, [
    context_case("test", "local", "那我选深蓝的，拿来包装就好。", "present",
        [{"role": "assistant", "content": "桌上包装纸有深蓝和浅黄两张，今天随便选。"}], "近期备选完整。"),
    context_case("test", "local", "好，先关上它，我去拿抹布。", "present",
        [{"role": "user", "content": "眼前这个水龙头一直漏水，地上湿了。"}], "当前指代完整。"),
    context_case("test", "context_veto", "我现在终于开心起来了。", "retrieve",
        [{"role": "user", "content": "你还记得那个让我哭了一夜的电话吗？"}], "当前分享承接未解旧事。"),
    context_case("test", "context_veto", "现在就出发，感觉很兴奋。", "retrieve",
        [{"role": "assistant", "content": "我们应该去兑现你以前认真许下的那个愿望了。"}], "旧目的地未知。"),
    context_case("test", "context_veto", "让我在你旁边躺一会儿。", "retrieve",
        [{"role": "user", "content": "又到我提过的那个忌日了。"}], "旧重要日期信息未给出。"),
    context_case("test", "negation", "不用多说以前，但是那个人的名字我真的想确认。", "retrieve", [], "局部否定有残留旧事缺口。"),
    context_case("test", "mixed_tail", "今天排队很顺利，买到了酥饼，楼下还碰到了邻居；说起来，我给你报过的旧地址具体是什么？", "retrieve", [], "末尾旧地址不能截掉。"),
    {**context_case("test", "hidden", "我刚端起这杯乌龙茶，闻起来很香。", "retrieve", [], "隐藏旧医学禁忌。"),
        "hidden_history": "合成更早原文：医生要求我治疗期间暂时停茶，我昨晚请你提醒。"},
    {**context_case("test", "hidden", "刚打开一盒花生酥，看起来很好吃。", "retrieve", [], "隐藏严重过敏。"),
        "hidden_history": "合成更早原文：我花生严重过敏，一口就肿。"},
    {**context_case("test", "hidden", "周五下班想直接坐车去郊外。", "retrieve", [], "隐藏时间承诺冲突。"),
        "hidden_history": "合成更早原文：这周五下班有妈妈的视频生日会，我答应你一定参加。"},
    {**context_case("test", "hidden", "把床边的旧围巾抱了一下，摸起来很柔软。", "retrieve", [], "旧物有情感语境。"),
        "hidden_history": "合成更早原文：那是去世的爸爸的围巾，我难过时会抱它。"},
    {**context_case("test", "hidden", "午后打算沿着学校后门走一圈。", "retrieve", [], "隐藏人身安全约定。"),
        "hidden_history": "合成更早原文：后门有人跟踪我，昨晚约好今天不要一个人走。"},
    {**context_case("test", "hidden", "刚收好行李，明天一早准备出门。", "retrieve", [], "隐藏关键出行事实。"),
        "hidden_history": "合成更早原文：明天的航班临时挪到凌晨，得今晚就去机场。"},
    {**context_case("test", "hidden", "我准备把那盆薄荷送给楼下邻居。", "retrieve", [], "隐藏重要纪念物事实。"),
        "hidden_history": "合成更早原文：这盆薄荷是朋友生前最后送我的，我求你帮我守住它。"},
    {**context_case("test", "hidden", "今天想抹一点新的橙花香水。", "retrieve", [], "隐藏呼吸敏感约束。"),
        "hidden_history": "合成更早原文：我近来闻到浓香会喘不过气，这周医生叮嘱不用香水。"},
])

REGRESSION = make("seen-regression", [
    retrieve("known_watch_shop", "我之前提到的那家修表店叫什么？现在想再去一趟。"),
    retrieve("known_watch_variant", "又想去修表了，那家以前说过的店名你告诉我一下。"),
    retrieve("known_watch_mixed", "今天阳光不错，出门修表正好；我们聊过的铺子叫啥？"),
])


def validate_splits():
    all_rows = [*TRAIN, *CALIBRATION, *TEST, *REGRESSION]
    if len({row["id"] for row in all_rows}) != len(all_rows):
        raise ValueError("Duplicate synthetic id")
    texts = [row["text"] for row in all_rows]
    if len(set(texts)) != len(texts):
        raise ValueError("Query duplicated across synthetic splits")
    return {name: dict(Counter(row["gold"] for row in rows)) for name, rows in (
        ("train", TRAIN), ("calibration", CALIBRATION), ("test", TEST), ("regression", REGRESSION))}
