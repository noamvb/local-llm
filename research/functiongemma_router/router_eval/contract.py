"""Strict validator for the provisional router grammar and evaluation cases.

This intentionally does not depend on a general JSON Schema package. The checked schema is
the constrained-generation description; this module is the second, deterministic policy
boundary that also enforces source permission and source-scoped semantic rules.
"""

from __future__ import annotations

import json
import re
import unicodedata
from datetime import date
from typing import Any, Iterable, Mapping


GRAMMAR_VERSION = 1
MAX_OUTPUT_BYTES = 4_096
MAX_UTTERANCE_CHARS = 512
SOURCES = ("CANNSHEET", "POOP_SCHEDULE")
SOURCE_ORDER = {value: index for index, value in enumerate(SOURCES)}

METRICS_BY_SOURCE = {
    "CANNSHEET": (
        "cannsheet.consumption_count",
        "cannsheet.active_days",
        "cannsheet.time_band_counts",
        "cannsheet.weekday_counts",
        "cannsheet.product_log_counts",
        "cannsheet.purchase_count",
        "cannsheet.recorded_spend",
        "cannsheet.recorded_spend_coverage",
        "cannsheet.inventory_remaining",
    ),
    "POOP_SCHEDULE": (
        "poop.entry_count",
        "poop.active_days",
        "poop.frequency_per_week",
        "poop.average_interval",
        "poop.average_duration",
        "poop.time_band_counts",
        "poop.weekday_counts",
        "poop.bristol_type_counts",
        "poop.colour_counts",
        "poop.size_counts",
        "poop.symptom_counts",
        "poop.rating_averages",
    ),
}
ALL_METRICS = tuple(metric for source in SOURCES for metric in METRICS_BY_SOURCE[source])
METRIC_ORDER = {value: index for index, value in enumerate(ALL_METRICS)}

GROUPINGS = (
    "DAY",
    "WEEK",
    "WEEKDAY",
    "TIME_BAND",
    "PRODUCT",
    "BRISTOL_TYPE",
    "COLOUR",
    "SIZE",
)
GROUPING_ORDER = {value: index for index, value in enumerate(GROUPINGS)}
GROUPINGS_BY_SOURCE = {
    "CANNSHEET": {"DAY", "WEEK", "WEEKDAY", "TIME_BAND", "PRODUCT"},
    "POOP_SCHEDULE": {
        "DAY",
        "WEEK",
        "WEEKDAY",
        "TIME_BAND",
        "BRISTOL_TYPE",
        "COLOUR",
        "SIZE",
    },
}

COMPARISONS = {"NONE", "PREVIOUS_EQUAL_PERIOD"}
RESULT_MODES = {"FACTS", "COUNT_AND_NAVIGATION", "EXPLANATION"}
CLARIFICATION_IDS = {
    "CHOOSE_SOURCE",
    "CHOOSE_METRIC",
    "CHOOSE_DATE",
    "CHOOSE_PRODUCT",
    "SPLIT_REQUEST",
    "UNKNOWN_SOURCE",
    "UNKNOWN_METRIC",
    "UNKNOWN_PRODUCT",
}
LIMITATION_IDS = {
    "READ_ONLY",
    "MEDICAL_OR_CAUSAL",
    "RAW_DATA_UNAVAILABLE",
    "PROJECTION_UNAVAILABLE",
    "OUT_OF_GRAMMAR",
    "UNSAFE_OR_MALFORMED",
}
FILTER_FIELDS = {
    "cannsheet.product_name",
    "poop.note_query",
    "poop.bristol_type",
    "poop.colour",
    "poop.size",
    "poop.symptom",
}

CASE_SPLITS = {"train", "validation", "test", "adversarial"}
CASE_CATEGORIES = {"SUPPORTED", "AMBIGUOUS", "UNSUPPORTED", "ADVERSARIAL"}
IDENTIFIER = re.compile(r"^[a-z0-9][a-z0-9._-]{0,95}$")
OPERATION = re.compile(r"^[A-Z][A-Z0-9_]{0,63}$")
DATE_TEXT = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")


class DuplicateKeyError(ValueError):
    """Raised when model output contains an ambiguous duplicate JSON key."""


def _object_without_duplicate_keys(pairs: Iterable[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateKeyError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def parse_model_output(output: Any) -> tuple[Any | None, list[str]]:
    """Parse the complete raw model output, rejecting wrappers and duplicate keys."""

    if not isinstance(output, str):
        return None, ["output: must be the model's raw string"]
    try:
        encoded = output.encode("utf-8")
    except UnicodeEncodeError as error:
        return None, [f"output: invalid Unicode: {error}"]
    if len(encoded) > MAX_OUTPUT_BYTES:
        return None, [f"output: exceeds {MAX_OUTPUT_BYTES} UTF-8 bytes"]
    try:
        value = json.loads(output, object_pairs_hook=_object_without_duplicate_keys)
    except (json.JSONDecodeError, DuplicateKeyError) as error:
        return None, [f"output: invalid JSON: {error}"]
    return value, []


def _exact_keys(
    value: Mapping[str, Any], required: set[str], path: str, errors: list[str]
) -> None:
    missing = sorted(required - set(value))
    extra = sorted(set(value) - required)
    if missing:
        errors.append(f"{path}: missing fields {missing}")
    if extra:
        errors.append(f"{path}: unknown fields {extra}")


def _reject_unsafe_text(value: Any, path: str, errors: list[str]) -> None:
    if isinstance(value, str):
        for character in value:
            category = unicodedata.category(character)
            if category in {"Cc", "Cf"}:
                errors.append(
                    f"{path}: contains forbidden control/format character U+{ord(character):04X}"
                )
                return
    elif isinstance(value, list):
        for index, item in enumerate(value):
            _reject_unsafe_text(item, f"{path}[{index}]", errors)
    elif isinstance(value, dict):
        for key, item in value.items():
            _reject_unsafe_text(key, f"{path}.<key>", errors)
            _reject_unsafe_text(item, f"{path}.{key}", errors)


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _validate_period(period: Any, errors: list[str]) -> None:
    path = "decision.query.period"
    if not isinstance(period, dict):
        errors.append(f"{path}: must be an object")
        return
    mode = period.get("mode")
    if mode == "LAST_DAYS":
        _exact_keys(period, {"mode", "days"}, path, errors)
        days = period.get("days")
        if not _is_int(days) or not 1 <= days <= 366:
            errors.append(f"{path}.days: must be an integer from 1 through 366")
    elif mode == "EXPLICIT_DATES":
        _exact_keys(period, {"mode", "start", "end"}, path, errors)
        start_text = period.get("start")
        end_text = period.get("end")
        parsed: list[date] = []
        for field, value in (("start", start_text), ("end", end_text)):
            if not isinstance(value, str) or not DATE_TEXT.fullmatch(value):
                errors.append(f"{path}.{field}: must be an ISO yyyy-mm-dd date")
                continue
            try:
                parsed.append(date.fromisoformat(value))
            except ValueError:
                errors.append(f"{path}.{field}: is not a calendar date")
        if len(parsed) == 2:
            if parsed[0] > parsed[1]:
                errors.append(f"{path}: start must not be after end")
            elif (parsed[1] - parsed[0]).days > 366:
                errors.append(f"{path}: explicit range must span at most 366 days")
    elif mode == "ALL_TIME":
        _exact_keys(period, {"mode"}, path, errors)
    else:
        errors.append(f"{path}.mode: unknown period mode")


def _validate_filter(filter_value: Any, index: int, sources: set[str], errors: list[str]) -> None:
    path = f"decision.query.filters[{index}]"
    if not isinstance(filter_value, dict):
        errors.append(f"{path}: must be an object")
        return
    _exact_keys(filter_value, {"source", "field", "operator", "value"}, path, errors)
    source = filter_value.get("source")
    field = filter_value.get("field")
    operator = filter_value.get("operator")
    value = filter_value.get("value")
    if source not in SOURCES:
        errors.append(f"{path}.source: unknown source")
    elif source not in sources:
        errors.append(f"{path}.source: source is not present in query.sources")

    if field == "cannsheet.product_name":
        if source != "CANNSHEET" or operator != "EXACT_NAME":
            errors.append(f"{path}: invalid source/operator for Cannsheet product filter")
        if not isinstance(value, str) or not 1 <= len(value) <= 80:
            errors.append(f"{path}.value: product name must contain 1 through 80 characters")
    elif field == "poop.note_query":
        if source != "POOP_SCHEDULE" or operator != "TEXT_CONTAINS":
            errors.append(f"{path}: invalid source/operator for local note search")
        if not isinstance(value, str) or not 1 <= len(value) <= 120:
            errors.append(f"{path}.value: note query must contain 1 through 120 characters")
    elif field == "poop.bristol_type":
        if source != "POOP_SCHEDULE" or operator != "EQUALS":
            errors.append(f"{path}: invalid source/operator for Bristol type filter")
        if not _is_int(value) or not 1 <= value <= 7:
            errors.append(f"{path}.value: Bristol type must be an integer from 1 through 7")
    elif field in {"poop.colour", "poop.size", "poop.symptom"}:
        if source != "POOP_SCHEDULE" or operator != "EQUALS":
            errors.append(f"{path}: invalid source/operator for Poop Schedule filter")
        if not isinstance(value, str) or not 1 <= len(value) <= 40:
            errors.append(f"{path}.value: filter value must contain 1 through 40 characters")
    else:
        errors.append(f"{path}.field: unknown filter field")


def _validate_query(query: Any, envelope: Mapping[str, Any] | None, errors: list[str]) -> None:
    path = "decision.query"
    if not isinstance(query, dict):
        errors.append(f"{path}: must be an object")
        return
    required = {
        "grammarVersion",
        "sources",
        "metrics",
        "period",
        "comparison",
        "groupings",
        "filters",
        "resultMode",
    }
    _exact_keys(query, required, path, errors)
    if query.get("grammarVersion") != GRAMMAR_VERSION:
        errors.append(f"{path}.grammarVersion: must equal {GRAMMAR_VERSION}")

    sources_value = query.get("sources")
    sources: list[str] = []
    if not isinstance(sources_value, list) or not 1 <= len(sources_value) <= 2:
        errors.append(f"{path}.sources: must contain one or two sources")
    else:
        sources = sources_value
        if any(source not in SOURCES for source in sources):
            errors.append(f"{path}.sources: contains an unknown source")
        if len(set(sources)) != len(sources):
            errors.append(f"{path}.sources: sources must be unique")
        if sources != sorted(sources, key=lambda value: SOURCE_ORDER.get(value, 99)):
            errors.append(f"{path}.sources: sources are not in canonical order")
    source_set = set(sources)

    metrics_value = query.get("metrics")
    if not isinstance(metrics_value, list) or not 1 <= len(metrics_value) <= 8:
        errors.append(f"{path}.metrics: must contain one through eight metrics")
    else:
        if any(metric not in ALL_METRICS for metric in metrics_value):
            errors.append(f"{path}.metrics: contains an unknown metric")
        if len(set(metrics_value)) != len(metrics_value):
            errors.append(f"{path}.metrics: metrics must be unique")
        if metrics_value != sorted(metrics_value, key=lambda value: METRIC_ORDER.get(value, 999)):
            errors.append(f"{path}.metrics: metrics are not in canonical order")
        metric_sources: set[str] = set()
        for metric in metrics_value:
            metric_source = next(
                (source for source, values in METRICS_BY_SOURCE.items() if metric in values), None
            )
            if metric_source:
                metric_sources.add(metric_source)
            if metric_source and metric_source not in source_set:
                errors.append(f"{path}.metrics: {metric} has no matching query source")
        for source in source_set - metric_sources:
            errors.append(f"{path}.metrics: selected source {source} has no requested metric")

    _validate_period(query.get("period"), errors)
    comparison = query.get("comparison")
    if comparison not in COMPARISONS:
        errors.append(f"{path}.comparison: unknown comparison")
    if comparison == "PREVIOUS_EQUAL_PERIOD" and isinstance(query.get("period"), dict):
        if query["period"].get("mode") == "ALL_TIME":
            errors.append(f"{path}.comparison: cannot compare an all-time period")

    groupings_value = query.get("groupings")
    if not isinstance(groupings_value, list) or len(groupings_value) > 2:
        errors.append(f"{path}.groupings: must contain at most two groupings")
    else:
        if any(grouping not in GROUPINGS for grouping in groupings_value):
            errors.append(f"{path}.groupings: contains an unknown grouping")
        if len(set(groupings_value)) != len(groupings_value):
            errors.append(f"{path}.groupings: groupings must be unique")
        if groupings_value != sorted(
            groupings_value, key=lambda value: GROUPING_ORDER.get(value, 999)
        ):
            errors.append(f"{path}.groupings: groupings are not in canonical order")
        for grouping in groupings_value:
            if not any(grouping in GROUPINGS_BY_SOURCE.get(source, set()) for source in source_set):
                errors.append(f"{path}.groupings: {grouping} is unsupported by selected sources")

    filters_value = query.get("filters")
    if not isinstance(filters_value, list) or len(filters_value) > 4:
        errors.append(f"{path}.filters: must contain at most four filters")
        filters: list[Any] = []
    else:
        filters = filters_value
        serialized = [canonical_json(item) for item in filters]
        if len(set(serialized)) != len(serialized):
            errors.append(f"{path}.filters: filters must be unique")
        for index, filter_value in enumerate(filters):
            _validate_filter(filter_value, index, source_set, errors)

    result_mode = query.get("resultMode")
    if result_mode not in RESULT_MODES:
        errors.append(f"{path}.resultMode: unknown result mode")
    if result_mode == "COUNT_AND_NAVIGATION" and not filters:
        errors.append(f"{path}.resultMode: navigation mode requires at least one filter")
    if any(isinstance(item, dict) and item.get("field") == "poop.note_query" for item in filters):
        if result_mode != "COUNT_AND_NAVIGATION":
            errors.append(f"{path}: note search may return only count-and-navigation")

    if envelope is not None and sources:
        initiating = envelope.get("initiatingSource")
        allowed = envelope.get("allowedSources")
        explicit_cross_app = envelope.get("explicitCrossApp")
        if initiating not in SOURCES:
            errors.append("envelope.initiatingSource: unknown source")
        if not isinstance(allowed, list) or not 1 <= len(allowed) <= 2:
            errors.append("envelope.allowedSources: must contain one or two sources")
            allowed_set: set[str] = set()
        else:
            allowed_set = set(allowed)
            if any(source not in SOURCES for source in allowed) or len(allowed_set) != len(allowed):
                errors.append("envelope.allowedSources: contains invalid or duplicate sources")
        if not isinstance(explicit_cross_app, bool):
            errors.append("envelope.explicitCrossApp: must be Boolean")
        if not source_set.issubset(allowed_set):
            errors.append(f"{path}.sources: model widened the deterministic source envelope")
        if not explicit_cross_app:
            if len(sources) != 1 or sources[0] != initiating:
                errors.append(f"{path}.sources: implicit cross-app or non-initiating source access")
        elif len(sources) == 2 and len(allowed_set) != 2:
            errors.append(f"{path}.sources: two sources were not allowed")


def validate_router_decision(
    value: Any, envelope: Mapping[str, Any] | None = None
) -> list[str]:
    """Return every deterministic schema/policy error for a parsed decision."""

    errors: list[str] = []
    try:
        encoded_length = len(canonical_json(value).encode("utf-8"))
        if encoded_length > MAX_OUTPUT_BYTES:
            errors.append(f"decision: exceeds {MAX_OUTPUT_BYTES} canonical UTF-8 bytes")
    except (TypeError, ValueError):
        errors.append("decision: is not JSON-serializable")
    _reject_unsafe_text(value, "decision", errors)
    if not isinstance(value, dict):
        errors.append("decision: must be an object")
        return errors

    decision = value.get("decision")
    if decision == "QUERY":
        _exact_keys(value, {"decision", "query"}, "decision", errors)
        _validate_query(value.get("query"), envelope, errors)
    elif decision == "CLARIFY":
        _exact_keys(value, {"decision", "clarificationId"}, "decision", errors)
        if value.get("clarificationId") not in CLARIFICATION_IDS:
            errors.append("decision.clarificationId: unknown clarification identifier")
    elif decision == "UNSUPPORTED":
        _exact_keys(value, {"decision", "limitationId"}, "decision", errors)
        if value.get("limitationId") not in LIMITATION_IDS:
            errors.append("decision.limitationId: unknown limitation identifier")
    else:
        errors.append("decision.decision: must be QUERY, CLARIFY, or UNSUPPORTED")
    return errors


def validate_case(case: Any) -> list[str]:
    """Validate one synthetic evaluation case and its expected decision."""

    errors: list[str] = []
    if not isinstance(case, dict):
        return ["case: must be an object"]
    required = {
        "id",
        "templateFamily",
        "split",
        "category",
        "operation",
        "initiatingSource",
        "allowedSources",
        "explicitCrossApp",
        "utterance",
        "expected",
    }
    optional = {"forbiddenOperation", "resolverContext", "notes"}
    missing = sorted(required - set(case))
    extra = sorted(set(case) - required - optional)
    if missing:
        errors.append(f"case: missing fields {missing}")
    if extra:
        errors.append(f"case: unknown fields {extra}")

    for field in ("id", "templateFamily"):
        value = case.get(field)
        if not isinstance(value, str) or not IDENTIFIER.fullmatch(value):
            errors.append(f"case.{field}: invalid identifier")
    if case.get("split") not in CASE_SPLITS:
        errors.append("case.split: unknown split")
    category = case.get("category")
    if category not in CASE_CATEGORIES:
        errors.append("case.category: unknown category")
    operation = case.get("operation")
    if not isinstance(operation, str) or not OPERATION.fullmatch(operation):
        errors.append("case.operation: invalid operation identifier")
    forbidden = case.get("forbiddenOperation")
    if forbidden is not None and (not isinstance(forbidden, str) or not OPERATION.fullmatch(forbidden)):
        errors.append("case.forbiddenOperation: invalid operation identifier")
    if category in {"UNSUPPORTED", "ADVERSARIAL"} and forbidden is None:
        errors.append("case.forbiddenOperation: required for unsupported/adversarial cases")

    utterance = case.get("utterance")
    if not isinstance(utterance, str) or not 1 <= len(utterance) <= MAX_UTTERANCE_CHARS:
        errors.append(
            f"case.utterance: must contain 1 through {MAX_UTTERANCE_CHARS} characters"
        )
    initiating = case.get("initiatingSource")
    if initiating not in SOURCES:
        errors.append("case.initiatingSource: unknown source")
    allowed = case.get("allowedSources")
    if not isinstance(allowed, list) or not 1 <= len(allowed) <= 2:
        errors.append("case.allowedSources: must contain one or two sources")
    else:
        if any(source not in SOURCES for source in allowed) or len(set(allowed)) != len(allowed):
            errors.append("case.allowedSources: contains invalid or duplicate sources")
        if initiating not in allowed:
            errors.append("case.allowedSources: must include the initiating source")
        if allowed != sorted(allowed, key=lambda value: SOURCE_ORDER.get(value, 99)):
            errors.append("case.allowedSources: sources are not in canonical order")
    explicit_cross_app = case.get("explicitCrossApp")
    if not isinstance(explicit_cross_app, bool):
        errors.append("case.explicitCrossApp: must be Boolean")
    elif explicit_cross_app and (not isinstance(allowed, list) or len(allowed) != 2):
        errors.append("case.explicitCrossApp: requires both allowed sources")
    elif not explicit_cross_app and isinstance(allowed, list) and allowed != [initiating]:
        errors.append("case.allowedSources: a single-app case may allow only its initiating source")

    resolver_context = case.get("resolverContext")
    if resolver_context is not None:
        if not isinstance(resolver_context, dict):
            errors.append("case.resolverContext: must be an object")
        else:
            unknown = sorted(set(resolver_context) - {"knownProducts"})
            if unknown:
                errors.append(f"case.resolverContext: unknown fields {unknown}")
            known_products = resolver_context.get("knownProducts", [])
            if not isinstance(known_products, list) or any(
                not isinstance(value, str) or not 1 <= len(value) <= 80 for value in known_products
            ):
                errors.append("case.resolverContext.knownProducts: invalid product list")

    envelope = {
        "initiatingSource": initiating,
        "allowedSources": allowed,
        "explicitCrossApp": explicit_cross_app,
    }
    expected = case.get("expected")
    errors.extend(f"expected: {error}" for error in validate_router_decision(expected, envelope))
    expected_decision = expected.get("decision") if isinstance(expected, dict) else None
    if category == "SUPPORTED" and expected_decision != "QUERY":
        errors.append("case.expected: supported cases must be QUERY")
    if category == "AMBIGUOUS" and expected_decision != "CLARIFY":
        errors.append("case.expected: ambiguous cases must be CLARIFY")
    if category in {"UNSUPPORTED", "ADVERSARIAL"} and expected_decision == "QUERY":
        errors.append("case.expected: unsupported/adversarial cases must not be QUERY")
    return errors
