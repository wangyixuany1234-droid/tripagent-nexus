import json
import os
from pathlib import Path
from typing import Any

import yaml
from fastapi.testclient import TestClient

from trip_agent_ai.core.config import Settings
from trip_agent_ai.main import app, create_app

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
CONTRACT_PATH = REPOSITORY_ROOT / "packages/api-contract/ai-internal-openapi.yaml"
HTTP_METHODS = {"get", "put", "post", "delete", "patch", "head", "options", "trace"}


def _operations(document: dict[str, Any]) -> dict[tuple[str, str], str]:
    return {
        (method.upper(), path): operation["operationId"]
        for path, path_item in document["paths"].items()
        for method, operation in path_item.items()
        if method in HTTP_METHODS
    }


def _resolve(document: dict[str, Any], value: dict[str, Any]) -> dict[str, Any]:
    reference = value.get("$ref")
    if not reference:
        return value
    current: Any = document
    for segment in reference.removeprefix("#/").split("/"):
        current = current[segment]
    return current


def _write_runtime_contract(document: dict[str, Any]) -> None:
    results_root = os.getenv("QA_RESULTS_ROOT")
    run_id = os.getenv("QA_RUN_ID")
    if not results_root or not run_id:
        return
    output = Path(results_root) / run_id / "json/fastapi-runtime-openapi.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(document, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def test_fastapi_runtime_operations_match_the_independent_contract() -> None:
    runtime = app.openapi()
    declared = yaml.safe_load(CONTRACT_PATH.read_text(encoding="utf-8"))

    assert runtime["openapi"].startswith("3.1.")
    assert _operations(runtime) == _operations(declared)
    assert len(set(_operations(runtime).values())) == len(_operations(runtime))
    _write_runtime_contract(runtime)


def test_internal_error_responses_use_the_problem_details_schema() -> None:
    contract = yaml.safe_load(CONTRACT_PATH.read_text(encoding="utf-8"))

    for path, path_item in contract["paths"].items():
        for method, operation in path_item.items():
            if method not in HTTP_METHODS:
                continue
            assert operation["responses"], f"{method.upper()} {path} has no responses"
            for status, raw_response in operation["responses"].items():
                if int(status) < 400:
                    continue
                response = _resolve(contract, raw_response)
                content = response.get("content", {})
                assert "application/problem+json" in content, (
                    f"{method.upper()} {path} {status} has no problem+json response"
                )
                assert content["application/problem+json"]["schema"] == {
                    "$ref": "#/components/schemas/ProblemDetails"
                }


def test_malformed_json_uses_problem_details(tmp_path: Path) -> None:
    test_app = create_app(
        Settings(
            otel_enabled=False,
            lancedb_uri=tmp_path,
            ai_service_token="test-service-token",
            retrieval_provider_mode="mock",
            graph_store_mode="memory",
        )
    )

    with TestClient(test_app) as client:
        response = client.post(
            "/internal/v1/graph/search",
            headers={
                "Authorization": "Bearer test-service-token",
                "Content-Type": "application/json",
            },
            content=b"\x80",
        )

    assert response.status_code == 400
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json()["code"] == "REQUEST_INVALID"


def test_traceparent_maximum_length_is_enforced(tmp_path: Path) -> None:
    test_app = create_app(
        Settings(
            otel_enabled=False,
            lancedb_uri=tmp_path,
            ai_service_token="test-service-token",
            retrieval_provider_mode="mock",
            graph_store_mode="memory",
        )
    )

    with TestClient(test_app) as client:
        response = client.post(
            "/internal/v1/retrieval/search",
            headers={
                "Authorization": "Bearer test-service-token",
                "traceparent": "x" * 129,
            },
            json={"query": "Beijing attractions"},
        )

    assert response.status_code == 422
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json()["code"] == "VALIDATION_FAILED"


def test_schema_violating_scalar_and_required_fields_are_rejected(
    tmp_path: Path,
) -> None:
    test_app = create_app(
        Settings(
            otel_enabled=False,
            lancedb_uri=tmp_path,
            ai_service_token="test-service-token",
            retrieval_provider_mode="mock",
            graph_store_mode="memory",
        )
    )
    auth = {"Authorization": "Bearer test-service-token"}

    with TestClient(test_app) as client:
        responses = [
            client.post(
                "/internal/v1/graph/search",
                headers=auth,
                json={"query": "Beijing attractions", "travelDate": 0},
            ),
            client.post(
                "/internal/v1/retrieval/search",
                headers=auth,
                json={"query": "Beijing attractions", "includeGraph": 0},
            ),
            client.post(
                "/internal/v1/knowledge/ingest",
                headers={**auth, "Idempotency-Key": "contract-invalid-graph"},
                json={
                    "chunks": [
                        {
                            "chunkId": "contract-chunk",
                            "sourceId": "contract-source",
                            "sourceType": "NOTICE",
                            "title": "Contract fixture",
                            "city": "Beijing",
                            "language": "en",
                            "content": "Synthetic contract fixture.",
                            "updatedAt": "2026-09-09T00:00:00Z",
                            "metadata": {},
                        }
                    ],
                    "graph": {"nodes": []},
                },
            ),
        ]

    assert {response.status_code for response in responses} == {422}
    assert all(
        response.headers["content-type"].startswith("application/problem+json")
        for response in responses
    )
    assert {response.json()["code"] for response in responses} == {"VALIDATION_FAILED"}
