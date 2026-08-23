#!/usr/bin/env python3
"""Evaluate complete raw model outputs against the frozen seed corpus."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from router_eval.evaluator import evaluate_predictions  # noqa: E402


def load_jsonl(path: Path) -> list[dict]:
    rows = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(f"{path}:{line_number}: invalid JSONL: {error}") from error
        rows.append(value)
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--predictions", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    parser.add_argument(
        "--artifact-sha256",
        help="SHA-256 of the exact model artifact that produced these outputs",
    )
    parser.add_argument(
        "--artifact-id",
        help="immutable artifact identifier; required by release readiness, optional for baselines",
    )
    parser.add_argument(
        "--allow-failing-gates",
        action="store_true",
        help="write/print a failing report but return zero (never use for a release gate)",
    )
    args = parser.parse_args()

    manifest_path = ROOT / "corpus" / "manifest.json"
    manifest_bytes = manifest_path.read_bytes()
    manifest = json.loads(manifest_bytes)
    cases = load_jsonl(ROOT / "corpus" / manifest["file"])
    predictions = load_jsonl(args.predictions)
    report = evaluate_predictions(cases, predictions)
    report["corpus"] = {
        "file": manifest["file"],
        "sha256": manifest["sha256"],
        "frozen": manifest["frozen"],
        "productionEligible": manifest.get("productionEligible", False),
        "version": manifest["version"],
        "manifestSha256": hashlib.sha256(manifest_bytes).hexdigest(),
    }
    report["evaluatedArtifact"] = {
        "id": args.artifact_id,
        "sha256": args.artifact_sha256,
        "predictionsSha256": hashlib.sha256(args.predictions.read_bytes()).hexdigest(),
    }
    rendered = json.dumps(report, indent=2, ensure_ascii=False, sort_keys=True) + "\n"
    if args.report:
        args.report.write_text(rendered, encoding="utf-8")
    else:
        print(rendered, end="")
    if report["gates"]["routerEvaluationPassed"] or args.allow_failing_gates:
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
