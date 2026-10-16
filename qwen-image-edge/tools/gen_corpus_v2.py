#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
蒸馏语料 v2：修掉 v1 的两个致命缺陷
  v1 问题：平均仅 18.5 token、最大 32、>90 token 占 0%  ->  长提示词完全在分布外
  v2 目标：短/中/长三段覆盖，最长约 220 token，句式和词表大幅扩充

长度分布设计：短(15-35) 25% / 中(40-90) 40% / 长(100-220) 35%
"""
import os, json, random, argparse

R = random.Random(20261011)

# ---------- 词池 ----------
AGE = ["二十出头的", "二十五六岁的", "三十岁左右的", "中年的", "年少的", "看上去四十岁的", ""]
GENDER = ["年轻女性", "年轻男性", "女孩", "男孩", "女士", "男士", "老人", "少女", "少年"]
APPEAR = ["长发披肩", "短发利落", "扎着低马尾", "把头发挽成丸子头", "留着微卷的齐肩发",
          "戴着细框眼镜", "戴着圆框墨镜", "额头有细碎刘海", "皮肤白皙", "肤色健康偏小麦色",
          "眉眼清秀", "笑容温柔", "鼻梁高挺", "脸颊圆润"]
CLOTH = ["米白色针织毛衣", "黑色吊带背心", "浅蓝色衬衫", "深灰色西装外套", "黄色连帽衫",
         "藏青色水手服", "白色连衣裙", "卡其色风衣", "红色碎花裙", "牛仔外套配白T",
         "运动卫衣和束脚裤", "深蓝色大衣", "格子围巾配毛呢外套", "简洁的白色T恤"]
POSE = ["侧身面向镜头微笑", "双手托腮看着镜头", "低头翻阅手中的书", "抬头望向远方",
        "双手捧着马克杯", "单手撑着下巴", "身体微微前倾", "靠在墙上", "坐在椅子上交叠双腿",
        "站在窗边回头", "蹲下来抚摸小猫", "张开双臂迎向阳光", "边走边回头", "举着手机自拍"]
EXPR = ["表情自然放松", "嘴角带着浅浅的笑意", "闭眼享受阳光", "眉头微蹙若有所思",
        "开怀大笑", "神情安静", "眼神温柔", "表情认真专注"]
ENV = ["客厅的布艺沙发上", "靠窗的木质餐桌旁", "铺着长毛地毯的书房里", "现代风格的办公室中",
       "摆满书的图书馆角落", "临街的咖啡馆座位上", "铺着格子餐布的野餐垫上", "老城区的石板巷子里",
       "开满樱花的小径上", "雨后的城市街道边", "夏日的海边沙滩上", "覆着薄雪的松林里",
       "地铁车厢的座位上", "美术馆的展厅中央", "天台的栏杆旁", "厨房的料理台前",
       "便利店的冷柜旁", "音乐节的草地上", "健身房的落地镜前", "校园的林荫道中间"]
BG_DETAIL = ["背景里有一盆龟背竹", "身后是虚化的书架", "远处能看到城市天际线",
             "墙上有几幅装裱好的画", "桌面上散落着未拆的信件", "窗外是连绵的山脊",
             "背景是暖色的霓虹招牌", "身后挂着米色的窗帘", "旁边停着一辆复古自行车",
             "地面有斑驳的树影", "背景是虚化的粉色樱花与远处的教学楼", "身后有一排暖黄的吊灯",
             "桌上有半杯还冒着热气的咖啡", "一只橘猫趴在旁边的垫子上"]
LIGHT = ["午后阳光从右侧窗户斜射进来", "头顶的暖黄色灯光柔和地铺开", "清晨的金色光线穿过薄雾",
         "阴天的漫射光让阴影非常柔和", "背后的霓虹灯投下蓝紫色反光", "逆光在发丝边缘形成明亮的轮廓光",
         "傍晚的夕阳把整个画面染成橘色", "闪光灯直接打在脸上", "舞台顶灯自上而下照亮",
         "雪地反射的冷调光线", "烛光在脸上跳动", "柔和的棚拍三点布光，主光从左前方来"]
CAMERA = ["中景构图", "半身特写", "全身入镜的竖版构图", "横幅中景", "近景特写",
          "低角度仰拍", "略高于视线的俯拍", "正面平视机位", "过肩视角", "广角环境人像"]
LENS = ["浅景深，背景虚化", "f/1.8 大光圈虚化", "35mm 视角", "85mm 人像镜头压缩感",
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

T2I_TAIL = ["高清细节", "电影感", "生活照", "艺术感构图", "极简摄影", "商业人像", "氛围感", ""]
CONN = ["，", "，", "，", "。", "；"]


def gen_short():
    s = f"{R.choice(['一个', '一位'])}{R.choice(GENDER)}{R.choice(POSE)}，{R.choice(ENV)}，{R.choice(LIGHT)}，{R.choice(T2I_TAIL) or R.choice(STYLE)}"
    return s


def gen_medium():
    parts = [
        f"{R.choice(['一个', '一位'])}{R.choice(AGE)}{R.choice(GENDER)}",
        R.choice(POSE),
        f"穿着{R.choice(CLOTH)}",
        f"{R.choice(ENV)}",
        R.choice(LIGHT),
        R.choice(BG_DETAIL),
        R.choice(T2I_TAIL) or R.choice(QUALITY),
    ]
    R.shuffle(parts[2:5])
    return "，".join(p for p in parts if p)


def gen_long():
    """官方 rewriter 风格：分段描述，含主体/外貌/服装/动作/环境/背景/光照/机位/色调/氛围/质量"""
    segs = []
    segs.append(f"{R.choice(['一位', '一个'])}{R.choice(AGE)}{R.choice(GENDER)}，{R.choice(['站在', '坐在', '侧身面向'])}{R.choice(ENV)}。")
    if R.random() < 0.8:
        segs.append(f"她/他{R.choice(POSE)}，{R.choice(EXPR)}。")
    if R.random() < 0.85:
        segs.append(f"穿着{R.choice(CLOTH)}，{R.choice(['头发', '发型'])}{R.choice(APPEAR)}。")
    if R.random() < 0.7:
        segs.append(f"{R.choice(LIGHT)}，{R.choice(['在她的脸颊和衣服上形成柔和的高光', '让画面有了明确的方向感', '让肤色显得通透'])}。")
    if R.random() < 0.8:
        segs.append(f"{R.choice(BG_DETAIL)}，{R.choice(['背景做了虚化处理', '景深较浅', '远处细节柔和'])}。")
    if R.random() < 0.6:
        segs.append(f"{R.choice(OBJECTS)}{R.choice(['摆在画面一侧', '被自然地握在手里', '放在触手可及的位置'])}。")
    if R.random() < 0.75:
        segs.append(f"画面为{R.choice(CAMERA)}，{R.choice(LENS)}，{R.choice(COLOR)}。")
    segs.append(f"{R.choice(MOOD)}，{R.choice(STYLE)}风格，{R.choice(QUALITY)}。")
    return "".join(segs)


def gen_text_img():
    return (f"{R.choice(['咖啡馆木质招牌特写', '便利店门口的灯箱', '餐厅菜单特写', '书店橱窗', '地铁站指示牌', '奶茶店招牌'])}，"
            f"{R.choice(TEXT_ITEMS)}，{R.choice(LIGHT)}，{R.choice(['浅景深', '暖色灯光', '极简构图'])}，"
            f"中文字体清晰工整")


def gen_long_text():
    """带图内文字的长提示词"""
    return (f"一张关于{R.choice(['街边小店', '咖啡馆', '书店', '面包房', '花店'])}的纪实摄影："
            f"{R.choice(ENV)}，{R.choice(['木质招牌', '灯箱', '玻璃橱窗', '手写黑板'])}{R.choice(TEXT_ITEMS)}。"
            f"{R.choice(LIGHT)}，{R.choice(BG_DETAIL)}。"
            f"{R.choice(CAMERA)}，{R.choice(COLOR)}，{R.choice(MOOD)}，{R.choice(QUALITY)}。")


def gen_group():
    n = R.choice([2, 3, 4, 5, 6, 8])
    who = R.choice(["同事", "朋友", "家人", "同学", "队友"])
    act = R.choice(["站成一排微笑看镜头", "围坐在一起聊天", "并排走在路上", "在户外合影", "举杯庆祝"])
    return (f"一张{['', '写实的', '自然的'][R.randint(0, 2)]}合影：{n}位{who}{R.choice(ENV)}{act}。"
            f"{R.choice(['从左到右分别是不同年龄段与穿着风格的人', '每个人的姿态自然放松各不相同', '几人穿着同色系的服装'])}。"
            f"{R.choice(LIGHT)}，{R.choice(['人物面部没有强烈阴影', '肤色真实', '面部受光均匀'])}，"
            f"{R.choice(CAMERA)}，{R.choice(MOOD)}。")


def gen_hand():
    act = R.choice(["双手捧着一只马克杯", "右手握着菜刀切菜", "左手按着纸右手写字", "双手比出心形",
                    "用手指捏着一枚硬币", "双手在钢琴键上", "正在系鞋带", "双手抱着纸箱"])
    return (f"{R.choice(['一个人', '一位女性', '一位男性'])}{act}，{R.choice(ENV)}。"
            f"手部细节清晰，{R.choice(['手指关节结构准确', '指甲修剪整齐', '指腹有自然的受力感'])}，"
            f"{R.choice(LIGHT)}，{R.choice(['浅景深', '手部特写', '近景构图'])}，{R.choice(QUALITY)}。")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-n", type=int, default=12000)
    ap.add_argument("--out", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\distill\corpus_v2.jsonl")
    args = ap.parse_args()
    os.makedirs(os.path.dirname(args.out), exist_ok=True)

    gens = [(gen_short, 0.22), (gen_medium, 0.26), (gen_long, 0.30),
            (gen_text_img, 0.05), (gen_long_text, 0.05), (gen_group, 0.06), (gen_hand, 0.06)]
    out, seen = [], set()
    tries = 0
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
