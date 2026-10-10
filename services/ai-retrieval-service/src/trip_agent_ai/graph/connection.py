from neo4j import AsyncDriver, AsyncGraphDatabase, EagerResult

from trip_agent_ai.core.config import Settings


class Neo4jConnection:
    def __init__(self, settings: Settings) -> None:
        self._database = settings.neo4j_database
        self._driver: AsyncDriver = AsyncGraphDatabase.driver(
            settings.neo4j_uri,
            auth=(
                settings.neo4j_username,
                settings.neo4j_password.get_secret_value(),
            ),
        )

    async def verify_connectivity(self) -> None:
        await self._driver.verify_connectivity()
        await self._driver.execute_query(
            "RETURN 1 AS healthy",
            database_=self._database,
        )

    async def execute_query(
        self,
        query: str,
        **parameters: object,
    ) -> EagerResult:
        return await self._driver.execute_query(
            query,
            parameters_=parameters,
            database_=self._database,
        )

    async def close(self) -> None:
        await self._driver.close()


class MemoryNeo4jConnection:
    """No-op graph connection used when GRAPH_STORE_MODE=memory.

    In memory mode graph operations are served by InMemoryGraphStore and no
    external Neo4j instance is required. The health check still calls
    verify_connectivity(), so this stub reports healthy without opening any
    network connection.
    """

    def __init__(self, settings: Settings) -> None:
        self._database = settings.neo4j_database

    async def verify_connectivity(self) -> None:
        # No external dependency in memory mode; treat as healthy.
        return None

    async def execute_query(
        self,
        query: str,
        **parameters: object,
    ) -> EagerResult:
        raise NotImplementedError(
            "Neo4j queries are not available in GRAPH_STORE_MODE=memory; "
            "use InMemoryGraphStore instead."
        )

    async def close(self) -> None:
        return None
