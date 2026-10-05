"""Reproduce bounded lexical memory-weight trials over synthetic original records.

No network, API keys, Docker or user database; reuse the same fixtures as regression tests.
Run from the repository root: backend/.venv/Scripts/python.exe scripts/eval_memory_weights.py
"""

import argparse
import json
import sys
from dataclasses import asdict, replace
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / "backend"), str(ROOT / "backend" / "tests")]

from memory_ranking_cases import NOW, cases  # noqa: E402

from app.conversation_memory import memory_terms  # noqa: E402
from app.memory_ranking import DEFAULT_WEIGHTS, select_memory  # noqa: E402


def policies():
    return {
        "relevance_only_equal_authors": replace(DEFAULT_WEIGHTS, assistant_weight=1, recency_bonus=0, event_weight=0),
        "equal_authors_with_events": replace(DEFAULT_WEIGHTS, assistant_weight=1, recency_bonus=0),
        "heavy_recency": replace(DEFAULT_WEIGHTS, recency_bonus=0.8),
        "weak_event_bonus": replace(DEFAULT_WEIGHTS, event_weight=0.35),
        "wide_24_hour_window": replace(DEFAULT_WEIGHTS, event_window_hours=24),
        "bounded_user_first_12h": DEFAULT_WEIGHTS,
    }


def evaluate(weights):
    rows = []
    for case in cases():
        selected = select_memory(case.candidates, memory_terms(case.query), nearby_events=case.events,
                                 now=NOW, scope=("owner", "chat"), weights=weights)
        selected_ids = {item.turn["sequence"] for item in selected}
        rows.append({
            "case": case.name, "expected": sorted(case.expected), "selected": sorted(selected_ids),
            "hits": len(case.expected & selected_ids), "expected_count": len(case.expected),
            "forbidden_selected": sorted(case.forbidden & selected_ids),
            "passed": case.expected <= selected_ids and not case.forbidden & selected_ids,
            "original_records": [{"user_id": item.turn["user_id"], "user_text": item.turn["user_text"],
                                  "assistant_id": item.turn["assistant_id"], "assistant_text": item.turn["assistant_text"],
                                  "user_created_at": item.turn["user_created_at"], "assistant_created_at": item.turn["assistant_created_at"],
                                  "score": round(item.score, 6), "reason": item.reason,
                                  "anchor_ids": list(item.anchor_ids), "relation_confirmed": item.relation_confirmed}
                                 for item in selected],
        })
    return {
        "weights": asdict(weights), "case_passes": sum(row["passed"] for row in rows), "case_count": len(rows),
        "hits": sum(row["hits"] for row in rows), "expected_count": sum(row["expected_count"] for row in rows),
        "forbidden_selected": sum(len(row["forbidden_selected"]) for row in rows), "cases": rows,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="Save this synthetic evaluation as a UTF-8 JSON artifact")
    args = parser.parse_args()
    report = {
        "fixture_time_utc": NOW.isoformat(), "dataset": f"{len(list(cases()))} synthetic scenarios; never user data",
        "limitation": "In-sample lexical regression comparison, not a global optimum or LLM factuality measurement. Message time is not event time. Nearby events remain unconfirmed separate originals.",
        "policies": {name: evaluate(weights) for name, weights in policies().items()},
    }
    print("policy                          cases   hits    forbidden-event-or-scope")
    for name, result in report["policies"].items():
        print(f"{name:31} {result['case_passes']:2}/{result['case_count']:<2}   {result['hits']:2}/{result['expected_count']:<2}    {result['forbidden_selected']}")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Synthetic report: {args.output.resolve()}")


if __name__ == "__main__":
    main()
