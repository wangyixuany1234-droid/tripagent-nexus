from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Header, Path, Request

from trip_agent_ai.core.guardrails import require_safe_prompt_input
from trip_agent_ai.core.security import require_service_auth
from trip_agent_ai.retrieval.service import RetrievalService
from trip_agent_ai.schemas.retrieval import (
    GraphSearchRequest,
    GraphSearchResponse,
    IngestResponse,
    KnowledgeIngestRequest,
    MemoryIngestRequest,
    RetrievalSearchRequest,
    RetrievalSearchResponse,
)

router = APIRouter(
    prefix="/internal/v1",
    tags=["Internal Retrieval"],
    dependencies=[Depends(require_service_auth)],
)

IdempotencyKey = Annotated[
    str,
    Header(
        alias="Idempotency-Key",
        min_length=8,
        max_length=128,
        pattern=r"^[A-Za-z0-9._:-]+$",
    ),
]
TenantId = Annotated[UUID, Header(alias="X-Tenant-Id")]
UserId = Annotated[UUID, Header(alias="X-User-Id")]
OptionalTenantId = Annotated[UUID | None, Header(alias="X-Tenant-Id")]
OptionalUserId = Annotated[UUID | None, Header(alias="X-User-Id")]
Traceparent = Annotated[
    str | None,
    Header(alias="traceparent", max_length=128),
]


def get_retrieval_service(request: Request) -> RetrievalService:
    return request.app.state.retrieval_service


@router.post(
    "/knowledge/ingest",
    response_model=IngestResponse,
    operation_id="ingestPublicKnowledge",
)
async def ingest_public_knowledge(
    body: KnowledgeIngestRequest,
    idempotency_key: IdempotencyKey,
    service: Annotated[RetrievalService, Depends(get_retrieval_service)],
    traceparent: Traceparent = None,
) -> IngestResponse:
    return await service.ingest_knowledge(body, idempotency_key)


@router.post(
    "/memory/ingest",
    response_model=IngestResponse,
    operation_id="ingestUserMemory",
)
async def ingest_user_memory(
    body: MemoryIngestRequest,
    idempotency_key: IdempotencyKey,
    tenant_id: TenantId,
    user_id: UserId,
    service: Annotated[RetrievalService, Depends(get_retrieval_service)],
    traceparent: Traceparent = None,
) -> IngestResponse:
    return await service.ingest_memory(
        body,
        idempotency_key,
        tenant_id,
        user_id,
    )


@router.delete(
    "/memory/{preferenceId}",
    status_code=204,
    operation_id="deleteUserMemory",
)
async def delete_user_memory(
    preference_id: Annotated[UUID, Path(alias="preferenceId")],
    idempotency_key: IdempotencyKey,
    tenant_id: TenantId,
    user_id: UserId,
    service: Annotated[RetrievalService, Depends(get_retrieval_service)],
    traceparent: Traceparent = None,
) -> None:
    await service.delete_memory(preference_id, idempotency_key, tenant_id, user_id)


@router.post(
    "/retrieval/search",
    response_model=RetrievalSearchResponse,
    operation_id="searchTravelKnowledge",
)
async def search_retrieval(
    body: RetrievalSearchRequest,
    service: Annotated[RetrievalService, Depends(get_retrieval_service)],
    tenant_id: OptionalTenantId = None,
    user_id: OptionalUserId = None,
    traceparent: Traceparent = None,
) -> RetrievalSearchResponse:
    require_safe_prompt_input(body.query)
    return await service.search(body, tenant_id, user_id)


@router.post(
    "/graph/search",
    response_model=GraphSearchResponse,
    operation_id="searchTravelGraph",
)
async def search_graph(
    body: GraphSearchRequest,
    service: Annotated[RetrievalService, Depends(get_retrieval_service)],
    traceparent: Traceparent = None,
) -> GraphSearchResponse:
    require_safe_prompt_input(body.query)
    return await service.graph_search(body)
