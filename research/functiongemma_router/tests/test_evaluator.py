from __future__ import annotations

import json
import sys
import unittest
from copy import deepcopy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from router_eval.evaluator import evaluate_predictions  # noqa: E402


def load_cases() -> list[dict]:
    manifest = json.loads((ROOT / "corpus" / "manifest.json").read_text())
    path = ROOT / "corpus" / manifest["file"]
    return [json.loads(line) for line in path.read_text().splitlines() if line]


def gold_predictions(cases: list[dict]) -> list[dict]:
    return [
        {
            "id": case["id"],
            "output": json.dumps(case["expected"], separators=(",", ":"), sort_keys=True),
        }
        for case in cases
    ]


class EvaluatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.cases = load_cases()

    def test_gold_predictions_pass_every_gate(self) -> None:
        report = evaluate_predictions(self.cases, gold_predictions(self.cases))
        self.assertTrue(report["gates"]["routerEvaluationPassed"], report["failures"])
        self.assertEqual(1.0, report["rates"]["parseable"]["rate"])
        self.assertEqual(
            1.0, report["rates"]["supportedExactFullQueryAndArguments"]["rate"]
        )
        self.assertIsNotNone(report["rates"]["parseable"]["wilson95"])
        self.assertEqual(
            1.0,
            report["rates"]["exactByOperation"]["QUERY_CANNSHEET_ACTIVITY"]["rate"],
        )
        self.assertIsNotNone(
            report["rates"]["exactByOperation"]["QUERY_CANNSHEET_ACTIVITY"]["wilson95"]
        )

    def test_source_widening_is_rejected_by_policy_boundary(self) -> None:
        predictions = gold_predictions(self.cases)
        index = next(
            index
            for index, case in enumerate(self.cases)
            if case["id"] == "supported-cannsheet-last-30-count"
        )
        widened = deepcopy(self.cases[index]["expected"])
        widened["query"]["sources"] = ["CANNSHEET", "POOP_SCHEDULE"]
        widened["query"]["metrics"].append("poop.entry_count")
        predictions[index]["output"] = json.dumps(widened)
        report = evaluate_predictions(self.cases, predictions)
        self.assertFalse(report["gates"]["schemaAndPolicyValid100"])
        self.assertTrue(report["gates"]["implicitCrossAppPrevented100"])
        failure = next(item for item in report["failures"] if item["id"] == self.cases[index]["id"])
        self.assertTrue(any("widened" in issue for issue in failure["validationIssues"]))

    def test_malformed_forbidden_output_is_non_executable_but_fails_parse_gate(self) -> None:
        predictions = gold_predictions(self.cases)
        index = next(
            index
            for index, case in enumerate(self.cases)
            if case["id"] == "unsupported-delete-entry"
        )
        predictions[index]["output"] = "not json"
        report = evaluate_predictions(self.cases, predictions)
        self.assertFalse(report["gates"]["parseable100"])
        self.assertTrue(report["gates"]["forbiddenRejected100"])

    def test_valid_forbidden_query_fails_rejection_gate(self) -> None:
        predictions = gold_predictions(self.cases)
        index = next(
            index
            for index, case in enumerate(self.cases)
            if case["id"] == "unsupported-delete-entry"
        )
        unsafe_query = deepcopy(
            next(case["expected"] for case in self.cases if case["category"] == "SUPPORTED")
        )
        unsafe_query["query"]["sources"] = ["POOP_SCHEDULE"]
        unsafe_query["query"]["metrics"] = ["poop.entry_count"]
        predictions[index]["output"] = json.dumps(unsafe_query)
        report = evaluate_predictions(self.cases, predictions)
        self.assertFalse(report["gates"]["forbiddenRejected100"])

    def test_missing_and_duplicate_predictions_are_reported(self) -> None:
        predictions = gold_predictions(self.cases)
        duplicate = deepcopy(predictions[0])
        predictions = predictions[1:] + [duplicate, duplicate]
        report = evaluate_predictions(self.cases, predictions)
        self.assertFalse(report["gates"]["allPredictionsPresentAndUnique"])
        self.assertTrue(any("duplicate" in issue for issue in report["inputIssues"]))


if __name__ == "__main__":
    unittest.main()
