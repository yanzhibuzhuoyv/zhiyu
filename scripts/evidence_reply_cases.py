"""Frozen predeclared synthetic cases; no model output enters this dataset."""

import json
from copy import deepcopy
from pathlib import Path

DATASET_VERSION = "single-evidence-reply-independent-v1"
GOLD_ORIGIN = "predeclared independent synthetic cases before model outputs"
CURRENT_UTC = "2026-10-07T04:00:00+00:00"
_BANK = json.loads(Path(__file__).with_suffix(".json").read_text(encoding="utf-8"))


def build_cases():
    return deepcopy(_BANK)
