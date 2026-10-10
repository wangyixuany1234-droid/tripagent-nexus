from datetime import UTC, datetime

import pytest

from trip_agent_ai.core.config import Settings
from trip_agent_ai.rerank.cross_encoder import (
    MockCrossEncoderReranker,
    create_reranker,
)
from trip_agent_ai.retrieval.candidate import RetrievalCandidate
from trip_agent_ai.schemas.retrieval import (
    ProviderMode,
    RetrievalLayer,
    RetrievalStrategy,
)


def _candidate(source_id: str, title: str, excerpt: str, score: float):
    return RetrievalCandidate(
        chunk_id=source_id + "-chunk",
        source_id=source_id,
        source_type="ATTRACTION",
        title=title,
        excerpt=excerpt,
        updated_at=datetime(2026, 9, 5, tzinfo=UTC),
        layer=RetrievalLayer.PUBLIC_KNOWLEDGE,
        city="北京",
        region="东城区",
        score=score,
    )


@pytest.mark.asyncio
async def test_mock_cross_encoder_reranks_by_query_relevance() -> None:
    reranker = MockCrossEncoderReranker()
    candidates = [
        _candidate("summer-palace", "颐和园", "皇家园林与湖区", 0.72),
        _candidate("forbidden-city", "故宫门票", "故宫门票需要提前预约", 0.65),
    ]

    ranked = await reranker.rerank("故宫门票", candidates)

    assert [item.source_id for item in ranked] == [
        "forbidden-city",
        "summer-palace",
    ]
    assert all(RetrievalStrategy.CROSS_ENCODER in item.strategies for item in ranked)
    assert ranked[0].score > ranked[1].score


@pytest.mark.asyncio
async def test_mock_cross_encoder_handles_an_empty_candidate_set() -> None:
    reranker = create_reranker(
        Settings(
            retrieval_provider_mode="mock",
            neo4j_password="test-neo4j-password",
            otel_enabled=False,
        )
    )

    assert reranker.mode is ProviderMode.MOCK
    assert await reranker.rerank("北京三日游", []) == []
