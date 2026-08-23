from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from copy import deepcopy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from router_eval.evaluator import evaluate_predictions  # noqa: E402


class ReleaseReadinessTest(unittest.TestCase):
    def setUp(self) -> None:
        manifest_path = ROOT / "corpus" / "manifest.json"
        self.manifest_bytes = manifest_path.read_bytes()
        manifest = json.loads(self.manifest_bytes)
        corpus_path = ROOT / "corpus" / manifest["file"]
        self.cases = [json.loads(line) for line in corpus_path.read_text().splitlines() if line]
        predictions = [
            {"id": case["id"], "output": json.dumps(case["expected"])}
            for case in self.cases
        ]
        self.artifact_hash = "1" * 64
        self.corpus_manifest_hash = hashlib.sha256(self.manifest_bytes).hexdigest()
        self.evaluation = evaluate_predictions(self.cases, predictions)
        self.evaluation["corpus"] = {
            "frozen": True,
            "productionEligible": True,
            "manifestSha256": self.corpus_manifest_hash,
        }
        self.evaluation["evaluatedArtifact"] = {
            "id": "router-test-1",
            "sha256": self.artifact_hash,
        }

    def run_check(self, provenance: dict) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as directory:
            evaluation_path = Path(directory) / "evaluation.json"
            provenance_path = Path(directory) / "provenance.json"
            evaluation_path.write_text(json.dumps(self.evaluation, sort_keys=True))
            provenance["evaluation"]["reportSha256"] = hashlib.sha256(
                evaluation_path.read_bytes()
            ).hexdigest()
            provenance_path.write_text(json.dumps(provenance, sort_keys=True))
            return subprocess.run(
                [
                    sys.executable,
                    str(ROOT / "scripts" / "check_release_readiness.py"),
                    "--evaluation",
                    str(evaluation_path),
                    "--provenance",
                    str(provenance_path),
                ],
                check=False,
                capture_output=True,
                text=True,
            )

    def complete_provenance(self) -> dict:
        digest = "2" * 64
        return {
            "schemaVersion": 1,
            "artifactRole": "ROUTER",
            "releaseStatus": "READY_FOR_PUBLICATION",
            "grammarVersion": 1,
            "language": "en",
            "baseModel": {
                "modelId": "google/functiongemma-270m-it",
                "immutableRevision": "checkpoint-commit",
                "sha256": digest,
            },
            "training": {
                "repositoryCommit": "training-commit",
                "experimentConfigSha256": digest,
                "trainingManifestSha256": digest,
                "seed": 7,
                "hyperparametersSha256": digest,
                "packageLockSha256": digest,
                "syntheticOnly": True,
                "personalQuestionsUsedForTraining": False,
            },
            "conversion": {
                "tool": "converter-name",
                "immutableRevision": "converter-commit",
                "commandSha256": digest,
                "runtimeVersion": "v0.16.1",
                "functionGemmaChatTemplatePreserved": True,
                "manualToolCallingVerified": True,
                "constrainedOutputVerified": True,
            },
            "artifact": {
                "id": "router-test-1",
                "version": "1",
                "fileName": "router.litertlm",
                "bytes": 123,
                "sha256": self.artifact_hash,
                "source": "immutable-release-url",
                "rollbackArtifactId": "router-test-0",
            },
            "evaluation": {
                "productionCorpus": True,
                "independentHumanReviewComplete": True,
                "corpusManifestSha256": self.corpus_manifest_hash,
                "reportSha256": "filled-by-test",
                "artifactSha256": self.artifact_hash,
                "allRouterGatesPassed": True,
                "balancedOperationReviewPassed": True,
            },
            "deviceEvaluation": {
                "intendedDevice": "synthetic-device-record",
                "artifactSha256": self.artifact_hash,
                "warmRouterP95Millis": 900,
                "peakRssBytes": 1000,
                "outOfMemoryObserved": False,
                "serviceDeathObserved": False,
                "severeThermalObserved": False,
                "routerWriterAlternationPassed": True,
                "oneRoleResidencyPassed": True,
            },
            "distributionReview": {
                "termsUrl": "https://ai.google.dev/gemma/terms",
                "termsVersionReviewed": "2026-04-01",
                "prohibitedUseUrl": "https://ai.google.dev/gemma/prohibited_use_policy",
                "termsAcceptedBy": "accountable-reviewer",
                "termsAcceptedAtUtc": "2026-08-23T00:00:00Z",
                "decision": "APPROVED",
                "approvedBy": "accountable-reviewer",
                "approvalRecord": "review-record",
                "agreementCopySha256": digest,
                "noticeFileSha256": digest,
                "modifiedFilesMarked": True,
                "downstreamUseRestrictionsProvided": True,
                "prohibitedUseReviewed": True,
            },
            "signedManifest": {
                "manifestSha256": digest,
                "signingKeyId": "pinned-key",
                "signatureVerified": True,
                "independentRedownloadVerified": True,
            },
        }

    def test_complete_evidence_record_passes_structural_check(self) -> None:
        completed = self.run_check(self.complete_provenance())
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        self.assertTrue(json.loads(completed.stdout)["ready"])

    def test_template_defaults_fail_closed(self) -> None:
        provenance = json.loads(
            (ROOT / "templates" / "ARTIFACT_PROVENANCE.template.json").read_text()
        )
        completed = self.run_check(deepcopy(provenance))
        self.assertEqual(1, completed.returncode)
        result = json.loads(completed.stdout)
        self.assertFalse(result["ready"])
        self.assertTrue(any("redistribution" in error for error in result["errors"]))


if __name__ == "__main__":
    unittest.main()
