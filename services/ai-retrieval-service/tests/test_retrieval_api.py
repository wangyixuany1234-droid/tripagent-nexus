import asyncio
import importlib.util
import json
import time
from copy import deepcopy
from pathlib import Path
from typing import Any
from uuid import UUID

import lancedb
import pytest
from fastapi.testclient import TestClient
from neo4j.exceptions import Neo4jError

from trip_agent_ai.core.config import Settings
from trip_agent_ai.main import create_app

AUTH = {"Authorization": "Bearer test-service-token"}
TENANT_ID = UUID("10000000-0000-0000-0000-000000000001")
USER_A = UUID("20000000-0000-0000-0000-000000000001")
USER_B = UUID("20000000-0000-0000-0000-000000000002")

_chaos_spec = importlib.util.spec_from_file_location(
    "chaos_evidence",
    Path(__file__).resolve().parents[3] / "qa/chaos/evidence.py",
)
_chaos = importlib.util.module_from_spec(_chaos_spec)
_chaos_spec.loader.exec_module(_chaos)


@pytest.fixture
def retrieval_context(isolated_lancedb_dir, qa_fixture_root):
    lancedb_uri = isolated_lancedb_dir
    settings = Settings(
        otel_enabled=False,
        lancedb_uri=lancedb_uri,
        ai_service_token="test-service-token",
        neo4j_password="test-neo4j-password",
        retrieval_provider_mode="mock",
        retrieval_embedding_dimensions=64,
        graph_store_mode="memory",
    )
    app = create_app(settings)
    fixture_root = qa_fixture_root / "retrieval"
    knowledge = _fixture(fixture_root, "public-knowledge.json")
    memory_a = _fixture(fixture_root, "memory-user-a.json")
    memory_b = _fixture(fixture_root, "memory-user-b.json")

    with TestClient(app) as client:
        knowledge_response = client.post(
            "/internal/v1/knowledge/ingest",
            headers={
                **AUTH,
                "Idempotency-Key": "knowledge-beijing-v1",
            },
            json=knowledge,
        )
        assert knowledge_response.status_code == 200, knowledge_response.text
        memory_a_response = _ingest_memory(client, USER_A, memory_a, "memory-user-a-v1")
        memory_b_response = _ingest_memory(client, USER_B, memory_b, "memory-user-b-v1")
        yield {
            "client": client,
            "lancedb_uri": lancedb_uri,
            "knowledge": knowledge,
            "knowledge_response": knowledge_response.json(),
            "memory_a_response": memory_a_response,
            "memory_b_response": memory_b_response,
        }


@pytest.mark.parametrize("fault", ["graphTimeout", "vectorFailure", "partial", "index"])
def test_stage7_retrieval_faults_keep_only_valid_evidence(
    retrieval_context, monkeypatch, fault
) -> None:
    client = retrieval_context["client"]
    service = client.app.state.retrieval_service
    payload = {
        "query": "北京三天如何分区游览",
        "city": "北京",
        "travelDate": "2026-10-01",
        "maxResults": 10,
    }
    baseline = _search(client, payload)
    assert baseline.status_code == 200
    original_graph = service._graph_store.search
    original_vector = service._vector_store.hybrid_search_public
    timeout = _chaos.threshold("callTimeoutMs") / 1000
    service._settings.retrieval_timeout_seconds = timeout
    with _chaos.experiment(
        f"retrieval.{fault}",
        "REAL_LANCEDB_INDEX" if fault == "index" else "DEPENDENCY_METHOD_INJECTION",
        "FastAPI TestClient, real isolated LanceDB, in-memory graph fixture",
    ) as record:

        async def graph_timeout(*args, **kwargs):
            await asyncio.sleep(timeout * 3)
            return await original_graph(*args, **kwargs)

        def vector_failure(*args, **kwargs):
            raise TimeoutError("synthetic vector timeout")

        async def partial_graph(*args, **kwargs):
            scope, hits = await original_graph(*args, **kwargs)
            return scope, hits[:1]

        table = None
        record["injectedAt"] = _chaos.now()
        try:
            if fault == "graphTimeout":
                monkeypatch.setattr(service._graph_store, "search", graph_timeout)
            elif fault == "vectorFailure":
                monkeypatch.setattr(
                    service._vector_store, "hybrid_search_public", vector_failure
                )
            elif fault == "partial":
                monkeypatch.setattr(service._graph_store, "search", partial_graph)
                monkeypatch.setattr(
                    service._vector_store, "hybrid_search_public", vector_failure
                )
            else:
                table = lancedb.connect(
                    str(retrieval_context["lancedb_uri"])
                ).open_table("travel_public")
                indexes = table.list_indices()
                assert indexes
                for index in indexes:
                    table.drop_index(index.name)
                assert not table.list_indices()
            started = time.monotonic()
            response = _search(client, payload)
            record["firstDetectedAt"] = _chaos.now()
            record["measurements"]["responseMs"] = round(
                (time.monotonic() - started) * 1000
            )
            assert response.status_code == 200
            body = response.json()
            warning = (
                "NEO4J_SEARCH_FAILED"
                if fault == "graphTimeout"
                else "LANCEDB_SEARCH_FAILED"
            )
            if fault == "index":
                # LanceDB can scan without FTS indexes; valid fallback is not an outage.
                assert not table.list_indices()
                assert not body["warnings"]
                known_sources = {
                    chunk["sourceId"]
                    for chunk in retrieval_context["knowledge"]["chunks"]
                }
                known_sources.update(
                    node["sourceId"]
                    for node in retrieval_context["knowledge"]["graph"]["nodes"]
                )
                assert {item["sourceId"] for item in body["evidence"]} <= known_sources
                record["measurements"]["indexlessScanSucceeded"] = True
            else:
                assert warning in body["warnings"]
            assert body["evidence"]
            assert all(
                item["sourceId"] and item["excerpt"] for item in body["evidence"]
            )
            if fault in {"vectorFailure", "partial"}:
                assert all(item["layer"] == "GRAPH" for item in body["evidence"])
            if fault == "partial":
                assert len(body["evidence"]) == 1
            record["traceIds"] = [response.headers["x-trace-id"]]
            if fault != "index":
                record["degradationStartedAt"] = _chaos.now()
                record["userVisibleImpact"] = (
                    "Available evidence retained; failed dependency "
                    "explicitly listed in warnings."
                )
            else:
                record["userVisibleImpact"] = (
                    "Indexless library scan retained valid evidence."
                )
        finally:
            monkeypatch.setattr(service._graph_store, "search", original_graph)
            monkeypatch.setattr(
                service._vector_store, "hybrid_search_public", original_vector
            )
            if table is not None:
                from lancedb.index import FTS

                table.create_index(
                    "content",
                    config=FTS(
                        base_tokenizer="ngram", ngram_min_length=1, ngram_max_length=3
                    ),
                    replace=True,
                )
            record["faultClearedAt"] = _chaos.now()
        restored = _search(client, payload)
        assert restored.status_code == 200
        assert not restored.json()["warnings"]
        record["recoveredAt"] = _chaos.now()


def test_ingestion_is_idempotent_and_uses_required_lancedb_fields(
    retrieval_context,
) -> None:
    client: TestClient = retrieval_context["client"]
    repeated = client.post(
        "/internal/v1/knowledge/ingest",
        headers={
            **AUTH,
            "Idempotency-Key": "knowledge-beijing-v1",
        },
        json=retrieval_context["knowledge"],
    )

    assert repeated.status_code == 200
    assert (
        repeated.json()["ingestionId"]
        == retrieval_context["knowledge_response"]["ingestionId"]
    )
    assert repeated.json()["providerMode"] == "MOCK"

    database = lancedb.connect(str(retrieval_context["lancedb_uri"]))
    public_table = database.open_table("travel_public")
    memory_table = database.open_table("user_memory")
    required = {
        "chunkId",
        "sourceId",
        "sourceType",
        "city",
        "region",
        "validFrom",
        "validTo",
        "language",
        "content",
        "embedding",
        "updatedAt",
        "metadata",
    }
    assert required <= set(public_table.schema.names)
    assert required | {"tenantId", "userId"} <= set(memory_table.schema.names)
    assert public_table.count_rows() == len(retrieval_context["knowledge"]["chunks"])
    assert memory_table.count_rows() == 7
    public_rows = public_table.to_arrow()
    memory_rows = memory_table.to_arrow()
    assert all(len(vector) == 64 for vector in public_rows["embedding"].to_pylist())
    assert all(len(vector) == 64 for vector in memory_rows["embedding"].to_pylist())
    assert set(public_rows["sourceType"].to_pylist()) == {
        "CITY_GUIDE",
        "ATTRACTION",
        "REGION_GUIDE",
        "TRANSIT_RULE",
        "OPENING_HOURS",
        "NOTICE",
        "DINING_AREA",
        "LODGING_AREA",
    }
    assert set(memory_rows["sourceType"].to_pylist()) == {
        "TRIP_HISTORY",
        "SAVED_PLACE",
        "BUDGET_PREFERENCE",
        "PACE_PREFERENCE",
        "DIETARY_RESTRICTION",
        "USER_FEEDBACK",
    }


def test_idempotency_key_rejects_a_different_payload(retrieval_context) -> None:
    changed = deepcopy(retrieval_context["knowledge"])
    changed["chunks"][0]["title"] = "Changed title"

    response = retrieval_context["client"].post(
        "/internal/v1/knowledge/ingest",
        headers={
            **AUTH,
            "Idempotency-Key": "knowledge-beijing-v1",
        },
        json=changed,
    )

    assert response.status_code == 409
    assert response.json()["code"] == "IDEMPOTENCY_KEY_REUSED"


def test_nearby_query_prioritizes_local_graph(retrieval_context) -> None:
    response = _search(
        retrieval_context["client"],
        {
            "query": "故宫附近有哪些顺路景点",
            "city": "北京",
            "travelDate": "2026-10-01",
            "maxResults": 5,
        },
    )

    assert response.status_code == 200
    assert response.headers["x-trace-id"] == "0" * 32
    body = response.json()
    assert body["intent"] == "LOCAL_NEARBY"
    assert body["routes"][0] == "GRAPH"
    assert body["evidenceInsufficient"] is False
    assert "LOCAL_GRAPH" in body["evidence"][0]["retrievalStrategies"]
    assert body["evidence"][0]["sourceId"] == "kb-attraction-jingshan"
    source_ids = [item["sourceId"] for item in body["evidence"]]
    assert len(source_ids) == len(set(source_ids))
    known_sources = {
        chunk["sourceId"] for chunk in retrieval_context["knowledge"]["chunks"]
    } | {node["sourceId"] for node in retrieval_context["knowledge"]["graph"]["nodes"]}
    assert set(source_ids) <= known_sources


def test_three_day_query_combines_region_graph_and_vector_recall(
    retrieval_context,
) -> None:
    response = _search(
        retrieval_context["client"],
        {
            "query": "北京三天如何分区游览",
            "city": "北京",
            "travelDate": "2026-10-01",
            "maxResults": 10,
        },
    )

    assert response.status_code == 200
    body = response.json()
    strategies = {
        strategy
        for evidence in body["evidence"]
        for strategy in evidence["retrievalStrategies"]
    }
    assert body["intent"] == "REGION_PLANNING"
    assert body["routes"][:2] == ["GRAPH", "PUBLIC_KNOWLEDGE"]
    assert "REGION_GRAPH" in strategies
    assert {"VECTOR", "KEYWORD"} & strategies


def test_personalized_retrieval_uses_memory_and_isolates_users(
    retrieval_context,
) -> None:
    client: TestClient = retrieval_context["client"]
    request = {
        "query": "根据我之前偏好推荐餐厅",
        "city": "北京",
        "travelDate": "2026-10-01",
        "includeUserMemory": True,
        "maxResults": 10,
    }
    response_a = _search(client, request, USER_A)
    response_b = _search(client, request, USER_B)

    assert response_a.status_code == 200
    assert response_b.status_code == 200
    body_a = response_a.json()
    body_b = response_b.json()
    source_ids_a = {item["sourceId"] for item in body_a["evidence"]}
    source_ids_b = {item["sourceId"] for item in body_b["evidence"]}
    assert body_a["routes"][0] == "USER_MEMORY"
    assert "memory-user-a-dietary" in source_ids_a
    assert "memory-user-b-dietary" not in source_ids_a
    assert "memory-user-b-dietary" in source_ids_b
    assert "memory-user-a-dietary" not in source_ids_b
    assert any(item["layer"] == "USER_MEMORY" for item in body_a["evidence"])


def test_expired_opening_hours_are_filtered_before_reranking(
    retrieval_context,
) -> None:
    response = _search(
        retrieval_context["client"],
        {
            "query": "故宫二十点闭馆吗",
            "city": "北京",
            "travelDate": "2026-10-01",
            "includeGraph": False,
            "maxResults": 10,
        },
    )

    assert response.status_code == 200
    source_ids = {item["sourceId"] for item in response.json()["evidence"]}
    assert "kb-hours-forbidden-city-2025" not in source_ids
    assert "kb-hours-forbidden-city-2026" in source_ids


def test_no_evidence_and_realtime_routes_are_explicit(retrieval_context) -> None:
    client: TestClient = retrieval_context["client"]
    missing = _search(
        client,
        {
            "query": "不存在地点的攻略",
            "city": "不存在城市",
            "includeGraph": False,
        },
    )
    realtime = _search(
        client,
        {
            "query": "故宫现在排队多久",
            "city": "北京",
        },
    )

    assert missing.status_code == 200
    assert missing.json()["evidence"] == []
    assert missing.json()["evidenceInsufficient"] is True
    assert realtime.status_code == 200
    assert realtime.json()["routes"] == ["REALTIME_TOOL"]
    assert realtime.json()["realtimeToolRequired"] is True
    assert realtime.json()["evidenceInsufficient"] is True


def test_graph_endpoint_uses_bounded_local_scope(retrieval_context) -> None:
    response = retrieval_context["client"].post(
        "/internal/v1/graph/search",
        headers=AUTH,
        json={
            "query": "故宫附近景点",
            "scope": "AUTO",
            "city": "北京",
            "travelDate": "2026-10-01",
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["scope"] == "LOCAL"
    assert body["providerMode"] == "MOCK"
    assert body["evidenceInsufficient"] is False
    assert all(
        "LOCAL_GRAPH" in item["retrievalStrategies"] for item in body["evidence"]
    )


def test_graph_evidence_survives_lancedb_timeout(
    retrieval_context,
    monkeypatch,
) -> None:
    client: TestClient = retrieval_context["client"]

    def time_out_public_search(*args, **kwargs):
        del args, kwargs
        raise TimeoutError("synthetic LanceDB timeout")

    monkeypatch.setattr(
        client.app.state.retrieval_service._vector_store,
        "hybrid_search_public",
        time_out_public_search,
    )

    response = _search(
        client,
        {
            "query": "北京三天如何分区游览",
            "city": "北京",
            "travelDate": "2026-10-01",
            "maxResults": 10,
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["evidence"]
    assert all(item["layer"] == "GRAPH" for item in body["evidence"])
    assert "LANCEDB_SEARCH_FAILED" in body["warnings"]
    assert body["evidenceInsufficient"] is False


def test_all_persistent_retrieval_failures_return_dependency_error(
    retrieval_context,
    monkeypatch,
) -> None:
    client: TestClient = retrieval_context["client"]

    async def fail_graph_search(*args, **kwargs):
        del args, kwargs
        raise Neo4jError("synthetic Neo4j failure")

    def fail_public_search(*args, **kwargs):
        del args, kwargs
        raise TimeoutError("synthetic LanceDB timeout")

    service = client.app.state.retrieval_service
    monkeypatch.setattr(service._graph_store, "search", fail_graph_search)
    monkeypatch.setattr(
        service._vector_store,
        "hybrid_search_public",
        fail_public_search,
    )

    response = _search(
        client,
        {
            "query": "北京三天如何分区游览",
            "city": "北京",
            "travelDate": "2026-10-01",
            "maxResults": 10,
        },
    )

    assert response.status_code == 503
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json()["code"] == "NEO4J_SEARCH_FAILED"
    assert "evidence" not in response.json()


def test_memory_search_requires_complete_trusted_scope(retrieval_context) -> None:
    response = retrieval_context["client"].post(
        "/internal/v1/retrieval/search",
        headers=AUTH,
        json={
            "query": "根据我的偏好推荐餐厅",
            "includeUserMemory": True,
        },
    )

    assert response.status_code == 422
    assert response.json()["code"] == "USER_SCOPE_REQUIRED"


def test_internal_retrieval_endpoints_require_service_authentication(
    retrieval_context,
) -> None:
    response = retrieval_context["client"].post(
        "/internal/v1/retrieval/search",
        json={"query": "北京景点"},
    )

    assert response.status_code == 401
    assert response.json()["code"] == "SERVICE_AUTHENTICATION_FAILED"


def _fixture(fixture_root, name: str) -> dict[str, Any]:
    return json.loads((fixture_root / name).read_text(encoding="utf-8"))


def _ingest_memory(
    client: TestClient,
    user_id: UUID,
    body: dict[str, Any],
    idempotency_key: str,
) -> dict[str, Any]:
    response = client.post(
        "/internal/v1/memory/ingest",
        headers={
            **AUTH,
            "Idempotency-Key": idempotency_key,
            "X-Tenant-Id": str(TENANT_ID),
            "X-User-Id": str(user_id),
        },
        json=body,
    )
    assert response.status_code == 200, response.text
    return response.json()


def _search(
    client: TestClient,
    body: dict[str, Any],
    user_id: UUID | None = None,
):
    headers = dict(AUTH)
    if user_id is not None:
        headers["X-Tenant-Id"] = str(TENANT_ID)
        headers["X-User-Id"] = str(user_id)
    return client.post(
        "/internal/v1/retrieval/search",
        headers=headers,
        json=body,
    )
