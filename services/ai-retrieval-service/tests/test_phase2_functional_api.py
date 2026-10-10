import hashlib
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from decimal import Decimal
from uuid import UUID, uuid4

import httpx
import pytest
from openapi_assertions import assert_error_envelope, assert_openapi_schema

from trip_agent_ai.core.config import Settings
from trip_agent_ai.main import create_app

AUTH = {"Authorization": "Bearer test-service-token"}
TENANT_ID = UUID("10000000-0000-0000-0000-000000000021")
USER_A = UUID("20000000-0000-0000-0000-000000000021")
USER_B = UUID("20000000-0000-0000-0000-000000000022")


def _settings(lancedb_uri, *, upload_max_size_bytes: int = 100_000) -> Settings:
    return Settings(
        document_parser_mode="mock",
        lancedb_uri=lancedb_uri,
        ai_service_token="test-service-token",
        neo4j_password="test-neo4j-password",
        otel_enabled=False,
        retrieval_provider_mode="mock",
        retrieval_embedding_dimensions=64,
        graph_store_mode="memory",
        upload_max_size_bytes=upload_max_size_bytes,
    )


@asynccontextmanager
async def _client(settings: Settings) -> AsyncIterator[httpx.AsyncClient]:
    app = create_app(settings)
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app, raise_app_exceptions=False),
            base_url="http://testserver",
        ) as client:
            yield client


async def _parse(
    client: httpx.AsyncClient,
    filename: str,
    media_type: str,
    content: bytes,
    *,
    sha256: str | None = None,
) -> httpx.Response:
    return await client.post(
        "/internal/v1/documents/parse",
        headers=AUTH,
        data={
            "documentId": str(uuid4()),
            "taskId": str(uuid4()),
            "mediaType": media_type,
            "sha256": sha256 or hashlib.sha256(content).hexdigest(),
            "locale": "zh-CN",
        },
        files={"file": (filename, content, media_type)},
    )


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("filename", "media_type", "expected_type"),
    [
        ("hotel-confirmation.png", "image/png", "HOTEL"),
        ("duplicate-orders.pdf", "application/pdf", "FLIGHT"),
    ],
)
async def test_document_success_matches_openapi_and_preserves_money_and_time(
    isolated_lancedb_dir,
    qa_fixture_root,
    filename: str,
    media_type: str,
    expected_type: str,
) -> None:
    async with _client(_settings(isolated_lancedb_dir)) as client:
        content = (qa_fixture_root / "documents" / filename).read_bytes()
        response = await _parse(client, filename, media_type, content)

        assert response.status_code == 200, response.text
        body = response.json()
        assert_openapi_schema("BookingExtractionResult", body)
        assert body["bookings"][0]["type"] == expected_type
        amount = body["bookings"][0]["amount"]
        if amount is not None:
            assert Decimal(amount).as_tuple().exponent == -4
        for booking in body["bookings"]:
            for field in ("startTime", "endTime"):
                if booking[field] is not None:
                    value = datetime.fromisoformat(
                        booking[field].replace("Z", "+00:00")
                    )
                    assert value.tzinfo is not None
                    assert value.astimezone(UTC).utcoffset() == UTC.utcoffset(value)


@pytest.mark.asyncio
async def test_document_errors_use_contract_complete_envelopes(
    isolated_lancedb_dir,
    qa_fixture_root,
) -> None:
    async with _client(
        _settings(isolated_lancedb_dir, upload_max_size_bytes=20_000)
    ) as client:
        png = (qa_fixture_root / "documents/hotel-confirmation.png").read_bytes()
        cases = [
            (
                await _parse(client, "empty.png", "image/png", b""),
                413,
                "UPLOAD_SIZE_INVALID",
            ),
            (
                await _parse(client, "spoofed.jpg", "image/jpeg", png),
                415,
                "UPLOAD_MIME_MISMATCH",
            ),
            (
                await _parse(client, "large.png", "image/png", b"x" * 20_001),
                413,
                "UPLOAD_SIZE_INVALID",
            ),
            (
                await _parse(client, "hash.png", "image/png", png, sha256="0" * 64),
                422,
                "DOCUMENT_HASH_MISMATCH",
            ),
        ]
        for response, status, code in cases:
            assert response.status_code == status, response.text
            assert response.headers["content-type"].startswith(
                "application/problem+json"
            )
            assert_error_envelope(response.json(), code)


@pytest.mark.asyncio
async def test_memory_delete_and_user_isolation_are_preserved(
    isolated_lancedb_dir,
) -> None:
    preference_a = uuid4()
    preference_b = uuid4()
    async with _client(_settings(isolated_lancedb_dir)) as client:
        for user_id, preference_id, content in (
            (USER_A, preference_a, "用户偏好清淡餐厅，不吃辣。"),
            (USER_B, preference_b, "用户偏好川菜和辛辣口味。"),
        ):
            response = await client.post(
                "/internal/v1/memory/ingest",
                headers={
                    **AUTH,
                    "Idempotency-Key": f"memory-{preference_id}",
                    "X-Tenant-Id": str(TENANT_ID),
                    "X-User-Id": str(user_id),
                },
                json={"chunks": [_memory_chunk(preference_id, content)]},
            )
            assert response.status_code == 200, response.text
            assert_openapi_schema("IngestResponse", response.json())

        before_a = await _memory_search(client, USER_A)
        before_b = await _memory_search(client, USER_B)
        assert_openapi_schema("RetrievalSearchResponse", before_a)
        assert_openapi_schema("RetrievalSearchResponse", before_b)
        assert {item["sourceId"] for item in before_a["evidence"]} == {
            str(preference_a)
        }
        assert {item["sourceId"] for item in before_b["evidence"]} == {
            str(preference_b)
        }

        cross_scope_delete = await client.delete(
            f"/internal/v1/memory/{preference_b}",
            headers={
                **AUTH,
                "Idempotency-Key": f"delete-cross-{preference_b}",
                "X-Tenant-Id": str(TENANT_ID),
                "X-User-Id": str(USER_A),
            },
        )
        assert cross_scope_delete.status_code == 204
        assert {
            item["sourceId"]
            for item in (await _memory_search(client, USER_B))["evidence"]
        } == {str(preference_b)}

        deleted = await client.delete(
            f"/internal/v1/memory/{preference_a}",
            headers={
                **AUTH,
                "Idempotency-Key": f"delete-own-{preference_a}",
                "X-Tenant-Id": str(TENANT_ID),
                "X-User-Id": str(USER_A),
            },
        )
        assert deleted.status_code == 204
        assert (await _memory_search(client, USER_A))["evidence"] == []


def _memory_chunk(preference_id: UUID, content: str) -> dict[str, object]:
    identifier = str(preference_id)
    return {
        "chunkId": identifier,
        "sourceId": identifier,
        "sourceType": "DIETARY_RESTRICTION",
        "title": "用户饮食偏好",
        "city": "北京",
        "region": None,
        "validFrom": None,
        "validTo": None,
        "language": "zh-CN",
        "content": content,
        "updatedAt": "2026-09-08T00:00:00Z",
        "metadata": {"consent": "explicit", "synthetic": True},
    }


async def _memory_search(client: httpx.AsyncClient, user_id: UUID) -> dict:
    response = await client.post(
        "/internal/v1/retrieval/search",
        headers={
            **AUTH,
            "X-Tenant-Id": str(TENANT_ID),
            "X-User-Id": str(user_id),
        },
        json={
            "query": "根据我的偏好推荐餐厅",
            "city": "北京",
            "includePublicKnowledge": False,
            "includeUserMemory": True,
            "includeGraph": False,
            "maxResults": 10,
        },
    )
    assert response.status_code == 200, response.text
    return response.json()
