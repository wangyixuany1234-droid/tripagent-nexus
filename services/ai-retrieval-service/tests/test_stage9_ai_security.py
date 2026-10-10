"""Offline AI boundary probes. Failed security expectations remain real failures."""

import json
from pathlib import Path
from unittest.mock import AsyncMock
from uuid import UUID

import pytest
from fastapi.testclient import TestClient

from trip_agent_ai.core.config import Settings
from trip_agent_ai.core.errors import AppError
from trip_agent_ai.core.guardrails import (
    contains_prompt_injection,
    require_allowed_https_url,
)
from trip_agent_ai.main import create_app
from trip_agent_ai.ocr.engine import OcrResult
from trip_agent_ai.ocr.parser import DocumentParser

CORPUS = json.loads(
    (
        Path(__file__).resolve().parents[3] / "qa/security/ai-stage9-corpus.json"
    ).read_text(encoding="utf-8")
)
AUTH = {"Authorization": "Bearer test-service-token"}
TENANT = UUID(int=901)
USER_A = UUID(int=902)
USER_B = UUID(int=903)
pytestmark = pytest.mark.security


@pytest.fixture
def client(isolated_lancedb_dir, monkeypatch):
    # A future provider/routing change must not turn this regression into live traffic.
    async def deny_network(*_args, **_kwargs):
        raise AssertionError("Stage 9 forbids external HTTP")

    monkeypatch.setattr("httpx.AsyncClient.send", deny_network)
    settings = Settings(
        _env_file=None,
        lancedb_uri=isolated_lancedb_dir,
        retrieval_provider_mode="mock",
        document_parser_mode="mock",
        retrieval_embedding_dimensions=64,
        retrieval_context_max_chars=1500,
        graph_store_mode="memory",
        otel_enabled=False,
        ai_service_token="test-service-token",
    )
    with TestClient(create_app(settings), raise_server_exceptions=False) as value:
        yield value


def scope(user):
    return {**AUTH, "X-Tenant-Id": str(TENANT), "X-User-Id": str(user)}


def chunk(source="guide", content="museum culture travel guide", **changes):
    return {
        "chunkId": source,
        "sourceId": source,
        "sourceType": "CITY_GUIDE",
        "title": "Museum culture guide",
        "content": content,
        "city": "TestCity",
        "language": "en",
        "updatedAt": "2026-09-01T00:00:00Z",
        **changes,
    }


def ingest(client, chunks, user=None, key="stage9-ingest"):
    return client.post(
        "/internal/v1/knowledge/ingest"
        if user is None
        else "/internal/v1/memory/ingest",
        headers={**(AUTH if user is None else scope(user)), "Idempotency-Key": key},
        json={"chunks": chunks},
    )


def search(client, user=None, **changes):
    return client.post(
        "/internal/v1/retrieval/search",
        headers=AUTH if user is None else scope(user),
        json={
            "query": "museum culture travel guide",
            "city": "TestCity",
            "language": "en",
            "travelDate": "2026-10-01",
            "includeGraph": False,
            "includePublicKnowledge": user is None,
            "includeUserMemory": user is not None,
            **changes,
        },
    )


@pytest.mark.parametrize("case", CORPUS["direct"], ids=lambda case: case["id"])
def test_direct_input_stops_before_model(client, case, monkeypatch):
    analyzer = AsyncMock(side_effect=AssertionError("Unsafe input reached analyzer"))
    monkeypatch.setattr(
        client.app.state.retrieval_service._query_analyzer, "analyze", analyzer
    )
    response = search(client, query=case["text"])
    assert response.status_code == 422, "Direct-input guard did not reject attack"
    assert response.json()["code"] == "PROMPT_INJECTION_DETECTED"
    assert case["text"] not in response.text
    analyzer.assert_not_called()


@pytest.mark.parametrize("case", CORPUS["benign"], ids=lambda case: case["id"])
def test_benign_input_is_not_blocked(case):
    assert not contains_prompt_injection(case["text"])


@pytest.mark.parametrize("case", CORPUS["indirect"], ids=lambda case: case["id"])
@pytest.mark.parametrize("surface", ["guide", "memory", "title", "tail"])
def test_indirect_instructions_excluded_before_rerank(
    client, monkeypatch, case, surface
):
    user = USER_A if surface == "memory" else None
    data = chunk(
        content=("museum culture " * 90 if surface == "tail" else "") + case["text"]
    )
    if surface == "title":
        data.update(title=case["text"], content="museum culture travel guide")
    if user:
        data["sourceType"] = "USER_FEEDBACK"
    assert ingest(client, [data], user).status_code == 200
    observed = []
    reranker = client.app.state.retrieval_service._reranker
    original = reranker.rerank

    async def observe(query, candidates):
        observed.extend(candidates)
        return await original(query, candidates)

    monkeypatch.setattr(reranker, "rerank", observe)
    response = search(client, user)
    assert response.status_code == 200
    assert not observed, "Injected source reached reranking"
    assert response.json()["evidence"] == []
    assert response.json()["evidenceInsufficient"] is True


@pytest.mark.asyncio
@pytest.mark.parametrize("case", CORPUS["indirect"], ids=lambda case: case["id"])
async def test_ocr_instructions_do_not_reach_vision(case, qa_fixture_root):
    parser = DocumentParser(Settings(_env_file=None, document_parser_mode="mock"))
    parser._ocr = AsyncMock()
    parser._ocr.extract.return_value = OcrResult(case["text"], (), "synthetic")
    parser._vision = AsyncMock()
    with pytest.raises(AppError) as error:
        await parser.parse(
            "synthetic.png",
            "image/png",
            (qa_fixture_root / "documents/hotel-confirmation.png").read_bytes(),
            "en",
        )
    assert error.value.code == "PROMPT_INJECTION_DETECTED"
    parser._vision.extract.assert_not_called()


def test_rag_user_and_tenant_isolation_and_delete(client):
    source = str(UUID(int=904))
    for user in (USER_A, USER_B):
        data = chunk(
            source,
            sourceType="USER_FEEDBACK",
            content=f"museum culture travel guide private-{user.int}",
        )
        assert ingest(client, [data], user).status_code == 200
    for user, other in ((USER_A, USER_B), (USER_B, USER_A)):
        response = search(client, user)
        assert response.status_code == 200
        assert response.json()["evidence"]
        assert f"private-{user.int}" in response.text
        assert f"private-{other.int}" not in response.text
    response = client.delete(
        f"/internal/v1/memory/{source}",
        headers={**scope(USER_A), "Idempotency-Key": "stage9-delete"},
    )
    assert response.status_code == 204
    assert search(client, USER_A).json()["evidence"] == []
    assert search(client, USER_B).json()["evidence"]
    response = client.post(
        "/internal/v1/retrieval/search",
        headers={**scope(USER_B), "X-Tenant-Id": str(UUID(int=999))},
        json={"query": "museum", "includeUserMemory": True, "includeGraph": False},
    )
    assert response.status_code == 200
    assert response.json()["evidence"] == []


def test_rag_city_scope_and_expired_hours(client):
    assert (
        ingest(
            client,
            [
                chunk("current"),
                chunk("other-city", city="OtherCity"),
                chunk("expired", sourceType="OPENING_HOURS", validTo="2025-12-31"),
            ],
        ).status_code
        == 200
    )
    body = search(client).json()
    assert {item["sourceId"] for item in body["evidence"]} == {"current"}
    assert all(
        item["excerpt"].startswith("[UNTRUSTED_RETRIEVAL_DATA]")
        for item in body["evidence"]
    )


def test_rag_same_chunk_id_cannot_overwrite_another_city(client):
    assert ingest(client, [chunk("shared")]).status_code == 200
    assert search(client).json()["evidence"]
    assert (
        ingest(
            client, [chunk("shared", city="OtherCity")], key="stage9-other"
        ).status_code
        == 200
    )
    assert search(client).json()["evidence"], (
        "Cross-city chunk collision removed original evidence"
    )


def test_rag_empty_source_and_context_limits(client):
    body = search(client).json()
    assert body["evidence"] == [] and body["evidenceInsufficient"] is True
    assert ingest(client, [chunk(sourceId="")]).status_code == 422
    assert ingest(client, [chunk(content="x" * 12001)]).status_code == 422
    assert (
        ingest(
            client, [chunk(str(i), content="museum culture " * 800) for i in range(8)]
        ).status_code
        == 200
    )
    response = search(client, maxResults=20)
    assert response.status_code == 200, (
        "Bounded valid documents must not fail evidence packaging"
    )
    evidence = response.json()["evidence"]
    assert evidence
    assert sum(len(item["excerpt"]) for item in evidence) <= 1500
    assert all(len(item["excerpt"]) <= 1000 for item in evidence)
    assert {item["sourceId"] for item in evidence} <= {str(i) for i in range(8)}


@pytest.mark.parametrize("case", CORPUS["urls"], ids=lambda case: case["id"])
def test_external_url_allowlist_without_network(case):
    with pytest.raises(ValueError, match="EXTERNAL_TOOL_DOMAIN_DENIED"):
        require_allowed_https_url(case["text"], ("allowed.example.test",))


def test_injection_security_event_is_recorded_without_payload(client, caplog):
    caplog.set_level("WARNING")
    attack = CORPUS["direct"][0]["text"] + " synthetic-private-marker"
    response = search(client, query=attack)
    assert response.status_code == 422
    assert "synthetic-private-marker" not in caplog.text
    assert "PROMPT_INJECTION_DETECTED" in caplog.text, "No security event recorded"
