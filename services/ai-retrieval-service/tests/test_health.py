from datetime import UTC, datetime

from fastapi.testclient import TestClient

from trip_agent_ai.api.health import get_health_service
from trip_agent_ai.core.config import Settings
from trip_agent_ai.main import create_app
from trip_agent_ai.schemas.health import (
    DependencyHealth,
    DependencyStatus,
    HealthResponse,
    HealthStatus,
)


class HealthyDependencies:
    async def check(self) -> HealthResponse:
        return HealthResponse(
            status=HealthStatus.DEGRADED,
            version="0.1.0",
            time=datetime.now(UTC),
            dependencies=[
                DependencyHealth(name="lancedb", status=DependencyStatus.UP),
                DependencyHealth(name="neo4j", status=DependencyStatus.UP),
                DependencyHealth(name="deepseek", status=DependencyStatus.DEGRADED),
            ],
        )


def test_health_requires_service_authentication(tmp_path) -> None:
    app = create_app(
        Settings(
            otel_enabled=False,
            lancedb_uri=tmp_path,
            ai_service_token="test-service-token",
        )
    )

    with TestClient(app) as client:
        response = client.get("/internal/v1/health")

    assert response.status_code == 401
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json()["code"] == "SERVICE_AUTHENTICATION_FAILED"


def test_health_returns_typed_status_and_request_context(tmp_path) -> None:
    app = create_app(
        Settings(
            otel_enabled=False,
            lancedb_uri=tmp_path,
            ai_service_token="test-service-token",
        )
    )
    app.dependency_overrides[get_health_service] = lambda: HealthyDependencies()

    with TestClient(app) as client:
        response = client.get(
            "/internal/v1/health",
            headers={
                "Authorization": "Bearer test-service-token",
                "X-Request-Id": "request-123",
            },
        )

    assert response.status_code == 200
    assert response.headers["x-request-id"] == "request-123"
    assert response.headers["x-trace-id"] == "0" * 32
    assert response.json()["status"] == "DEGRADED"
    assert {item["name"] for item in response.json()["dependencies"]} == {
        "deepseek",
        "lancedb",
        "neo4j",
    }


def test_fastapi_does_not_enable_browser_cors(tmp_path) -> None:
    app = create_app(
        Settings(
            otel_enabled=False,
            lancedb_uri=tmp_path,
            ai_service_token="test-service-token",
        )
    )

    with TestClient(app) as client:
        response = client.options(
            "/internal/v1/health",
            headers={
                "Origin": "https://untrusted.example",
                "Access-Control-Request-Method": "GET",
            },
        )

    assert "access-control-allow-origin" not in response.headers
