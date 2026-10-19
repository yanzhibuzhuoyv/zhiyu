"""Public-model calibration on synthetic, frozen inputs; never reads heldout files."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
from transformers import AutoTokenizer

from prepare_semantic_model import QUERY_PREFIX, REVISION, ROOT, verify_source

DOCUMENTS = [
    "我出门前会把钥匙挂到玄关的小鹿挂钩上，回家也放那里。",
    "穿衣服我最讨厌高领，一勒住脖子就觉得难受。",
    "我上台讲话前手会抖，所以喜欢先在空房间完整练一遍。",
    "我想把第一笔奖金攒起来买大提琴，暂时不买电子产品。",
    "别人把耳机开得很响我受不了，我自己总调到很低的音量。",
    "毕业时那张旧车票我一直放在床头的木盒里，舍不得扔。",
    "写笔记我习惯用蓝色钢笔，圆珠笔握久了手指疼。",
    "我喜欢拿铅笔在纸上画建筑草图，电子绘图板一直用不惯。",
    "家里突然停电时，我会去厨房最下层抽屉找蜡烛和火柴。",
    "我每周二晚饭后都会整理阳台上的盆栽，把枯叶摘掉。",
    "新衣服贴着皮肤的标签很扎，我买回来第一件事就是剪掉。",
    "洗床单我只用没有香味的洗衣液，浓重的香精味让我不舒服。",
]
POSITIVE_QUERIES = [
    "我那串开门用的东西通常收在哪儿？",
    "哪种领口会让我觉得不自在？",
    "公开发言让我紧张时，我会提前怎么准备？",
    "我第一次得到额外报酬后打算添置什么？",
    "听声音时我偏好怎样的音量？",
    "我把学生时代不舍得丢的乘车凭证存哪里了？",
    "记录事情时我惯用哪一种笔？",
    "我更愿意用什么工具做楼房的设计草稿？",
    "屋里没有电的时候，我会到什么位置拿照明的东西？",
    "我照顾家里植物的固定时间是什么？",
    "我拿到新衣服后先处理哪个地方？",
    "清洗寝具时，我对清洁用品的气味有什么要求？",
]
# Six related-but-absent attributes and six unrelated topics. Related is not evidence of an answer.
NEGATIVE_QUERIES = [
    "我家门锁的密码具体是多少？",
    "我第一次登台讲话是哪年哪月？",
    "我那张旧车票的票价是多少？",
    "我的耳机是什么牌子的？",
    "我阳台上那盆花的品种叫什么？",
    "我买床单时最常选多大的尺寸？",
    "我给汽车换轮胎时选过哪个维修店？",
    "我的身份证有效期到哪天？",
    "我学过哪门编程语言？",
    "我平时用哪种牌子的牙膏？",
    "我哪次坐飞机升到了商务舱？",
    "我手机的锁屏密码是多少？",
]
MANIFEST_SHA = "60fc9061b2d9460a879707aede1fa3283241c3f425311bc462227c49c5d9d292"
RULE = {
    "thresholds": [round(value / 100, 2) for value in range(25, 66)],
    "top1_minus_top2_gaps": [round(value / 1000, 3) for value in range(0, 101, 5)],
    "selection": "minimize 4*false_acceptances + false_fallbacks; ties prefer fewer false acceptances, then more accepted correct positives, then higher threshold, then smaller gap",
    "false_acceptance_weight": 4,
    "warning": "Cosine is not a probability. No unseen heldout data is used to choose parameters.",
}


def write_frozen(path: Path, value: dict) -> str:
    raw = json.dumps(value, ensure_ascii=False, indent=2).encode("utf-8")
    if path.exists() and path.read_bytes() != raw:
        raise RuntimeError(
            "Frozen calibration input changed; create a separately versioned experiment"
        )
    path.write_bytes(raw)
    return hashlib.sha256(raw).hexdigest()


def evaluate(scores: np.ndarray, threshold: float, gap: float) -> dict:
    top = scores.argmax(axis=1)
    sorted_scores = np.sort(scores, axis=1)
    margins = sorted_scores[:, -1] - sorted_scores[:, -2]
    accepted = (scores[np.arange(len(scores)), top] >= threshold) & (margins >= gap)
    positive = np.arange(len(scores)) < len(POSITIVE_QUERIES)
    correct = positive & (top == np.arange(len(scores)))
    false_acceptance = int(np.count_nonzero(accepted & ~correct))
    false_fallback = int(np.count_nonzero(positive & ~accepted))
    accepted_correct = int(np.count_nonzero(accepted & correct))
    return {
        "threshold": threshold,
        "top_gap": gap,
        "false_acceptances": false_acceptance,
        "false_fallbacks": false_fallback,
        "accepted_correct": accepted_correct,
        "accepted_wrong_positive": int(np.count_nonzero(accepted & positive & ~correct)),
        "accepted_no_answer": int(np.count_nonzero(accepted & ~positive)),
        "objective": 4 * false_acceptance + false_fallback,
    }


def selection_key(row: dict) -> tuple:
    return (
        row["objective"],
        row["false_acceptances"],
        -row["accepted_correct"],
        -row["threshold"],
        row["top_gap"],
    )


def main() -> None:
    source = (
        Path.home() / ".cache/huggingface/hub/models--BAAI--bge-small-zh-v1.5/snapshots" / REVISION
    )
    verify_source(source)
    folder = ROOT / "artifacts/semantic-models"
    assert hashlib.sha256((folder / "manifest.json").read_bytes()).hexdigest() == MANIFEST_SHA
    queries = POSITIVE_QUERIES + NEGATIVE_QUERIES
    inputs = {
        "documents": DOCUMENTS,
        "queries": queries,
        "expected_document": list(range(12)) + [None] * 12,
        "query_prefix": QUERY_PREFIX,
        "rule": RULE,
        "model_manifest_sha256": MANIFEST_SHA,
    }
    input_sha = write_frozen(folder / "calibration-input-v1.json", inputs)
    tokenizer = AutoTokenizer.from_pretrained(
        source, local_files_only=True, trust_remote_code=False
    )
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 2
    model = folder / "bge-small-zh-v1.5-int8.onnx"
    session = ort.InferenceSession(str(model), options, providers=["CPUExecutionProvider"])
    encoded = []
    for text in DOCUMENTS + [QUERY_PREFIX + query for query in queries]:
        feed = tokenizer(text, return_tensors="np", truncation=False)
        assert feed["input_ids"].shape[1] <= 512
        encoded.append(
            session.run(None, {key: value.astype(np.int64) for key, value in feed.items()})[0][0]
        )
    vectors = np.stack(encoded)
    scores = vectors[len(DOCUMENTS) :] @ vectors[: len(DOCUMENTS)].T
    grid = [
        evaluate(scores, threshold, gap)
        for threshold in RULE["thresholds"]
        for gap in RULE["top1_minus_top2_gaps"]
    ]
    selected = min(grid, key=selection_key)
    top = scores.argmax(axis=1)
    margins = np.sort(scores, axis=1)[:, -1] - np.sort(scores, axis=1)[:, -2]
    rows = [
        {
            "query": query,
            "expected": index if index < 12 else None,
            "top1": int(top[index]),
            "top1_score": float(scores[index, top[index]]),
            "top_gap": float(margins[index]),
            "accepted": bool(
                scores[index, top[index]] >= selected["threshold"]
                and margins[index] >= selected["top_gap"]
            ),
        }
        for index, query in enumerate(queries)
    ]
    alternatives = {
        str(budget): min(
            (row for row in grid if row["false_acceptances"] <= budget),
            key=lambda row: (
                row["false_fallbacks"],
                row["false_acceptances"],
                -row["threshold"],
                row["top_gap"],
            ),
        )
        for budget in range(4)
    }
    report = {
        "input_sha256": input_sha,
        "model_manifest_sha256": MANIFEST_SHA,
        "query_prefix": QUERY_PREFIX,
        "raw_cosine_matrix": scores.tolist(),
        "selected": selected,
        "rows": rows,
        "false_acceptance_budget_alternatives": alternatives,
        "grid": grid,
        "notice": "24 synthetic calibration cases only; selected performance is training/calibration performance, not heldout generalization.",
    }
    (folder / "calibration-report-v1.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(
        json.dumps(
            {
                "input_sha256": input_sha,
                "selected": selected,
                "alternatives": alternatives,
                "positive_top1": sum(int(top[index]) == index for index in range(12)),
                "rows": rows,
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
