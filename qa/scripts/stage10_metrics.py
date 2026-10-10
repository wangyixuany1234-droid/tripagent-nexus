#!/usr/bin/env python3
"""Deterministic Stage 10 metric calculations.

This module never asks a model to judge another model. OCR values, ranking
positions, dates, money, conflicts, and JUnit outcomes are scored by rules.
"""

from __future__ import annotations

import math
import statistics
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation
from typing import Any

BUSINESS_FIELDS = (
    "type",
    "provider",
    "origin",
    "destination",
    "hotelName",
    "hotelAddress",
    "transportNumber",
    "confirmationNumber",
    "startTime",
    "endTime",
    "travelerCount",
    "amount",
    "currency",
)
DATE_FIELDS = ("startTime", "endTime")
LOCATION_FIELDS = ("origin", "destination", "hotelName", "hotelAddress")


def measured(numerator: float, denominator: int, *, value: float | None = None) -> dict:
    if denominator <= 0:
        return not_measured("NO_ELIGIBLE_CASES")
    observed = float(numerator) / denominator if value is None else value
    return {
        "status": "MEASURED",
        "value": round(observed, 4),
        "numerator": numerator,
        "denominator": denominator,
    }


def not_measured(reason: str) -> dict:
    return {
        "status": "NOT_MEASURED",
        "value": None,
        "numerator": None,
        "denominator": None,
        "reason": reason,
    }


def score_ocr(dataset: dict, observations: list[dict]) -> dict:
    cases = {item["id"]: item for item in dataset["ocrCases"]}
    by_id = {item["id"]: item for item in observations}
    field_tp = field_fp = field_fn = 0
    dates_correct = dates_total = 0
    amounts_correct = amounts_total = 0
    locations_correct = locations_total = 0
    types_correct = types_total = 0
    low_confidence_hits = low_confidence_total = 0
    duplicate_hits = duplicate_total = 0
    irrelevant_hits = irrelevant_total = 0
    case_results: list[dict] = []

    for case_id, case in cases.items():
        observation = by_id.get(case_id)
        if observation is None:
            case_results.append(
                {
                    "id": case_id,
                    "scenario": case["scenario"],
                    "status": "NOT_EXECUTED",
                }
            )
            continue
        response = observation.get("response") or {}
        actual = (
            response.get("bookings", []) if observation.get("statusCode") == 200 else []
        )
        expected = case["expectedBookings"]
        pairs = _align_bookings(expected, actual)
        case_tp = case_fp = case_fn = 0
        for gold, predicted in pairs:
            gold_fields = _business_values(gold)
            predicted_fields = _business_values(predicted)
            for name, predicted_value in predicted_fields.items():
                if name in gold_fields and _equal_value(
                    name, gold_fields[name], predicted_value
                ):
                    field_tp += 1
                    case_tp += 1
                else:
                    field_fp += 1
                    case_fp += 1
            for name, gold_value in gold_fields.items():
                if name not in predicted_fields or not _equal_value(
                    name, gold_value, predicted_fields[name]
                ):
                    field_fn += 1
                    case_fn += 1
                if name in DATE_FIELDS:
                    dates_total += 1
                    dates_correct += int(
                        name in predicted_fields
                        and _equal_value(name, gold_value, predicted_fields[name])
                    )
                if name in LOCATION_FIELDS:
                    locations_total += 1
                    locations_correct += int(
                        name in predicted_fields
                        and _equal_value(name, gold_value, predicted_fields[name])
                    )
            if "type" in gold_fields:
                types_total += 1
                types_correct += int(
                    "type" in predicted_fields
                    and _equal_value(
                        "type", gold_fields["type"], predicted_fields["type"]
                    )
                )
            if "amount" in gold_fields:
                amounts_total += 1
                amounts_correct += int(
                    "amount" in predicted_fields
                    and _equal_value(
                        "amount", gold_fields["amount"], predicted_fields["amount"]
                    )
                    and gold_fields.get("currency") == predicted_fields.get("currency")
                )

        expected_low = set(case.get("expectedLowConfidenceFields", []))
        if expected_low:
            predicted_low = {
                field
                for booking in actual
                for field in booking.get("missingFields", [])
            }
            low_confidence_hits += len(expected_low & predicted_low)
            low_confidence_total += len(expected_low)

        for duplicate in case.get("expectedDuplicateGroups", []):
            duplicate_total += 1
            confirmation = duplicate["confirmationNumber"]
            count = sum(
                booking.get("confirmationNumber") == confirmation for booking in actual
            )
            duplicate_hits += int(count == duplicate["count"])

        if case.get("expectRejectedOrEmpty"):
            irrelevant_total += 1
            irrelevant_hits += int(
                observation.get("statusCode") in {415, 422} or not actual
            )

        case_precision, case_recall, case_f1 = _prf(case_tp, case_fp, case_fn)
        case_results.append(
            {
                "id": case_id,
                "scenario": case["scenario"],
                "status": "MEASURED",
                "statusCode": observation.get("statusCode"),
                "fieldPrecision": case_precision,
                "fieldRecall": case_recall,
                "fieldF1": case_f1,
                "expectedBookingCount": len(expected),
                "actualBookingCount": len(actual),
                "artifactSha256": observation.get("artifactSha256"),
            }
        )

    precision, recall, f1 = _prf(field_tp, field_fp, field_fn)
    return {
        "fieldPrecision": measured(field_tp, field_tp + field_fp, value=precision),
        "fieldRecall": measured(field_tp, field_tp + field_fn, value=recall),
        "fieldF1": measured(
            2 * field_tp,
            2 * field_tp + field_fp + field_fn,
            value=f1,
        )
        if field_tp + field_fp + field_fn
        else not_measured("NO_GOLD_OR_PREDICTED_FIELDS"),
        "dateAccuracy": measured(dates_correct, dates_total),
        "amountAccuracy": measured(amounts_correct, amounts_total),
        "locationAccuracy": measured(locations_correct, locations_total),
        "orderTypeAccuracy": measured(types_correct, types_total),
        "lowConfidenceFieldRecall": measured(low_confidence_hits, low_confidence_total),
        "duplicateOrderRecognitionRate": measured(duplicate_hits, duplicate_total),
        "irrelevantImageRejectionRate": measured(irrelevant_hits, irrelevant_total),
        "cases": case_results,
    }


def score_rag(dataset: dict, observations: list[dict]) -> dict:
    cases = {item["id"]: item for item in dataset["ragCases"]}
    by_id = {item["id"]: item for item in observations}
    k = int(dataset["retrievalParameters"]["metricK"])
    before_rankings: list[tuple[list[str], dict[str, int]]] = []
    after_rankings: list[tuple[list[str], dict[str, int]]] = []
    evidence_hits = evidence_total = 0
    refusal_hits = refusal_total = 0
    isolation_hits = isolation_total = 0
    changed_cases = rank_shift = rank_items = 0
    case_results: list[dict] = []

    for case_id, case in cases.items():
        observation = by_id.get(case_id)
        if observation is None:
            case_results.append(
                {
                    "id": case_id,
                    "scenario": case["scenario"],
                    "status": "NOT_EXECUTED",
                }
            )
            continue
        response = observation.get("response") or {}
        before = observation.get("beforeRerank", [])
        after = [item.get("sourceId") for item in response.get("evidence", [])]
        after = [item for item in after if item]
        relevance = {key: int(value) for key, value in case["relevance"].items()}
        if relevance:
            before_rankings.append((before, relevance))
            after_rankings.append((after, relevance))
        required = set(case.get("requiredEvidence", []))
        if required:
            evidence_total += 1
            evidence_hits += int(required <= set(after))
        if case.get("expectNoEvidence"):
            refusal_total += 1
            refusal_hits += int(
                response.get("evidenceInsufficient") is True and not after
            )
        forbidden = set(case.get("forbiddenEvidence", []))
        if forbidden:
            isolation_total += 1
            isolation_hits += int(not (forbidden & set(after)))

        common = set(before) & set(after)
        shift = sum(abs(before.index(item) - after.index(item)) for item in common)
        rank_shift += shift
        rank_items += len(common)
        changed = before[:k] != after[:k]
        changed_cases += int(changed)
        case_results.append(
            {
                "id": case_id,
                "scenario": case["scenario"],
                "status": "MEASURED",
                "statusCode": observation.get("statusCode"),
                "beforeRerank": before,
                "afterRerank": after,
                "requiredEvidenceCovered": required <= set(after),
                "forbiddenEvidenceFound": sorted(forbidden & set(after)),
                "rerankChangedTopK": changed,
            }
        )

    before_metrics = _aggregate_rank_metrics(before_rankings, k)
    after_metrics = _aggregate_rank_metrics(after_rankings, k)
    eligible = len(after_rankings)
    return {
        "recallAtK": _averaged_score(after_metrics["recallAtK"], eligible)
        if eligible
        else not_measured("NO_RELEVANCE_JUDGMENTS"),
        "mrr": _averaged_score(after_metrics["mrr"], eligible)
        if eligible
        else not_measured("NO_RELEVANCE_JUDGMENTS"),
        "nDcgAtK": _averaged_score(after_metrics["nDcgAtK"], eligible)
        if eligible
        else not_measured("NO_RELEVANCE_JUDGMENTS"),
        "rerankDifference": {
            "status": "MEASURED" if eligible else "NOT_MEASURED",
            "before": before_metrics if eligible else None,
            "after": after_metrics if eligible else None,
            "delta": {
                key: round(after_metrics[key] - before_metrics[key], 4)
                for key in before_metrics
            }
            if eligible
            else None,
            "changedCaseRate": round(changed_cases / len(observations), 4)
            if observations
            else None,
            "meanAbsoluteRankShift": round(rank_shift / rank_items, 4)
            if rank_items
            else 0.0,
        },
        "evidenceCoverageRate": measured(evidence_hits, evidence_total),
        "noEvidenceRefusalRate": measured(refusal_hits, refusal_total),
        "userMemoryIsolationRate": measured(isolation_hits, isolation_total),
        "cases": case_results,
    }


def summarize_repetitions(reports: list[dict]) -> dict:
    metric_paths: set[tuple[str, ...]] = set()
    for report in reports:
        _collect_metric_paths(report, (), metric_paths)
    summary: dict[str, Any] = {}
    for path in sorted(metric_paths):
        values = []
        for report in reports:
            metric = _get_path(report, path)
            if (
                isinstance(metric, dict)
                and metric.get("status") == "MEASURED"
                and isinstance(metric.get("value"), (int, float))
            ):
                values.append(float(metric["value"]))
        destination = summary
        for part in path[:-1]:
            destination = destination.setdefault(part, {})
        destination[path[-1]] = {
            "status": "MEASURED"
            if len(values) == len(reports) and values
            else "INCOMPLETE",
            "values": values,
            "mean": round(statistics.fmean(values), 4) if values else None,
            "minimum": round(min(values), 4) if values else None,
            "maximum": round(max(values), 4) if values else None,
            "populationStandardDeviation": round(statistics.pstdev(values), 4)
            if values
            else None,
        }
    canonical = [_stable_metric_view(report) for report in reports]
    return {
        "repetitions": len(reports),
        "identicalMeasuredMetrics": bool(canonical)
        and all(item == canonical[0] for item in canonical[1:]),
        "metrics": summary,
    }


def _averaged_score(value: float, case_count: int) -> dict:
    return {
        **measured(round(value * case_count, 4), case_count, value=value),
        "aggregation": "MEAN_OF_PER_CASE_SCORES",
    }


def _align_bookings(
    expected: list[dict], actual: list[dict]
) -> list[tuple[dict, dict]]:
    unmatched = list(actual)
    pairs: list[tuple[dict, dict]] = []
    for gold in expected:
        confirmation = gold.get("confirmationNumber")
        match_index = next(
            (
                index
                for index, item in enumerate(unmatched)
                if confirmation and item.get("confirmationNumber") == confirmation
            ),
            None,
        )
        if match_index is None and unmatched:
            match_index = 0
        predicted = unmatched.pop(match_index) if match_index is not None else {}
        pairs.append((gold, predicted))
    pairs.extend(({}, item) for item in unmatched)
    return pairs


def _business_values(booking: dict) -> dict[str, Any]:
    return {
        name: booking[name]
        for name in BUSINESS_FIELDS
        if name in booking and booking[name] is not None
    }


def _equal_value(name: str, expected: Any, actual: Any) -> bool:
    if name == "amount":
        try:
            return Decimal(str(expected)) == Decimal(str(actual))
        except InvalidOperation:
            return False
    if name in DATE_FIELDS:
        return _normalized_datetime(expected) == _normalized_datetime(actual)
    if isinstance(expected, str) and isinstance(actual, str):
        return " ".join(expected.casefold().split()) == " ".join(
            actual.casefold().split()
        )
    return expected == actual


def _normalized_datetime(value: Any) -> datetime | None:
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.astimezone(UTC)


def _prf(tp: int, fp: int, fn: int) -> tuple[float, float, float]:
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return round(precision, 4), round(recall, 4), round(f1, 4)


def _aggregate_rank_metrics(
    rankings: list[tuple[list[str], dict[str, int]]], k: int
) -> dict[str, float]:
    if not rankings:
        return {"recallAtK": 0.0, "mrr": 0.0, "nDcgAtK": 0.0}
    recalls = []
    reciprocal_ranks = []
    ndcgs = []
    for ranking, relevance in rankings:
        relevant = {item for item, grade in relevance.items() if grade > 0}
        top = ranking[:k]
        recalls.append(len(relevant & set(top)) / len(relevant))
        reciprocal_ranks.append(
            next(
                (1 / (index + 1) for index, item in enumerate(top) if item in relevant),
                0.0,
            )
        )
        dcg = sum(
            (2 ** relevance.get(item, 0) - 1) / math.log2(index + 2)
            for index, item in enumerate(top)
        )
        ideal = sorted(relevance.values(), reverse=True)[:k]
        ideal_dcg = sum(
            (2**grade - 1) / math.log2(index + 2) for index, grade in enumerate(ideal)
        )
        ndcgs.append(dcg / ideal_dcg if ideal_dcg else 0.0)
    return {
        "recallAtK": round(statistics.fmean(recalls), 4),
        "mrr": round(statistics.fmean(reciprocal_ranks), 4),
        "nDcgAtK": round(statistics.fmean(ndcgs), 4),
    }


def _collect_metric_paths(
    value: Any, path: tuple[str, ...], output: set[tuple[str, ...]]
) -> None:
    if isinstance(value, dict):
        if "status" in value and "value" in value:
            output.add(path)
            return
        for key, child in value.items():
            if key not in {"cases", "rerankDifference"}:
                _collect_metric_paths(child, (*path, key), output)


def _get_path(value: dict, path: tuple[str, ...]) -> Any:
    current: Any = value
    for part in path:
        if not isinstance(current, dict):
            return None
        current = current.get(part)
    return current


def _stable_metric_view(value: Any) -> Any:
    if isinstance(value, dict):
        if "status" in value and "value" in value:
            return (value.get("status"), value.get("value"))
        return {
            key: _stable_metric_view(child)
            for key, child in sorted(value.items())
            if key not in {"cases", "rerankDifference"}
        }
    return value
