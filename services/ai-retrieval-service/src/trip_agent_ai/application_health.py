import asyncio
from datetime import UTC, datetime
from importlib.metadata import version

import structlog

from trip_agent_ai.core.config import Settings
from trip_agent_ai.core.errors import AppError
from trip_agent_ai.graph.connection import Neo4jConnection
from trip_agent_ai.schemas.health import (
    DependencyHealth,
    DependencyStatus,
    HealthResponse,
    HealthStatus,
)
from trip_agent_ai.storage.lancedb_store import LanceDbStore

logger = structlog.get_logger(__name__)


class HealthService:
    def __init__(
        self,
        settings: Settings,
        neo4j: Neo4jConnection,
        lancedb: LanceDbStore,
    ) -> None:
        self._settings = settings
        self._neo4j = neo4j
        self._lancedb = lancedb

    async def check(self) -> HealthResponse:
        neo4j_status, lancedb_status = await asyncio.gather(
            self._check_neo4j(),
            self._check_lancedb(),
        )
        if DependencyStatus.DOWN in {neo4j_status, lancedb_status}:
            raise AppError(
                code="DEPENDENCY_UNAVAILABLE",
                title="Dependency unavailable",
                status=503,
                detail="A required AI retrieval dependency is unavailable.",
                retryable=True,
            )

        deepseek_status = (
            DependencyStatus.UP
            if self._settings.deepseek_api_key is not None
            and self._settings.deepseek_api_key.get_secret_value()
            else DependencyStatus.DEGRADED
        )
        overall = (
            HealthStatus.UP
            if deepseek_status is DependencyStatus.UP
            else HealthStatus.DEGRADED
        )
        return HealthResponse(
            status=overall,
            version=version("trip-agent-ai"),
            time=datetime.now(UTC),
            dependencies=[
                DependencyHealth(name="lancedb", status=lancedb_status),
                DependencyHealth(name="neo4j", status=neo4j_status),
                DependencyHealth(name="deepseek", status=deepseek_status),
            ],
        )

    async def _check_neo4j(self) -> DependencyStatus:
        try:
            await self._neo4j.verify_connectivity()
            return DependencyStatus.UP
        except Exception as exception:
            await logger.awarning(
                "neo4j_health_check_failed",
                exceptionType=type(exception).__name__,
            )
            return DependencyStatus.DOWN

    async def _check_lancedb(self) -> DependencyStatus:
        try:
            await asyncio.to_thread(self._lancedb.verify_connectivity)
            return DependencyStatus.UP
        except Exception as exception:
            await logger.awarning(
                "lancedb_health_check_failed",
                exceptionType=type(exception).__name__,
            )
            return DependencyStatus.DOWN
