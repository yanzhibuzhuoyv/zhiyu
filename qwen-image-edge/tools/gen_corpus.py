#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
蒸馏用提示词语料生成器
覆盖用户目标域（真人 / 二次元 / 3D / 生活照 / 中文图内文字）+ 通用覆盖
组合式模板 + 随机采样，去重后输出 JSONL
"""
import os, json, random, itertools, argparse

R = random.Random(20261010)

SUBJ_REAL = ["一个女生", "一个男生", "一位年轻女性", "一位年轻男性", "一位中年女性", "一位中年男性",
             "一位老人", "一个小女孩", "一个小男孩", "一对情侣", "一家三口", "两个朋友", "一群同事",
             "一位穿西装的男士", "一位穿连衣裙的女生", "一个背书包的学生", "一位戴眼镜的女生"]
SUBJ_ANIME = ["动漫风格少女", "动漫风格少年", "日式动漫女孩", "赛璐璐风格的女仆", "动漫风格的剑士",
              "二次元风格的魔法少女", "动漫风格的校服少女", "动漫风格的骑士"]
SUBJ_3D = ["皮克斯风格 3D 渲染的小男孩", "迪士尼风格 3D 渲染的小女孩", "3D 卡通渲染的厨师",
           "3D 渲染的宇航员", "3D 卡通风格的猫", "3D 渲染的机器人", "黏土动画风格的小人",
           "3D 卡通渲染的老人"]
SUBJ_ILL = ["水彩手绘风格的女孩", "厚涂油画风格的人物", "扁平矢量插画的人物", "铅笔线稿的人物",
            "国风水墨风格的人物", "像素风格的角色", "赛博朋克风格的插画人物"]

SCENE = ["客厅沙发上", "靠窗的餐桌旁", "大学教室里", "图书馆书架间", "现代办公室中", "咖啡馆里",
         "夜晚的城市街头", "公园长椅上", "海边沙滩上", "山顶上", "樱花树下", "雨天的街道上",
         "地铁车厢里", "厨房里", "卧室床边", "健身房中", "操场跑道上", "草原上", "雪地里",
         "便利店门口", "音乐节现场", "美术馆展厅里", "老城巷子里", "天台边缘"]

ACT_REAL = ["微笑看镜头", "侧脸看向窗外", "低头看书", "喝咖啡", "走路", "跑步", "自拍", "比耶",
            "抱着猫", "弹吉他", "写字", "做饭", "打伞", "回头看向镜头", "双手捧着马克杯",
            "坐在草地上", "举着手机拍照", "和朋友聊天", "背着背包前进", "张开双臂"]
ACT_ANIME = ["抱着书本", "挥剑", "施法", "坐在窗边看书", "撑透明雨伞", "在草原上奔跑",
             "抬头仰望天空", "双手合十祈祷"]
ACT_3D = ["玩积木", "举着红色积木大笑", "站在餐车旁比大拇指", "在瀑布边探险", "抱着大玩偶",
          "骑着自行车", "在草地上野餐"]
ACT_ILL = ["站在花田里", "走在雨中", "坐在台阶上", "望向远方", "手捧星星"]

LIGHT = ["午后阳光从窗户照进来", "温暖的室内灯光", "夜晚霓虹灯", "逆光剪影", "阴天柔和漫射光",
         "清晨的金色光线", "闪光灯直打", "烛光", "舞台灯光", "雪地反光", "夕阳西下", "顶灯照明"]
STYLE_EXTRA = ["浅景深", "电影感", "胶片质感", "高清细节", "柔和布光", "高对比度", "低饱和度",
               "明亮色调", "暗色调", "体积光", "写实摄影", "纪实感"]

TEXT_ITEMS = [
    "招牌上用手写中文字写着“深夜咖啡”",
    "海报顶部用粗体中文字写着“限时特惠”",
    "书页上是清晰的中文正文",
    "红色横幅上写着“欢迎光临”",
    "菜单上用中文字写着“红烧牛肉面”",
    "店门口的灯箱上写着“24小时营业”",
    "黑板上的粉笔字写着“今日推荐”",
    "包装盒上印着“手工制作”",
    "路牌上写着“中山路”",
    "锦旗上写着金字“妙手回春”",
]
TEXT_SCENE = ["咖啡馆木质招牌特写", "便利店门口的招牌", "餐厅菜单特写", "书店橱窗", "地铁站指示牌",
              "商店橱窗海报", "奶茶店灯箱"]


def gen(n_target=9000):
    out, seen = [], set()

    def add(p):
        p = " ".join(p.split())
        if len(p) < 8 or p in seen:
            return
        seen.add(p)
        out.append(p)

    # 1) 真人生活照
    for subj, scene, act, light, extra in itertools.product(SUBJ_REAL, SCENE, ACT_REAL, LIGHT, STYLE_EXTRA):
        if R.random() > 0.012:
            continue
        add(f"{subj}{act}，{scene}，{light}，{extra}")
    # 2) 二次元
    for subj, act, scene, light in itertools.product(SUBJ_ANIME, ACT_ANIME, SCENE, LIGHT):
        if R.random() > 0.02:
            continue
        add(f"{subj}{act}，{scene}，{light}，清晰的线稿与上色")
    # 3) 3D
    for subj, act, scene, light in itertools.product(SUBJ_3D, ACT_3D, SCENE, LIGHT):
        if R.random() > 0.02:
            continue
        add(f"{subj}{act}，{scene}，{light}，卡通材质")
    # 4) 插画
    for subj, act, scene in itertools.product(SUBJ_ILL, ACT_ILL, SCENE):
        if R.random() > 0.02:
            continue
        add(f"{subj}{act}，{scene}，艺术感构图")
    # 5) 中文图内文字（重点域）
    for t, sc in itertools.product(TEXT_ITEMS, TEXT_SCENE):
        add(f"{sc}，{t}，旁边有小灯泡装饰，浅景深")
    # 6) 合影 / 多人
    for n in [2, 3, 4, 5, 6, 8]:
        for scene in SCENE:
            for act in ["站成一排微笑看镜头", "围坐在一起聊天", "在户外合影", "并排走在路上"]:
                if R.random() > 0.05:
                    continue
                add(f"{n}个同事在{scene}{act}，室内自然光，横幅构图")
                add(f"{n}位朋友在{scene}{act}，{R.choice(LIGHT)}")
    # 7) 特写 / 手部
    for act in ["双手捧着一只马克杯", "右手握着菜刀切菜", "左手按着纸右手写字", "双手比出心形",
                "用手指捏着一枚硬币", "弹钢琴", "弹吉他", "系鞋带"]:
        for scene in SCENE:
            if R.random() > 0.04:
                continue
            add(f"一个人{act}，{scene}，手部特写，{R.choice(LIGHT)}")

    R.shuffle(out)
    return out[:n_target]


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("-n", type=int, default=9000)
    ap.add_argument("--out", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\distill\corpus.jsonl")
    args = ap.parse_args()
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    ps = gen(args.n)
    with open(args.out, "w", encoding="utf-8") as f:
        for i, p in enumerate(ps):
            f.write(json.dumps({"id": f"p{i:06d}", "prompt": p}, ensure_ascii=False) + "\n")
    print(f"生成 {len(ps)} 条 -> {args.out}")
    for p in ps[:8]:
        print("  ", p)
