from __future__ import annotations

import hashlib
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from router_eval.contract import (  # noqa: E402
    ALL_METRICS,
    CLARIFICATION_IDS,
    FILTER_FIELDS,
    GROUPINGS,
    LIMITATION_IDS,
    parse_model_output,
    validate_case,
    validate_router_decision,
)


def query_decision(sources: list[str] | None = None) -> dict:
    selected = sources or ["CANNSHEET"]
    metrics = ["cannsheet.consumption_count"]
    if "POOP_SCHEDULE" in selected:
        metrics.append("poop.entry_count")
    return {
        "decision": "QUERY",
        "query": {
            "grammarVersion": 1,
            "sources": selected,
            "metrics": metrics,
            "period": {"mode": "LAST_DAYS", "days": 30},
            "comparison": "NONE",
            "groupings": [],
            "filters": [],
            "resultMode": "FACTS",
        },
    }


class RouterContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.single_envelope = {
            "initiatingSource": "CANNSHEET",
            "allowedSources": ["CANNSHEET"],
            "explicitCrossApp": False,
        }

    def test_accepts_bounded_single_source_query(self) -> None:
        self.assertEqual([], validate_router_decision(query_decision(), self.single_envelope))

    def test_rejects_implicit_cross_app_widening(self) -> None:
        errors = validate_router_decision(
            query_decision(["CANNSHEET", "POOP_SCHEDULE"]), self.single_envelope
        )
        self.assertTrue(any("widened" in error for error in errors), errors)
        self.assertTrue(any("implicit cross-app" in error for error in errors), errors)

    def test_accepts_explicit_cross_app_inside_envelope(self) -> None:
        envelope = {
            "initiatingSource": "POOP_SCHEDULE",
            "allowedSources": ["CANNSHEET", "POOP_SCHEDULE"],
            "explicitCrossApp": True,
        }
        self.assertEqual(
            [],
            validate_router_decision(
                query_decision(["CANNSHEET", "POOP_SCHEDULE"]), envelope
            ),
        )

    def test_rejects_selected_source_without_a_metric(self) -> None:
        envelope = {
            "initiatingSource": "CANNSHEET",
            "allowedSources": ["CANNSHEET", "POOP_SCHEDULE"],
            "explicitCrossApp": True,
        }
        decision = query_decision(["CANNSHEET", "POOP_SCHEDULE"])
        decision["query"]["metrics"] = ["cannsheet.consumption_count"]
        errors = validate_router_decision(decision, envelope)
        self.assertTrue(any("has no requested metric" in error for error in errors), errors)

    def test_rejects_model_confidence_and_unknown_fields(self) -> None:
        decision = query_decision()
        decision["confidence"] = 0.999
        errors = validate_router_decision(decision, self.single_envelope)
        self.assertTrue(any("unknown fields" in error for error in errors), errors)

    def test_rejects_invalid_calendar_date(self) -> None:
        decision = query_decision()
        decision["query"]["period"] = {
            "mode": "EXPLICIT_DATES",
            "start": "2026-02-30",
            "end": "2026-03-01",
        }
        errors = validate_router_decision(decision, self.single_envelope)
        self.assertTrue(any("not a calendar date" in error for error in errors), errors)

    def test_note_search_cannot_return_fact_text(self) -> None:
        decision = {
            "decision": "QUERY",
            "query": {
                "grammarVersion": 1,
                "sources": ["POOP_SCHEDULE"],
                "metrics": ["poop.entry_count"],
                "period": {"mode": "ALL_TIME"},
                "comparison": "NONE",
                "groupings": [],
                "filters": [
                    {
                        "source": "POOP_SCHEDULE",
                        "field": "poop.note_query",
                        "operator": "TEXT_CONTAINS",
                        "value": "synthetic",
                    }
                ],
                "resultMode": "FACTS",
            },
        }
        envelope = {
            "initiatingSource": "POOP_SCHEDULE",
            "allowedSources": ["POOP_SCHEDULE"],
            "explicitCrossApp": False,
        }
        errors = validate_router_decision(decision, envelope)
        self.assertTrue(any("count-and-navigation" in error for error in errors), errors)

    def test_rejects_bidi_or_format_characters_in_output(self) -> None:
        decision = {"decision": "UNSUPPORTED", "limitationId": "OUT_OF_GRAMMAR\u202e"}
        errors = validate_router_decision(decision, self.single_envelope)
        self.assertTrue(any("U+202E" in error for error in errors), errors)

    def test_raw_parser_rejects_duplicate_keys(self) -> None:
        parsed, errors = parse_model_output(
            '{"decision":"UNSUPPORTED","decision":"QUERY","limitationId":"READ_ONLY"}'
        )
        self.assertIsNone(parsed)
        self.assertTrue(any("duplicate JSON key" in error for error in errors), errors)

    def test_raw_parser_rejects_markdown_fence_and_trailing_text(self) -> None:
        for output in (
            '```json\n{"decision":"UNSUPPORTED","limitationId":"READ_ONLY"}\n```',
            '{"decision":"UNSUPPORTED","limitationId":"READ_ONLY"} ignored',
        ):
            with self.subTest(output=output):
                parsed, errors = parse_model_output(output)
                self.assertIsNone(parsed)
                self.assertTrue(errors)


class CorpusFreezeTest(unittest.TestCase):
    def test_manifest_hash_and_family_split_are_consistent(self) -> None:
        manifest = json.loads((ROOT / "corpus" / "manifest.json").read_text())
        corpus_path = ROOT / "corpus" / manifest["file"]
        self.assertEqual(manifest["sha256"], hashlib.sha256(corpus_path.read_bytes()).hexdigest())
        rows = [json.loads(line) for line in corpus_path.read_text().splitlines() if line]
        self.assertEqual(manifest["caseCount"], len(rows))
        families: dict[str, set[str]] = {}
        for row in rows:
            self.assertEqual([], validate_case(row), row["id"])
            families.setdefault(row["templateFamily"], set()).add(row["split"])
        self.assertFalse(
            {family: splits for family, splits in families.items() if len(splits) > 1}
        )

    def test_checked_schema_enums_match_deterministic_validator(self) -> None:
        schema = json.loads(
            (ROOT / "schema" / "router_decision.draft-1.schema.json").read_text()
        )
        definitions = schema["$defs"]
        self.assertEqual(set(ALL_METRICS), set(definitions["metric"]["enum"]))
        self.assertEqual(
            set(GROUPINGS),
            set(
                definitions["aggregateQuery"]["properties"]["groupings"]["items"]["enum"]
            ),
        )
        self.assertEqual(
            CLARIFICATION_IDS,
            set(definitions["clarifyDecision"]["properties"]["clarificationId"]["enum"]),
        )
        self.assertEqual(
            LIMITATION_IDS,
            set(definitions["unsupportedDecision"]["properties"]["limitationId"]["enum"]),
        )
        schema_filter_fields: set[str] = set()
        for variant in definitions["filter"]["oneOf"]:
            field_schema = variant["properties"]["field"]
            if "const" in field_schema:
                schema_filter_fields.add(field_schema["const"])
            else:
                schema_filter_fields.update(field_schema["enum"])
        self.assertEqual(FILTER_FIELDS, schema_filter_fields)


if __name__ == "__main__":
    unittest.main()
