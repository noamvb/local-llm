#!/usr/bin/env python3
"""Emit a label-blind safe-abstain baseline for evaluator smoke tests.

This is not the planned deterministic product router. It intentionally routes nothing and
must fail supported/clarification accuracy gates; its only purpose is to provide a stable
floor and prove that safety alone cannot be mistaken for useful routing accuracy.
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    manifest = json.loads((ROOT / "corpus" / "manifest.json").read_text(encoding="utf-8"))
    corpus_path = ROOT / "corpus" / manifest["file"]
    abstention = json.dumps(
        {"decision": "UNSUPPORTED", "limitationId": "OUT_OF_GRAMMAR"},
        separators=(",", ":"),
        sort_keys=True,
    )
    for line in corpus_path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        case = json.loads(line)
        print(json.dumps({"id": case["id"], "output": abstention}, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
