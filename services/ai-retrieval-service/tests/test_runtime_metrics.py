from fastapi.testclient import TestClient
from openapi_assertions import assert_openapi_schema

from trip_agent_ai.core.config import Settings
from trip_agent_ai.main import create_app


def test_runtime_metrics_require_service_authentication(tmp_path) -> None:
    app = create_app(
        Settings(
            otel_enabled=False,
            lancedb_uri=tmp_path,
            ai_service_token="test-service-token",
        )
    )

    with TestClient(app) as client:
        response = client.get("/internal/v1/runtime-metrics")

    assert response.status_code == 401
    assert response.json()["code"] == "SERVICE_AUTHENTICATION_FAILED"


def test_runtime_metrics_report_process_and_event_loop_state(tmp_path) -> None:
    app = create_app(
        Settings(
            otel_enabled=False,
            lancedb_uri=tmp_path,
            ai_service_token="test-service-token",
        )
    )

    with TestClient(app) as client:
        response = client.get(
            "/internal/v1/runtime-metrics",
            headers={"Authorization": "Bearer test-service-token"},
        )

    assert response.status_code == 200
    payload = response.json()
    assert_openapi_schema("RuntimeMetricsResponse", payload)
    assert payload["processRssBytes"] > 0
    assert payload["threadCount"] >= 1
    assert payload["eventLoopLagMs"] >= 0
    assert payload["eventLoopLagP95Ms"] >= 0
    assert payload["eventLoopLagMaxMs"] >= payload["eventLoopLagMs"]
