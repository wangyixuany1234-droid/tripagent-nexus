import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager, suppress

from fastapi import FastAPI

from trip_agent_ai.api.documents import router as documents_router
from trip_agent_ai.api.health import router as health_router
from trip_agent_ai.api.retrieval import router as retrieval_router
from trip_agent_ai.api.runtime import router as runtime_router
from trip_agent_ai.application_health import HealthService
from trip_agent_ai.core.config import Settings, get_settings
from trip_agent_ai.core.errors import register_exception_handlers
from trip_agent_ai.core.logging import configure_logging
from trip_agent_ai.core.middleware import install_request_context_middleware
from trip_agent_ai.graph.connection import MemoryNeo4jConnection, Neo4jConnection
from trip_agent_ai.graph.store import InMemoryGraphStore, Neo4jGraphStore
from trip_agent_ai.observability.runtime_metrics import RuntimeMetricsService
from trip_agent_ai.observability.tracing import configure_tracing
from trip_agent_ai.ocr.parser import DocumentParser
from trip_agent_ai.rerank.cross_encoder import create_reranker
from trip_agent_ai.retrieval.embedding import create_embedding_provider
from trip_agent_ai.retrieval.query_analysis import create_query_analyzer
from trip_agent_ai.retrieval.service import RetrievalService
from trip_agent_ai.storage.lancedb_store import LanceDbStore


def create_app(settings: Settings | None = None) -> FastAPI:
    runtime_settings = settings or get_settings()
    configure_logging(runtime_settings.log_level)
    neo4j = (
        MemoryNeo4jConnection(runtime_settings)
        if runtime_settings.graph_store_mode == "memory"
        else Neo4jConnection(runtime_settings)
    )
    lancedb = LanceDbStore(runtime_settings.lancedb_uri, runtime_settings)
    graph_store = (
        InMemoryGraphStore()
        if runtime_settings.graph_store_mode == "memory"
        else Neo4jGraphStore(neo4j)
    )
    retrieval_service = RetrievalService(
        runtime_settings,
        lancedb,
        graph_store,
        create_embedding_provider(runtime_settings),
        create_query_analyzer(runtime_settings),
        create_reranker(runtime_settings),
    )
    runtime_metrics_service = RuntimeMetricsService()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        app.state.health_service = HealthService(
            runtime_settings,
            neo4j,
            lancedb,
        )
        app.state.runtime_metrics_service = runtime_metrics_service
        runtime_metrics_task = asyncio.create_task(
            runtime_metrics_service.monitor_event_loop()
        )
        try:
            yield
        finally:
            runtime_metrics_task.cancel()
            with suppress(asyncio.CancelledError):
                await runtime_metrics_task
            await neo4j.close()

    app = FastAPI(
        title="TripAgent Nexus AI Retrieval Service",
        version="0.1.0",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )
    app.state.settings = runtime_settings
    app.state.document_parser = DocumentParser(runtime_settings)
    app.state.retrieval_service = retrieval_service
    register_exception_handlers(app)
    install_request_context_middleware(app)
    app.include_router(health_router)
    app.include_router(runtime_router)
    app.include_router(documents_router)
    app.include_router(retrieval_router)
    configure_tracing(app, runtime_settings)
    return app


app = create_app()
