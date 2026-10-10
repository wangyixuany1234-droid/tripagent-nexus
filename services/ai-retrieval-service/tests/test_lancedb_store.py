from datetime import UTC, date, datetime
from uuid import UUID

from trip_agent_ai.core.config import Settings
from trip_agent_ai.schemas.retrieval import (
    KnowledgeChunkInput,
    KnowledgeSourceType,
    MemoryChunkInput,
    MemorySourceType,
    RetrievalStrategy,
)
from trip_agent_ai.storage.lancedb_store import LanceDbStore


def test_lancedb_hybrid_recall_filters_location_date_and_user_scope(
    isolated_lancedb_dir,
) -> None:
    settings = Settings(
        lancedb_uri=isolated_lancedb_dir,
        retrieval_embedding_dimensions=16,
        neo4j_password="test-neo4j-password",
        otel_enabled=False,
    )
    store = LanceDbStore(settings.lancedb_uri, settings)
    updated_at = datetime(2026, 9, 5, tzinfo=UTC)
    store.upsert_public(
        [
            KnowledgeChunkInput(
                chunkId="forbidden-city-2026",
                sourceId="forbidden-city",
                sourceType=KnowledgeSourceType.OPENING_HOURS,
                title="故宫开放时间",
                city="北京",
                region="东城区",
                validFrom=date(2026, 1, 1),
                validTo=date(2026, 12, 31),
                language="zh-CN",
                content="故宫门票需要预约，开放时间为八点三十分。",
                updatedAt=updated_at,
            ),
            KnowledgeChunkInput(
                chunkId="expired-hours",
                sourceId="forbidden-city-old",
                sourceType=KnowledgeSourceType.OPENING_HOURS,
                title="故宫旧开放时间",
                city="北京",
                region="东城区",
                validFrom=date(2025, 1, 1),
                validTo=date(2025, 12, 31),
                language="zh-CN",
                content="故宫门票旧开放时间。",
                updatedAt=updated_at,
            ),
        ],
        [[1.0] + [0.0] * 15, [0.9] + [0.0] * 15],
    )
    tenant_a = UUID("10000000-0000-0000-0000-000000000001")
    user_a = UUID("20000000-0000-0000-0000-000000000001")
    user_b = UUID("20000000-0000-0000-0000-000000000002")
    memory = [
        MemoryChunkInput(
            chunkId="dietary",
            sourceId="memory-dietary",
            sourceType=MemorySourceType.DIETARY_RESTRICTION,
            title="饮食偏好",
            language="zh-CN",
            content="用户偏好素食餐厅。",
            updatedAt=updated_at,
        )
    ]
    store.upsert_memory(tenant_a, user_a, memory, [[0.0, 1.0] + [0.0] * 14])

    public_hits = store.hybrid_search_public(
        "故宫门票",
        [1.0] + [0.0] * 15,
        city="北京",
        region=None,
        language="zh-CN",
        as_of=date(2026, 10, 1),
        limit=10,
    )
    own_memory = store.hybrid_search_memory(
        "素食餐厅",
        [0.0, 1.0] + [0.0] * 14,
        tenant_id=tenant_a,
        user_id=user_a,
        city=None,
        region=None,
        language="zh-CN",
        as_of=date(2026, 10, 1),
        limit=10,
    )
    other_memory = store.hybrid_search_memory(
        "素食餐厅",
        [0.0, 1.0] + [0.0] * 14,
        tenant_id=tenant_a,
        user_id=user_b,
        city=None,
        region=None,
        language="zh-CN",
        as_of=date(2026, 10, 1),
        limit=10,
    )

    assert [hit.record["chunkId"] for hit in public_hits] == ["forbidden-city-2026"]
    assert RetrievalStrategy.VECTOR in public_hits[0].strategies
    assert RetrievalStrategy.KEYWORD in public_hits[0].strategies
    assert RetrievalStrategy.HYBRID in public_hits[0].strategies
    assert [hit.record["chunkId"] for hit in own_memory] == ["dietary"]
    assert other_memory == []


def test_delete_memory_is_scoped_to_tenant_and_user(isolated_lancedb_dir) -> None:
    settings = Settings(
        lancedb_uri=isolated_lancedb_dir,
        retrieval_embedding_dimensions=16,
        neo4j_password="test-neo4j-password",
        otel_enabled=False,
    )
    store = LanceDbStore(settings.lancedb_uri, settings)
    tenant_id = UUID("10000000-0000-0000-0000-000000000001")
    user_id = UUID("20000000-0000-0000-0000-000000000001")
    preference_id = UUID("30000000-0000-0000-0000-000000000001")
    store.upsert_memory(
        tenant_id,
        user_id,
        [
            MemoryChunkInput(
                chunkId=str(preference_id),
                sourceId=str(preference_id),
                sourceType=MemorySourceType.PACE_PREFERENCE,
                title="节奏偏好",
                language="zh-CN",
                content="用户偏好舒缓节奏。",
                updatedAt=datetime(2026, 9, 7, tzinfo=UTC),
            )
        ],
        [[0.0, 1.0] + [0.0] * 14],
    )

    store.delete_memory(tenant_id, user_id, preference_id)

    assert (
        store.hybrid_search_memory(
            "舒缓节奏",
            [0.0, 1.0] + [0.0] * 14,
            tenant_id=tenant_id,
            user_id=user_id,
            city=None,
            region=None,
            language="zh-CN",
            as_of=date(2026, 10, 1),
            limit=10,
        )
        == []
    )
