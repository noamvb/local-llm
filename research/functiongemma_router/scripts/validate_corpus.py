#!/usr/bin/env python3
"""Validate the seed corpus, family split, and recorded hash without network access."""

from __future__ import annotations

import hashlib
import json
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from router_eval.contract import (  # noqa: E402
    ALL_METRICS,
    CLARIFICATION_IDS,
    COMPARISONS,
    FILTER_FIELDS,
    GROUPINGS,
    LIMITATION_IDS,
    RESULT_MODES,
    validate_case,
)


def load_jsonl(path: Path) -> list[dict]:
    rows = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError as error:
            raise ValueError(f"{path}:{line_number}: invalid JSON: {error}") from error
    return rows


def main() -> int:
    manifest_path = ROOT / "corpus" / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    corpus_path = ROOT / "corpus" / manifest["file"]
    corpus_bytes = corpus_path.read_bytes()
    actual_hash = hashlib.sha256(corpus_bytes).hexdigest()
    errors: list[str] = []
    if manifest.get("sha256") != actual_hash:
        errors.append(
            f"manifest sha256 mismatch: expected {manifest.get('sha256')}, actual {actual_hash}"
        )
    rows = load_jsonl(corpus_path)
    if manifest.get("caseCount") != len(rows):
        errors.append(
            f"manifest caseCount mismatch: expected {manifest.get('caseCount')}, actual {len(rows)}"
        )

    seen_ids: set[str] = set()
    family_splits: dict[str, set[str]] = defaultdict(set)
    split_counts: dict[str, int] = defaultdict(int)
    category_counts: dict[str, int] = defaultdict(int)
    forbidden_coverage: set[str] = set()
    grammar_coverage = {
        "metrics": set(),
        "groupings": set(),
        "filters": set(),
        "periodModes": set(),
        "comparisons": set(),
        "resultModes": set(),
        "clarificationIds": set(),
        "limitationIds": set(),
    }
    for row in rows:
        case_id = row.get("id", "<missing>")
        if case_id in seen_ids:
            errors.append(f"{case_id}: duplicate case id")
        seen_ids.add(case_id)
        for error in validate_case(row):
            errors.append(f"{case_id}: {error}")
        family = row.get("templateFamily")
        split = row.get("split")
        if isinstance(family, str) and isinstance(split, str):
            family_splits[family].add(split)
        split_counts[str(split)] += 1
        category_counts[str(row.get("category"))] += 1
        if isinstance(row.get("forbiddenOperation"), str):
            forbidden_coverage.add(row["forbiddenOperation"])
        expected = row.get("expected", {})
        if expected.get("decision") == "QUERY":
            query = expected.get("query", {})
            grammar_coverage["metrics"].update(query.get("metrics", []))
            grammar_coverage["groupings"].update(query.get("groupings", []))
            grammar_coverage["filters"].update(
                item.get("field") for item in query.get("filters", []) if isinstance(item, dict)
            )
            period = query.get("period", {})
            if isinstance(period, dict):
                grammar_coverage["periodModes"].add(period.get("mode"))
            grammar_coverage["comparisons"].add(query.get("comparison"))
            grammar_coverage["resultModes"].add(query.get("resultMode"))
        elif expected.get("decision") == "CLARIFY":
            grammar_coverage["clarificationIds"].add(expected.get("clarificationId"))
        elif expected.get("decision") == "UNSUPPORTED":
            grammar_coverage["limitationIds"].add(expected.get("limitationId"))

    leaking = {family: sorted(splits) for family, splits in family_splits.items() if len(splits) > 1}
    if leaking:
        errors.append(f"template families cross splits: {leaking}")
    required_forbidden = set(manifest.get("requiredForbiddenOperations", []))
    missing_forbidden = sorted(required_forbidden - forbidden_coverage)
    if missing_forbidden:
        errors.append(f"missing forbidden-operation coverage: {missing_forbidden}")
    required_grammar = {
        "metrics": set(ALL_METRICS),
        "groupings": set(GROUPINGS),
        "filters": set(FILTER_FIELDS),
        "periodModes": {"LAST_DAYS", "EXPLICIT_DATES", "ALL_TIME"},
        "comparisons": set(COMPARISONS),
        "resultModes": set(RESULT_MODES),
        "clarificationIds": set(CLARIFICATION_IDS),
        "limitationIds": set(LIMITATION_IDS),
    }
    for name, required_values in required_grammar.items():
        missing_values = sorted(required_values - grammar_coverage[name])
        if missing_values:
            errors.append(f"missing {name} grammar coverage: {missing_values}")

    expected_split_counts = manifest.get("splitCounts")
    if expected_split_counts != dict(sorted(split_counts.items())):
        errors.append(
            f"manifest splitCounts mismatch: expected {expected_split_counts}, "
            f"actual {dict(sorted(split_counts.items()))}"
        )
    expected_category_counts = manifest.get("categoryCounts")
    if expected_category_counts != dict(sorted(category_counts.items())):
        errors.append(
            f"manifest categoryCounts mismatch: expected {expected_category_counts}, "
            f"actual {dict(sorted(category_counts.items()))}"
        )

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(
        json.dumps(
            {
                "status": "valid",
                "caseCount": len(rows),
                "sha256": actual_hash,
                "splitCounts": dict(sorted(split_counts.items())),
                "categoryCounts": dict(sorted(category_counts.items())),
                "templateFamilyCount": len(family_splits),
                "forbiddenOperationCount": len(forbidden_coverage),
                "grammarCoverage": {
                    name: len(values) for name, values in sorted(grammar_coverage.items())
                },
            },
            indent=2,
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
