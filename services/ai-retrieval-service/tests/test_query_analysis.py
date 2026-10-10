import pytest

from trip_agent_ai.core.config import Settings
from trip_agent_ai.core.errors import AppError
from trip_agent_ai.retrieval.query_analysis import (
    DeepseekQueryAnalyzer,
    MockQueryAnalyzer,
    QueryPlan,
    _enabled_routes,
    _enforce_requested_routes,
)
from trip_agent_ai.schemas.retrieval import (
    GraphScope,
    RetrievalIntent,
    RetrievalLayer,
    RetrievalSearchRequest,
)


@pytest.mark.asyncio
async def test_mock_analyzer_routes_transit_queries_without_external_calls() -> None:
    plan = await MockQueryAnalyzer().analyze(
        RetrievalSearchRequest(
            query="从机场怎么换乘地铁",
            includePublicKnowledge=False,
            includeUserMemory=False,
            includeGraph=True,
        ),
        memory_scope_available=False,
    )

    assert plan.intent is RetrievalIntent.TRANSIT_CONNECTION
    assert plan.graphScope is GraphScope.TRANSIT
    assert plan.routes == [RetrievalLayer.GRAPH]


@pytest.mark.asyncio
async def test_mock_analyzer_allows_local_query_without_anchor() -> None:
    plan = await MockQueryAnalyzer().analyze(
        RetrievalSearchRequest(query="附近"),
        memory_scope_available=False,
    )

    assert plan.intent is RetrievalIntent.LOCAL_NEARBY
    assert plan.anchorEntity is None


def test_enabled_routes_adds_memory_when_not_already_prioritized() -> None:
    request = RetrievalSearchRequest(
        query="合成偏好查询",
        includePublicKnowledge=False,
        includeUserMemory=True,
        includeGraph=False,
    )

    assert _enabled_routes(request, memory_scope_available=True) == [
        RetrievalLayer.USER_MEMORY
    ]


def test_model_routes_are_restricted_to_explicitly_requested_layers() -> None:
    plan = QueryPlan(
        rewrittenQuery="合成检索",
        intent=RetrievalIntent.GENERAL_KNOWLEDGE,
        routes=[
            RetrievalLayer.PUBLIC_KNOWLEDGE,
            RetrievalLayer.USER_MEMORY,
            RetrievalLayer.GRAPH,
            RetrievalLayer.REALTIME_TOOL,
        ],
        graphScope=GraphScope.AUTO,
    )
    request = RetrievalSearchRequest(
        query="合成检索",
        includePublicKnowledge=True,
        includeUserMemory=True,
        includeGraph=True,
    )

    restricted = _enforce_requested_routes(
        plan,
        request,
        memory_scope_available=True,
    )

    assert restricted.routes == plan.routes


def test_model_routes_fall_back_when_every_suggested_layer_is_disallowed() -> None:
    plan = QueryPlan(
        rewrittenQuery="合成检索",
        intent=RetrievalIntent.GENERAL_KNOWLEDGE,
        routes=[RetrievalLayer.GRAPH],
        graphScope=GraphScope.AUTO,
    )
    request = RetrievalSearchRequest(
        query="合成检索",
        includePublicKnowledge=False,
        includeUserMemory=False,
        includeGraph=False,
    )

    restricted = _enforce_requested_routes(
        plan,
        request,
        memory_scope_available=False,
    )

    assert restricted.routes == [RetrievalLayer.PUBLIC_KNOWLEDGE]


@pytest.mark.asyncio
async def test_deepseek_analyzer_fails_closed_without_model_credentials() -> None:
    analyzer = DeepseekQueryAnalyzer(
        Settings(
            ai_service_token="synthetic-service-token",
            neo4j_password="synthetic-neo4j-password",
            deepseek_api_key=None,
            retrieval_provider_mode="deepseek",
            document_parser_mode="mock",
            otel_enabled=False,
        )
    )

    with pytest.raises(AppError) as raised:
        await analyzer.analyze(
            RetrievalSearchRequest(query="合成检索"),
            memory_scope_available=False,
        )

    assert raised.value.code == "MODEL_CREDENTIAL_MISSING"
    assert raised.value.status == 503
