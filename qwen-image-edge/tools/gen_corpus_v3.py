#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
蒸馏语料 v3：在 v2 的长度覆盖基础上，修掉槽位错配/性别年龄矛盾/代词不一致等语法问题

v1 缺陷：平均 18.5 token、最大 32、>90 token 占 0%   -> 长提示词全在分布外
v2 缺陷：长度修好了，但"中年的女孩""她/他""头发戴着眼镜"等语法错误多
v3 目标：长度覆盖 + 语法人称一致
"""
import os, json, random, argparse

R = random.Random(20261012)

# ---------- 主体：性别与代词绑定，年龄池按主体区分 ----------
SUBJECTS = [
    ("年轻女性", "她", ["二十出头的", "二十五六岁的", "三十岁左右的", ""]),
    ("年轻男性", "他", ["二十出头的", "二十五六岁的", "三十岁左右的", ""]),
    ("女孩",     "她", ["七八岁的", "十来岁的", "十二三岁的", ""]),
    ("男孩",     "他", ["七八岁的", "十来岁的", "十二三岁的", ""]),
    ("女士",     "她", ["三十多岁的", "四十岁上下的", "中年的", ""]),
    ("男士",     "他", ["三十多岁的", "四十岁上下的", "中年的", ""]),
    ("老人",     "他", ["头发花白的", "年过七旬的", ""]),
    ("少女",     "她", ["十六七岁的", "十八九岁的", ""]),
    ("少年",     "他", ["十六七岁的", "十八九岁的", ""]),
    ("女生",     "她", ["大学年纪的", "二十岁出头的", ""]),
    ("男生",     "他", ["大学年纪的", "二十岁出头的", ""]),
]

HAIR = ["长发披肩", "短发利落", "扎着低马尾", "把头发挽成丸子头", "留着微卷的齐肩发",
        "把头发扎成双马尾", "梳着整齐的背头", "头发被风吹得有些凌乱"]
FACE = ["戴着细框眼镜", "戴着圆框墨镜", "额前有细碎的刘海", "皮肤白皙", "肤色健康偏小麦色",
        "眉眼清秀", "鼻梁高挺", "脸颊圆润", "笑起来有浅浅的酒窝"]
CLOTH_F = ["米白色针织毛衣", "黑色吊带背心", "浅蓝色衬衫", "白色连衣裙", "红色碎花裙",
           "藏青色水手服", "卡其色风衣", "格子围巾配毛呢外套", "简洁的白色T恤"]
CLOTH_M = ["深灰色西装外套", "黑色高领毛衣", "浅蓝色衬衫", "深蓝色大衣", "黄色连帽衫",
           "牛仔外套配白T", "运动卫衣和束脚裤", "简洁的白色T恤"]

POSE = ["侧身面向镜头微笑", "双手托腮看着镜头", "低头翻阅手中的书", "抬头望向远方",
        "双手捧着一只马克杯", "单手撑着下巴", "身体微微前倾", "靠在墙上", "坐在椅子上交叠双腿",
        "站在窗边回头", "蹲下来抚摸小猫", "张开双臂迎向阳光", "边走边回头", "举着手机自拍",
        "把书抱在胸前", "双手插在口袋里", "抬手整理头发", "微微低头看手中的东西"]
EXPR = ["表情自然放松", "嘴角带着浅浅的笑意", "闭眼享受阳光", "眉头微蹙若有所思",
        "开怀大笑", "神情安静", "眼神温柔", "表情认真专注"]

ENV = ["客厅的布艺沙发上", "靠窗的木质餐桌旁", "铺着长毛地毯的书房里", "现代风格的办公室中",
       "摆满书的图书馆角落", "临街的咖啡馆座位上", "铺着格子餐布的野餐垫上", "老城区的石板巷子里",
       "开满樱花的小径上", "雨后的城市街道边", "夏日的海边沙滩上", "覆着薄雪的松林里",
       "地铁车厢的座位上", "美术馆的展厅中央", "天台的栏杆旁", "厨房的料理台前",
       "便利店的冷柜旁", "音乐节的草地上", "健身房的落地镜前", "校园的林荫道中间",
       "开满野花的草原上", "摆着绿植的阳台上", "木质书架间的过道上", "傍晚的河堤边"]
BG_DETAIL = ["背景里有一盆龟背竹", "身后是虚化的书架", "远处能看到城市天际线",
             "墙上有几幅装裱好的画", "桌面上散落着未拆的信件", "窗外是连绵的山脊",
             "背景是暖色的霓虹招牌", "身后挂着米色的窗帘", "旁边停着一辆复古自行车",
             "地面上有斑驳的树影", "背景是虚化的粉色樱花与远处的教学楼", "身后有一排暖黄的吊灯",
             "桌上放着半杯还冒着热气的咖啡", "一只橘猫趴在旁边的垫子上"]
LIGHT = ["午后阳光从右侧窗户斜射进来", "头顶的暖黄色灯光柔和地铺开", "清晨的金色光线穿过薄雾",
         "阴天的漫射光让阴影非常柔和", "背后的霓虹灯投下蓝紫色反光", "逆光在发丝边缘形成明亮的轮廓光",
         "傍晚的夕阳把整个画面染成橘色", "闪光灯直接打在脸上", "舞台顶灯自上而下照亮",
         "雪地反射的冷调光线", "烛光在脸上轻轻跳动", "柔和的棚拍三点布光，主光从左前方打来"]
LIGHT_EFFECT = ["在脸颊和衣服上形成柔和的暖色高光", "让画面有了明确的方向感", "让肤色显得通透自然",
                "把头发边缘勾出一圈亮边", "在地面投下长长的影子"]
CAMERA = ["中景构图", "半身特写", "全身入镜的竖版构图", "横幅中景", "近景特写",
          "低角度仰拍", "略高于视线的俯拍", "正面平视机位", "过肩视角", "广角环境人像"]
LENS = ["浅景深，背景虚化", "f/1.8 大光圈虚化", "35mm 视角", "85mm 人像镜头的压缩感",
        "微距细节清晰", "长焦压缩背景"]
COLOR = ["色调温暖偏黄", "冷调偏青", "高饱和度明快", "低饱和度的胶片感", "高对比度",
         "柔和的莫兰迪色系", "暖色与冷色强烈对撞"]
MOOD = ["整体呈现出自然、放松的日常生活氛围", "传达出安静而略带怀旧的情绪",
        "画面充满活力与元气", "有一种电影剧照的叙事感", "显得干净利落",
        "带有淡淡的高级感", "氛围温暖治愈"]
STYLE = ["写实摄影", "纪实风格", "日式动漫赛璐璐上色", "皮克斯风格 3D 渲染", "迪士尼风格 3D 渲染",
         "水彩手绘", "厚涂油画质感", "国风水墨", "像素风", "赛博朋克插画", "扁平矢量插画",
         "黏土动画风格", "铅笔线稿", "胶片摄影"]
QUALITY = ["皮肤保留真实纹理", "材质细节清晰可辨", "画面锐利没有噪点", "细腻的布料纹理",
           "头发丝丝分明", "衣物褶皱自然", "光影过渡柔和", "层次丰富"]
OBJECTS = ["一只马克杯", "一台老式相机", "一束白色桔梗", "一本翻开的笔记本", "一把木吉他",
           "一盘刚烤好的可颂", "一只趴在垫子上的橘猫", "一盏黄铜台灯", "一副耳机", "一把透明雨伞"]
TEXT_ITEMS = ["招牌上用手写中文字写着“深夜咖啡”", "红色横幅上印着“欢迎光临”",
              "菜单上手写着“红烧牛肉面”", "灯箱上写着“24小时营业”", "黑板上的粉笔字写着“今日推荐”",
              "路牌上写着“中山路”", "包装盒上印着“手工制作”", "海报顶部写着“限时特惠”"]
TAIL = ["高清细节", "电影感", "生活照", "艺术感构图", "极简摄影", "商业人像", "氛围感", ""]


def subj():
    name, pron, ages = R.choice(SUBJECTS)
    age = R.choice(ages)
    return name, pron, age


def cloth_for(name):
    return R.choice(CLOTH_F if name in ("年轻女性", "女孩", "女士", "少女", "女生") else CLOTH_M)


def gen_short():
    name, pron, age = subj()
    return (f"{R.choice(['一个', '一位'])}{age}{name}{R.choice(POSE)}，"
            f"{R.choice(ENV)}，{R.choice(LIGHT)}，{R.choice(TAIL) or R.choice(STYLE)}")


def gen_medium():
    name, pron, age = subj()
    parts = [f"{R.choice(['一个', '一位'])}{age}{name}"]
    parts.append(R.choice(POSE))
    parts.append(f"穿{cloth_for(name)}")
    parts.append(f"{R.choice(ENV)}")
    parts.append(R.choice(LIGHT))
    parts.append(R.choice(BG_DETAIL) if R.random() < 0.6 else R.choice(QUALITY))
    parts.append(R.choice(TAIL) or R.choice(COLOR))
    return "，".join(p for p in parts if p)


def gen_long():
    """官方 rewriter 风格：主体 / 动作 / 服装 / 光照 / 背景 / 物件 / 机位 / 色调 / 氛围 / 质量"""
    name, pron, age = subj()
    segs = [f"{R.choice(['一位', '一个'])}{age}{name}，{R.choice(['站在', '坐在', '侧身面向'])}{R.choice(ENV)}。"]
    if R.random() < 0.85:
        segs.append(f"{pron}{R.choice(POSE)}，{R.choice(EXPR)}。")
    if R.random() < 0.85:
        segs.append(f"{pron}穿着{cloth_for(name)}，{R.choice(HAIR)}，{R.choice(FACE)}。")
    if R.random() < 0.8:
        segs.append(f"{R.choice(LIGHT)}，{R.choice(LIGHT_EFFECT)}。")
    if R.random() < 0.8:
        segs.append(f"{R.choice(BG_DETAIL)}，{R.choice(['背景做了虚化处理', '景深较浅', '远处细节柔和'])}。")
    if R.random() < 0.55:
        segs.append(f"{R.choice(OBJECTS)}{R.choice(['摆在画面一侧', '被自然地握在手里', '放在触手可及的位置'])}。")
    if R.random() < 0.75:
        segs.append(f"画面为{R.choice(CAMERA)}，{R.choice(LENS)}，{R.choice(COLOR)}。")
    segs.append(f"{R.choice(MOOD)}，{R.choice(STYLE)}风格，{R.choice(QUALITY)}。")
    return "".join(segs)


def gen_text_img():
    return (f"{R.choice(['咖啡馆木质招牌特写', '便利店门口的灯箱', '餐厅菜单特写', '书店橱窗', '地铁站指示牌', '奶茶店招牌'])}，"
            f"{R.choice(TEXT_ITEMS)}，{R.choice(LIGHT)}，{R.choice(['浅景深', '暖色灯光', '极简构图'])}，中文字体清晰工整")


def gen_long_text():
    return (f"一张关于{R.choice(['街边小店', '咖啡馆', '书店', '面包房', '花店'])}的纪实摄影："
            f"{R.choice(ENV)}，{R.choice(['木质招牌', '灯箱', '玻璃橱窗', '手写黑板'])}{R.choice(TEXT_ITEMS)}。"
            f"{R.choice(LIGHT)}，{R.choice(BG_DETAIL)}。"
            f"{R.choice(CAMERA)}，{R.choice(COLOR)}，{R.choice(MOOD)}，{R.choice(QUALITY)}。")


def gen_group():
    n = R.choice([2, 3, 4, 5, 6, 8])
    who = R.choice(["同事", "朋友", "家人", "同学", "队友"])
    act = R.choice(["站成一排微笑看镜头", "围坐在一起聊天", "并排走在路上", "在户外合影", "举杯庆祝"])
    return (f"一张{['', '写实的', '自然的'][R.randint(0, 2)]}合影：{n}位{who}在{R.choice(ENV)}{act}。"
            f"{R.choice(['从左到右分别是不同年龄段与穿着风格的人', '每个人的姿态自然放松各不相同', '几人穿着同色系的服装'])}。"
            f"{R.choice(LIGHT)}，{R.choice(['人物面部没有强烈阴影', '肤色真实自然', '面部受光均匀'])}，"
            f"{R.choice(CAMERA)}，{R.choice(MOOD)}。")


def gen_hand():
    act = R.choice(["双手捧着一只马克杯", "右手握着菜刀切菜", "左手按着纸右手写字", "双手比出心形",
                    "用手指捏着一枚硬币", "双手放在钢琴键上", "正在系鞋带", "双手抱着纸箱"])
    name, pron, age = subj()
    return (f"{R.choice(['一个人', f'一位{age}{name}'])}{act}，{R.choice(ENV)}。"
            f"手部细节清晰，{R.choice(['手指关节结构准确', '指甲修剪整齐', '指腹有自然的受力感'])}，"
            f"{R.choice(LIGHT)}，{R.choice(['浅景深', '手部特写', '近景构图'])}，{R.choice(QUALITY)}。")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-n", type=int, default=12000)
    ap.add_argument("--out", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\distill\corpus_v3.jsonl")
    args = ap.parse_args()
    os.makedirs(os.path.dirname(args.out), exist_ok=True)

    gens = [(gen_short, 0.22), (gen_medium, 0.25), (gen_long, 0.32),
            (gen_text_img, 0.05), (gen_long_text, 0.05), (gen_group, 0.05), (gen_hand, 0.06)]
    out, seen, tries = [], set(), 0
    while len(out) < args.n and tries < args.n * 12:
        tries += 1
        f = R.choices([g for g, _ in gens], weights=[w for _, w in gens])[0]
        p = " ".join(f().split())
        if len(p) < 10 or p in seen:
            continue
        seen.add(p)
        out.append(p)
    R.shuffle(out)
    with open(args.out, "w", encoding="utf-8") as fh:
        for i, p in enumerate(out):
            fh.write(json.dumps({"id": f"q{i:06d}", "prompt": p}, ensure_ascii=False) + "\n")
    print(f"生成 {len(out)} 条 -> {args.out}")


if __name__ == "__main__":
    main()
