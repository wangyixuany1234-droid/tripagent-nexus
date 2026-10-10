from datetime import UTC, datetime
from functools import lru_cache
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator, FormatChecker


@lru_cache(maxsize=1)
def _contract() -> dict[str, Any]:
    repository_root = Path(__file__).resolve().parents[3]
    path = repository_root / "packages/api-contract/ai-internal-openapi.yaml"
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def assert_openapi_schema(schema_name: str, payload: Any) -> None:
    contract = _contract()
    schema = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$ref": f"#/components/schemas/{schema_name}",
        "components": contract["components"],
    }
    errors = sorted(
        Draft202012Validator(
            schema,
            format_checker=FormatChecker(),
        ).iter_errors(payload),
        key=lambda error: list(error.absolute_path),
    )
    assert not errors, "; ".join(error.message for error in errors)


def assert_error_envelope(payload: dict[str, Any], expected_code: str) -> None:
    assert_openapi_schema("ProblemDetails", payload)
    assert payload["code"] == expected_code
    assert payload["message"]
    assert len(payload["traceId"]) == 32
    timestamp = datetime.fromisoformat(payload["timestamp"].replace("Z", "+00:00"))
    assert timestamp.utcoffset() == UTC.utcoffset(timestamp)
