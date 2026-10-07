"""One repair and a second full semantic review; nothing is streamed to a user."""

import time

from scripts.memory_answer_guard import parse_review, repair_messages, review_messages


async def audit(query, draft, evidence, generate, phase, reference_time=None):
    event = {"phase": phase, "result": None, "parsed": None, "error": None}
    try:
        result = await generate(review_messages(query, draft, evidence, reference_time), phase, "disabled")
        event = {**event, "result": result}
        if not result["grade"]["usable_pass"]:
            return {**event, "error": "incomplete_review"}
        parsed = parse_review(result["content"], draft, evidence)
    except (ValueError, TypeError, KeyError, AttributeError, RecursionError) as exc:
        return {**event, "error": type(exc).__name__}
    return {**event, "parsed": parsed}


def outcome(draft, events, start, status, candidate=None):
    checked = status != "unverified"
    return {"status": status, "draft_content": draft,
            "candidate_content": candidate if candidate is not None else draft,
            "final_content": (candidate if candidate is not None else draft) if checked else None,
            "events": events, "guard_wait_seconds": time.perf_counter() - start,
            "manual_review_required": True,
            "meaning": "Model review plus exact-source validation, not a factual correctness guarantee."}


async def guard_answer(query, draft, evidence, base_messages, generate, mode, *, reference_time=None):
    start = time.perf_counter()
    first = await audit(query, draft, evidence, generate, "review", reference_time)
    events = [first]
    if first["error"]:
        return outcome(draft, events, start, "unverified")
    if not first["parsed"]["needs_repair"]:
        return outcome(draft, events, start, "model_checked")
    try:
        messages = repair_messages(query, draft, evidence, first["parsed"], base_messages)
    except (ValueError, TypeError, KeyError, AttributeError) as exc:
        events.append({"phase": "rewrite_input", "error": type(exc).__name__})
        return outcome(draft, events, start, "unverified")
    rewritten = await generate(messages, "rewrite", mode)
    events.append({"phase": "rewrite", "result": rewritten})
    if not rewritten["grade"]["usable_pass"]:
        return outcome(draft, events, start, "unverified", rewritten["content"])
    candidate = rewritten["content"]
    second = await audit(query, candidate, evidence, generate, "recheck", reference_time)
    events.append(second)
    status = "unverified" if second["error"] or second["parsed"]["needs_repair"] else "model_checked_repaired"
    return outcome(draft, events, start, status, candidate)
