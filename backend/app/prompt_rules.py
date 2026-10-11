"""Composable, identifiable behavior rules; observational state is rendered elsewhere."""

from .companion_contract_generated import RULES

RULE_IDS = frozenset(rule[0] for rule in RULES)
SCOPES = frozenset(scope for _, scopes, _ in RULES for scope in scopes)


def render_rules(scopes, *, disabled_ids=frozenset()):
    scopes, disabled_ids = frozenset(scopes), frozenset(disabled_ids)
    if not scopes <= SCOPES or not disabled_ids <= RULE_IDS:
        raise ValueError("unknown companion rule or scope")
    return "\n".join(f"[{identifier}] {text}" for identifier, applies_to, text in RULES
                     if identifier not in disabled_ids and scopes.intersection(applies_to))


def behavior_rules(scopes, *, disabled_ids=frozenset()):
    return "\n<behavior_rules>\n" + render_rules(scopes, disabled_ids=disabled_ids) + "\n</behavior_rules>"
