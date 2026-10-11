"""Generate immutable rule definitions; --check rejects stale generated sources."""

import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "shared" / "companion-contract.json"
PYTHON = ROOT / "backend" / "app" / "companion_contract_generated.py"
KOTLIN = ROOT / "android/app/src/main/java/com/zhiyu/app/domain/CompanionContractGenerated.kt"


def read_contract():
    raw = SOURCE.read_bytes()
    value = json.loads(raw)
    if value["version"] != 1 or value["retrieval_states"] != ["ok", "not_searched", "partial"]:
        raise ValueError("unsupported companion contract version or retrieval states")
    rules = value["rules"]
    ids = [rule["id"] for rule in rules]
    if len(ids) != len(set(ids)):
        raise ValueError("duplicate companion rule id")
    for rule in rules:
        if set(rule) != {"id", "applies_to", "text"} or not re.fullmatch(r"[a-z][a-z_]{1,63}", rule["id"]):
            raise ValueError("invalid companion rule")
        if not rule["text"].strip() or not rule["applies_to"] or not set(rule["applies_to"]) <= {
            "chat", "proactive", "legacy_memory", "knowledge",
        }:
            raise ValueError("invalid companion rule scope or text")
    return value, hashlib.sha256(raw).hexdigest()


def python_source(value, digest):
    lines = ["# Generated from shared/companion-contract.json; do not edit.",
             f'CONTRACT_SHA256 = "{digest}"', "RETRIEVAL_STATES = ('ok', 'not_searched', 'partial')",
             "RULES = ("]
    for rule in value["rules"]:
        lines.append(f"    ({rule['id']!r}, {tuple(rule['applies_to'])!r}, {rule['text']!r}),")
    lines.extend([")", ""])
    return "\n".join(lines)


def kotlin_quote(value):
    return json.dumps(value, ensure_ascii=False).replace("$", "\\$")


def kotlin_source(value, digest):
    lines = ["// Generated from shared/companion-contract.json; do not edit.",
             "package com.zhiyu.app.domain", "",
             "enum class LocalRetrievalStatus(val wireValue: String) {",
             "    " + ", ".join(f"{state.upper()}({kotlin_quote(state)})" for state in value["retrieval_states"]) + ";",
             "}", "",
             "internal data class CompanionRule(val id: String, val appliesTo: Set<String>, val text: String)",
             "internal object CompanionContractGenerated {",
             f'    const val SHA256 = "{digest}"', "    val rules = listOf("]
    for rule in value["rules"]:
        scopes = ", ".join(kotlin_quote(scope) for scope in rule["applies_to"])
        lines.append(f"        CompanionRule({kotlin_quote(rule['id'])}, setOf({scopes}), {kotlin_quote(rule['text'])}),")
    lines.extend(["    )", "}", ""])
    return "\n".join(lines)


def generate(*, check=False):
    value, digest = read_contract()
    outputs = {PYTHON: python_source(value, digest), KOTLIN: kotlin_source(value, digest)}
    stale = []
    for path, source in outputs.items():
        current = path.read_text(encoding="utf-8") if path.is_file() else None
        if current == source:
            continue
        if check:
            stale.append(path.relative_to(ROOT).as_posix())
        else:
            path.write_text(source, encoding="utf-8", newline="\n")
    if stale:
        raise ValueError("stale companion contract: " + ", ".join(stale))
    return digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    try:
        digest = generate(check=args.check)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"{error}\n")
    print(f"companion contract {'verified' if args.check else 'generated'}: {digest}")


if __name__ == "__main__":
    main()
