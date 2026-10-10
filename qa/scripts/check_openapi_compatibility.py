#!/usr/bin/env python3
"""Reject backward-incompatible changes to the frozen public OpenAPI contract."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import yaml

HTTP_METHODS = {"get", "put", "post", "delete", "patch", "head", "options", "trace"}
LOWER_BOUNDS = ("minimum", "exclusiveMinimum", "minLength", "minItems", "minProperties")
UPPER_BOUNDS = ("maximum", "exclusiveMaximum", "maxLength", "maxItems", "maxProperties")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", required=True)
    parser.add_argument("--candidate", required=True)
    parser.add_argument("--output", required=True)
    return parser.parse_args()


def load_contract(path: Path) -> dict[str, Any]:
    document = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise TypeError(f"OpenAPI document must be an object: {path}")
    return document


def _violation(violations: list[dict[str, str]], location: str, change: str) -> None:
    violations.append({"location": location, "change": change})


def _compare_schema(
    location: str,
    baseline: dict[str, Any],
    candidate: dict[str, Any],
    violations: list[dict[str, str]],
) -> None:
    candidate_alternatives = candidate.get("anyOf") or candidate.get("oneOf")
    if isinstance(candidate_alternatives, list):
        for alternative in candidate_alternatives:
            if not isinstance(alternative, dict):
                continue
            alternative_violations: list[dict[str, str]] = []
            _compare_schema(
                location,
                baseline,
                alternative,
                alternative_violations,
            )
            if not alternative_violations:
                return
        _violation(
            violations,
            location,
            "no candidate union branch preserves the baseline schema",
        )
        return

    for keyword in ("$ref", "type", "format", "pattern"):
        if keyword in baseline and candidate.get(keyword) != baseline[keyword]:
            _violation(
                violations,
                location,
                f"{keyword} changed from {baseline[keyword]!r} to {candidate.get(keyword)!r}",
            )

    baseline_enum = set(baseline.get("enum", []))
    candidate_enum = set(candidate.get("enum", []))
    removed_enum_values = baseline_enum - candidate_enum
    if removed_enum_values:
        _violation(
            violations,
            location,
            f"enum values removed: {sorted(removed_enum_values, key=str)}",
        )

    for keyword in LOWER_BOUNDS:
        if keyword in baseline and (
            keyword not in candidate or candidate[keyword] > baseline[keyword]
        ):
            _violation(
                violations,
                location,
                f"{keyword} tightened from {baseline[keyword]!r} to {candidate.get(keyword)!r}",
            )
    for keyword in UPPER_BOUNDS:
        if keyword in baseline and (
            keyword not in candidate or candidate[keyword] < baseline[keyword]
        ):
            _violation(
                violations,
                location,
                f"{keyword} tightened from {baseline[keyword]!r} to {candidate.get(keyword)!r}",
            )

    baseline_properties = baseline.get("properties", {})
    candidate_properties = candidate.get("properties", {})
    if isinstance(baseline_properties, dict):
        if not isinstance(candidate_properties, dict):
            _violation(violations, location, "object properties were removed")
            return
        baseline_required = set(baseline.get("required", []))
        candidate_required = set(candidate.get("required", []))
        for property_name, property_schema in baseline_properties.items():
            property_location = f"{location}.{property_name}"
            if property_name not in candidate_properties:
                _violation(violations, property_location, "property removed or renamed")
                continue
            if (property_name in baseline_required) != (
                property_name in candidate_required
            ):
                _violation(violations, property_location, "required status changed")
            if isinstance(property_schema, dict) and isinstance(
                candidate_properties[property_name], dict
            ):
                _compare_schema(
                    property_location,
                    property_schema,
                    candidate_properties[property_name],
                    violations,
                )
        for property_name in candidate_properties.keys() - baseline_properties.keys():
            if property_name in candidate_required:
                _violation(
                    violations,
                    f"{location}.{property_name}",
                    "new property is required",
                )

    baseline_items = baseline.get("items")
    candidate_items = candidate.get("items")
    if isinstance(baseline_items, dict):
        if not isinstance(candidate_items, dict):
            _violation(violations, f"{location}[]", "array item schema removed")
        else:
            _compare_schema(
                f"{location}[]", baseline_items, candidate_items, violations
            )


def _parameter_key(parameter: dict[str, Any]) -> str:
    if "$ref" in parameter:
        return str(parameter["$ref"])
    return f"{parameter.get('in')}:{parameter.get('name')}"


def _parameters(path_item: dict[str, Any], operation: dict[str, Any]) -> dict[str, Any]:
    parameters = [*path_item.get("parameters", []), *operation.get("parameters", [])]
    return {_parameter_key(parameter): parameter for parameter in parameters}


def _compare_media_types(
    location: str,
    baseline_content: dict[str, Any],
    candidate_content: dict[str, Any],
    violations: list[dict[str, str]],
) -> None:
    for media_type, media in baseline_content.items():
        media_location = f"{location}.{media_type}"
        if media_type not in candidate_content:
            _violation(violations, media_location, "content type removed")
            continue
        baseline_schema = media.get("schema", {})
        candidate_schema = candidate_content[media_type].get("schema", {})
        if isinstance(baseline_schema, dict) and isinstance(candidate_schema, dict):
            _compare_schema(
                f"{media_location}.schema",
                baseline_schema,
                candidate_schema,
                violations,
            )


def _compare_operation(
    location: str,
    baseline_path: dict[str, Any],
    baseline: dict[str, Any],
    candidate_path: dict[str, Any],
    candidate: dict[str, Any],
    violations: list[dict[str, str]],
) -> None:
    baseline_parameters = _parameters(baseline_path, baseline)
    candidate_parameters = _parameters(candidate_path, candidate)
    for key, parameter in baseline_parameters.items():
        parameter_location = f"{location}.parameters[{key}]"
        if key not in candidate_parameters:
            _violation(violations, parameter_location, "parameter removed")
            continue
        candidate_parameter = candidate_parameters[key]
        if not parameter.get("required", False) and candidate_parameter.get(
            "required", False
        ):
            _violation(
                violations, parameter_location, "optional parameter became required"
            )
        if "$ref" not in parameter:
            _compare_schema(
                parameter_location,
                parameter.get("schema", {}),
                candidate_parameter.get("schema", {}),
                violations,
            )

    baseline_request = baseline.get("requestBody")
    candidate_request = candidate.get("requestBody")
    if baseline_request and not candidate_request:
        _violation(violations, f"{location}.requestBody", "request body removed")
    elif baseline_request and candidate_request:
        if not baseline_request.get("required", False) and candidate_request.get(
            "required", False
        ):
            _violation(
                violations,
                f"{location}.requestBody",
                "optional request body became required",
            )
        _compare_media_types(
            f"{location}.requestBody",
            baseline_request.get("content", {}),
            candidate_request.get("content", {}),
            violations,
        )

    baseline_responses = baseline.get("responses", {})
    candidate_responses = candidate.get("responses", {})
    for status, response in baseline_responses.items():
        response_location = f"{location}.responses[{status}]"
        if status not in candidate_responses:
            _violation(violations, response_location, "response status removed")
            continue
        if "$ref" in response:
            if candidate_responses[status].get("$ref") != response["$ref"]:
                _violation(violations, response_location, "response reference changed")
            continue
        _compare_media_types(
            response_location,
            response.get("content", {}),
            candidate_responses[status].get("content", {}),
            violations,
        )


def find_breaking_changes(
    baseline: dict[str, Any], candidate: dict[str, Any]
) -> list[dict[str, str]]:
    violations: list[dict[str, str]] = []
    baseline_paths = baseline.get("paths", {})
    candidate_paths = candidate.get("paths", {})
    for path, baseline_path in baseline_paths.items():
        if path not in candidate_paths:
            _violation(violations, f"paths.{path}", "path removed")
            continue
        candidate_path = candidate_paths[path]
        for method, operation in baseline_path.items():
            if method.lower() not in HTTP_METHODS:
                continue
            location = f"paths.{path}.{method.lower()}"
            if method not in candidate_path:
                _violation(violations, location, "operation removed")
                continue
            _compare_operation(
                location,
                baseline_path,
                operation,
                candidate_path,
                candidate_path[method],
                violations,
            )

    baseline_schemas = baseline.get("components", {}).get("schemas", {})
    candidate_schemas = candidate.get("components", {}).get("schemas", {})
    for schema_name, baseline_schema in baseline_schemas.items():
        location = f"components.schemas.{schema_name}"
        if schema_name not in candidate_schemas:
            _violation(violations, location, "schema removed")
            continue
        _compare_schema(
            location, baseline_schema, candidate_schemas[schema_name], violations
        )
    return sorted(violations, key=lambda item: (item["location"], item["change"]))


def main() -> None:
    args = parse_args()
    baseline_path = Path(args.baseline)
    candidate_path = Path(args.candidate)
    violations = find_breaking_changes(
        load_contract(baseline_path), load_contract(candidate_path)
    )
    report = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "baseline": str(baseline_path),
        "candidate": str(candidate_path),
        "passed": not violations,
        "breakingChangeCount": len(violations),
        "breakingChanges": violations,
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    if violations:
        for violation in violations:
            print(f"{violation['location']}: {violation['change']}")
        raise SystemExit(f"Detected {len(violations)} breaking OpenAPI change(s).")
    print("No breaking OpenAPI changes detected.")


if __name__ == "__main__":
    main()
