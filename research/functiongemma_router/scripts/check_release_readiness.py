#!/usr/bin/env python3
"""Fail closed unless evaluation, provenance, device, and review records are complete.

This checks recorded evidence. It does not decide whether redistribution is legally
permitted and cannot replace the accountable human review recorded in the provenance.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any

SHA256 = re.compile(r"^[0-9a-f]{64}$")
PLACEHOLDER_WORDS = {"REQUIRED", "NOT_REVIEWED", "BLOCKED_TEMPLATE"}


def nested(value: dict[str, Any], path: str) -> Any:
    current: Any = value
    for key in path.split("."):
        if not isinstance(current, dict) or key not in current:
            return None
        current = current[key]
    return current


def meaningful(value: Any) -> bool:
    if not isinstance(value, str) or not value.strip():
        return False
    upper = value.upper()
    return not any(word in upper for word in PLACEHOLDER_WORDS)


def require(condition: bool, message: str, errors: list[str]) -> None:
    if not condition:
        errors.append(message)


def positive_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evaluation", type=Path, required=True)
    parser.add_argument("--provenance", type=Path, required=True)
    args = parser.parse_args()

    evaluation_bytes = args.evaluation.read_bytes()
    evaluation = json.loads(evaluation_bytes)
    provenance = json.loads(args.provenance.read_text(encoding="utf-8"))
    errors: list[str] = []

    require(
        nested(evaluation, "gates.routerEvaluationPassed") is True,
        "evaluation: router gates did not all pass",
        errors,
    )
    require(
        nested(evaluation, "corpus.frozen") is True,
        "evaluation: corpus is not recorded as frozen",
        errors,
    )
    require(
        nested(evaluation, "corpus.productionEligible") is True,
        "evaluation: corpus is explicitly not production eligible",
        errors,
    )
    evaluation_artifact = nested(evaluation, "evaluatedArtifact.sha256")
    require(
        isinstance(evaluation_artifact, str) and bool(SHA256.fullmatch(evaluation_artifact)),
        "evaluation: exact artifact SHA-256 is missing",
        errors,
    )
    require(
        meaningful(nested(evaluation, "evaluatedArtifact.id")),
        "evaluation: immutable artifact id is missing",
        errors,
    )

    require(provenance.get("artifactRole") == "ROUTER", "provenance: wrong role", errors)
    require(
        provenance.get("releaseStatus") == "READY_FOR_PUBLICATION",
        "provenance: release status is not READY_FOR_PUBLICATION",
        errors,
    )
    require(provenance.get("schemaVersion") == 1, "provenance: wrong schema version", errors)
    require(provenance.get("grammarVersion") == 1, "provenance: wrong grammar version", errors)
    require(provenance.get("language") == "en", "provenance: first release is English only", errors)
    require(
        nested(provenance, "baseModel.modelId") == "google/functiongemma-270m-it",
        "provenance: unexpected base model id",
        errors,
    )
    require(
        nested(provenance, "distributionReview.termsUrl")
        == "https://ai.google.dev/gemma/terms",
        "provenance: canonical Gemma terms URL is missing",
        errors,
    )
    require(
        nested(provenance, "distributionReview.prohibitedUseUrl")
        == "https://ai.google.dev/gemma/prohibited_use_policy",
        "provenance: canonical prohibited-use URL is missing",
        errors,
    )
    for path in (
        "baseModel.immutableRevision",
        "training.repositoryCommit",
        "training.experimentConfigSha256",
        "training.trainingManifestSha256",
        "training.hyperparametersSha256",
        "training.packageLockSha256",
        "conversion.tool",
        "conversion.immutableRevision",
        "conversion.commandSha256",
        "conversion.runtimeVersion",
        "artifact.id",
        "artifact.version",
        "artifact.fileName",
        "artifact.source",
        "artifact.rollbackArtifactId",
        "distributionReview.termsVersionReviewed",
        "distributionReview.termsAcceptedBy",
        "distributionReview.termsAcceptedAtUtc",
        "distributionReview.approvedBy",
        "distributionReview.approvalRecord",
        "signedManifest.signingKeyId",
    ):
        require(meaningful(nested(provenance, path)), f"provenance: incomplete {path}", errors)

    for path in (
        "baseModel.sha256",
        "artifact.sha256",
        "evaluation.corpusManifestSha256",
        "evaluation.reportSha256",
        "evaluation.artifactSha256",
        "distributionReview.agreementCopySha256",
        "distributionReview.noticeFileSha256",
        "signedManifest.manifestSha256",
    ):
        value = nested(provenance, path)
        require(
            isinstance(value, str) and bool(SHA256.fullmatch(value)),
            f"provenance: invalid {path}",
            errors,
        )

    artifact_hash = nested(provenance, "artifact.sha256")
    require(
        nested(provenance, "artifact.id") == nested(evaluation, "evaluatedArtifact.id"),
        "provenance: artifact id does not match evaluated artifact",
        errors,
    )
    require(
        artifact_hash == evaluation_artifact,
        "provenance: artifact hash does not match evaluated artifact",
        errors,
    )
    require(
        nested(provenance, "evaluation.artifactSha256") == artifact_hash,
        "provenance: evaluation artifact hash mismatch",
        errors,
    )
    require(
        nested(provenance, "deviceEvaluation.artifactSha256") == artifact_hash,
        "provenance: device artifact hash mismatch",
        errors,
    )
    require(
        nested(provenance, "evaluation.reportSha256")
        == hashlib.sha256(evaluation_bytes).hexdigest(),
        "provenance: evaluation report hash mismatch",
        errors,
    )
    require(
        nested(provenance, "evaluation.corpusManifestSha256")
        == nested(evaluation, "corpus.manifestSha256"),
        "provenance: corpus manifest hash mismatch or absent from report",
        errors,
    )

    for path in (
        "training.syntheticOnly",
        "conversion.functionGemmaChatTemplatePreserved",
        "conversion.manualToolCallingVerified",
        "conversion.constrainedOutputVerified",
        "evaluation.productionCorpus",
        "evaluation.independentHumanReviewComplete",
        "evaluation.allRouterGatesPassed",
        "evaluation.balancedOperationReviewPassed",
        "deviceEvaluation.routerWriterAlternationPassed",
        "deviceEvaluation.oneRoleResidencyPassed",
        "distributionReview.modifiedFilesMarked",
        "distributionReview.downstreamUseRestrictionsProvided",
        "distributionReview.prohibitedUseReviewed",
        "signedManifest.signatureVerified",
        "signedManifest.independentRedownloadVerified",
    ):
        require(nested(provenance, path) is True, f"provenance: {path} is not true", errors)
    require(
        nested(provenance, "training.personalQuestionsUsedForTraining") is False,
        "provenance: personal questions must not be training data",
        errors,
    )
    require(
        isinstance(nested(provenance, "training.seed"), int)
        and not isinstance(nested(provenance, "training.seed"), bool),
        "provenance: training seed is not an integer",
        errors,
    )
    require(
        nested(provenance, "distributionReview.decision") == "APPROVED",
        "provenance: redistribution is not explicitly approved",
        errors,
    )
    require(
        positive_number(nested(provenance, "artifact.bytes")),
        "provenance: artifact byte size is not positive",
        errors,
    )
    require(
        meaningful(nested(provenance, "deviceEvaluation.intendedDevice")),
        "provenance: intended device is missing",
        errors,
    )
    warm_p95 = nested(provenance, "deviceEvaluation.warmRouterP95Millis")
    require(
        positive_number(warm_p95) and warm_p95 <= 1000,
        "provenance: warm router p95 does not meet the <=1000 ms gate",
        errors,
    )
    require(
        positive_number(nested(provenance, "deviceEvaluation.peakRssBytes")),
        "provenance: peak RSS was not recorded",
        errors,
    )
    for path in (
        "deviceEvaluation.outOfMemoryObserved",
        "deviceEvaluation.serviceDeathObserved",
        "deviceEvaluation.severeThermalObserved",
    ):
        require(nested(provenance, path) is False, f"provenance: {path} must be false", errors)

    result = {
        "ready": not errors,
        "errors": errors,
        "notice": "Recorded-evidence check only; accountable licensing review remains authoritative.",
    }
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
