"""Legacy import name backed by the shared, identifiable companion rule set."""

from .prompt_rules import render_rules

MEMORY_VOICE = render_rules({"proactive"})
