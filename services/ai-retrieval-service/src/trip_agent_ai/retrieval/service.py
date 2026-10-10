import asyncio
import hashlib
import json
from datetime import UTC, date, datetime
from decimal import ROUND_HALF_UP, Decimal
from difflib import SequenceMatcher
from uuid import NAMESPACE_URL, UUID, uuid5

import structlog
from neo4j.exceptions import Neo4jError
from opentelemetry import trace

from trip_agent_ai.core.config import Settings
from trip_agent_ai.core.errors import AppError
from trip_agent_ai.core.guardrails import isolate_retrieval_text
from trip_agent_ai.graph.store import GraphHit, GraphStore
from trip_agent_ai.rerank.cross_encoder import CrossEncoderReranker
from trip_agent_ai.retrieval.candidate import RetrievalCandidate
from trip_agent_ai.retrieval.embedding import EmbeddingProvider
from trip_agent_ai.retrieval.query_analysis import QueryAnalyzer
from trip_agent_ai.schemas.retrieval import (
    Evidence,
    GraphSearchRequest,
    GraphSearchResponse,
    IngestResponse,
    KnowledgeIngestRequest,
    MemoryIngestRequest,
    RetrievalLayer,
    RetrievalSearchRequest,
    RetrievalSearchResponse,
    RetrievalStrategy,
)
from trip_agent_ai.storage.lancedb_store import (
    IdempotencyConflictError,
    LanceDbStore,
    RecallHit,
)

logger = structlog.get_logger(__name__)
tracer = trace.get_tracer(__name__)
SCORE_QUANTUM = Decimal("0.0001")


class RetrievalService:
    def __init__(
        self,
        settings: Settings,
        vector_store: LanceDbStore,
        graph_store: GraphStore,
        embedding_provider: EmbeddingProvider,
        query_analyzer: QueryAnalyzer,
        reranker: CrossEncoderReranker,
    ) -> None:
        self._settings = settings
        self._vector_store = vector_store
        self._graph_store = graph_store
        self._embedding_provider = embedding_provider
        self._query_analyzer = query_analyzer
        self._reranker = reranker

    async def ingest_knowledge(
        self,
        request: KnowledgeIngestRequest,
        idempotency_key: str,
    ) -> IngestResponse:
        scope = "knowledge"
        payload_hash = _payload_hash(request)
        cached = await self._cached_ingestion(scope, idempotency_key, payload_hash)
        if cached is not None:
            return cached
        with tracer.start_as_current_span("retrieval.embed_ingest"):
            embeddings = await self._embedding_provider.embed(
                [
                    _embedding_text(chunk.title, chunk.content)
                    for chunk in request.chunks
                ]
            )
        try:
            with tracer.start_as_current_span("lancedb.public_upsert"):
                indexed_count = await asyncio.to_thread(
                    self._vector_store.upsert_public,
                    request.chunks,
                    embeddings,
                )
        except (OSError, RuntimeError, ValueError) as exc:
            raise _dependency_error("LANCEDB_WRITE_FAILED") from exc

        graph_nodes = 0
        graph_relationships = 0
        if request.graph is not None:
            try:
                with tracer.start_as_current_span("neo4j.public_graph_upsert"):
                    graph_nodes, graph_relationships = await self._graph_store.ingest(
                        request.graph
                    )
            except Neo4jError as exc:
                raise _dependency_error("NEO4J_WRITE_FAILED") from exc
        _log_mock_mode(self._embedding_provider.mode.value, "knowledge_ingest")
        response = IngestResponse(
            ingestionId=_ingestion_id(scope, idempotency_key),
            layer=RetrievalLayer.PUBLIC_KNOWLEDGE,
            indexedCount=indexed_count,
            graphNodesUpserted=graph_nodes,
            graphRelationshipsUpserted=graph_relationships,
            embeddingDimensions=self._embedding_provider.dimensions,
            providerMode=self._embedding_provider.mode,
        )
        await self._record_ingestion(
            scope,
            idempotency_key,
            payload_hash,
            response,
        )
        return response

    async def ingest_memory(
        self,
        request: MemoryIngestRequest,
        idempotency_key: str,
        tenant_id: UUID,
        user_id: UUID,
    ) -> IngestResponse:
        scope = f"memory:{tenant_id}:{user_id}"
        payload_hash = _payload_hash(request)
        cached = await self._cached_ingestion(scope, idempotency_key, payload_hash)
        if cached is not None:
            return cached
        with tracer.start_as_current_span("retrieval.embed_ingest"):
            embeddings = await self._embedding_provider.embed(
                [
                    _embedding_text(chunk.title, chunk.content)
                    for chunk in request.chunks
                ]
            )
        try:
            with tracer.start_as_current_span("lancedb.memory_upsert"):
                indexed_count = await asyncio.to_thread(
                    self._vector_store.upsert_memory,
                    tenant_id,
                    user_id,
                    request.chunks,
                    embeddings,
                )
        except (OSError, RuntimeError, ValueError) as exc:
            raise _dependency_error("LANCEDB_WRITE_FAILED") from exc
        _log_mock_mode(self._embedding_provider.mode.value, "memory_ingest")
        response = IngestResponse(
            ingestionId=_ingestion_id(
                scope,
                idempotency_key,
            ),
            layer=RetrievalLayer.USER_MEMORY,
            indexedCount=indexed_count,
            graphNodesUpserted=0,
            graphRelationshipsUpserted=0,
            embeddingDimensions=self._embedding_provider.dimensions,
            providerMode=self._embedding_provider.mode,
        )
        await self._record_ingestion(
            scope,
            idempotency_key,
            payload_hash,
            response,
        )
        return response

    async def delete_memory(
        self,
        preference_id: UUID,
        idempotency_key: str,
        tenant_id: UUID,
        user_id: UUID,
    ) -> None:
        del idempotency_key
        try:
            with tracer.start_as_current_span("lancedb.memory_delete"):
                await asyncio.to_thread(
                    self._vector_store.delete_memory,
                    tenant_id,
                    user_id,
                    preference_id,
                )
        except (OSError, RuntimeError, ValueError) as exc:
            raise _dependency_error("LANCEDB_DELETE_FAILED") from exc

    @tracer.start_as_current_span("rag.retrieval")
    async def search(
        self,
        request: RetrievalSearchRequest,
        tenant_id: UUID | None,
        user_id: UUID | None,
    ) -> RetrievalSearchResponse:
        root_span = trace.get_current_span()
        root_span.set_attribute("retrieval.operation", "hybrid_search")
        root_span.set_attribute(
            "retrieval.provider.mode",
            self._embedding_provider.mode.value,
        )
        _validate_user_scope(request, tenant_id, user_id)
        memory_scope_available = tenant_id is not None and user_id is not None
        with tracer.start_as_current_span("retrieval.query_analysis") as analysis_span:
            plan = await self._query_analyzer.analyze(
                request,
                memory_scope_available,
            )
            analysis_span.set_attribute("retrieval.route.count", len(plan.routes))
        query_embedding: list[float] | None = None
        if any(
            route
            in {
                RetrievalLayer.PUBLIC_KNOWLEDGE,
                RetrievalLayer.USER_MEMORY,
            }
            for route in plan.routes
        ):
            with tracer.start_as_current_span("retrieval.query_embedding"):
                query_embedding = (
                    await self._embedding_provider.embed([plan.rewrittenQuery])
                )[0]

        candidates: list[RetrievalCandidate] = []
        warnings: list[str] = []
        as_of = request.travelDate or datetime.now(UTC).date()
        recall_limit = min(
            self._settings.retrieval_max_candidates,
            max(request.maxResults * 4, 10),
        )
        for route in plan.routes:
            try:
                if route is RetrievalLayer.GRAPH and request.includeGraph:
                    graph_request = GraphSearchRequest(
                        query=plan.rewrittenQuery,
                        scope=plan.graphScope,
                        anchorEntity=plan.anchorEntity,
                        city=request.city,
                        region=request.region,
                        travelDate=as_of,
                        language=request.language,
                        maxResults=min(recall_limit, 20),
                    )
                    candidates.extend(await self._graph_candidates(graph_request))
                elif (
                    route is RetrievalLayer.PUBLIC_KNOWLEDGE
                    and request.includePublicKnowledge
                    and query_embedding is not None
                ):
                    candidates.extend(
                        await self._public_candidates(
                            plan.rewrittenQuery,
                            query_embedding,
                            request,
                            as_of,
                            recall_limit,
                        )
                    )
                elif (
                    route is RetrievalLayer.USER_MEMORY
                    and request.includeUserMemory
                    and query_embedding is not None
                    and tenant_id is not None
                    and user_id is not None
                ):
                    candidates.extend(
                        await self._memory_candidates(
                            plan.rewrittenQuery,
                            query_embedding,
                            request,
                            as_of,
                            recall_limit,
                            tenant_id,
                            user_id,
                        )
                    )
                elif route is RetrievalLayer.REALTIME_TOOL:
                    warnings.append("REALTIME_TOOL_REQUIRED")
            except AppError as exc:
                if exc.code not in {
                    "LANCEDB_SEARCH_FAILED",
                    "NEO4J_SEARCH_FAILED",
                }:
                    raise
                warnings.append(exc.code)

        dependency_warnings = [
            warning
            for warning in warnings
            if warning in {"LANCEDB_SEARCH_FAILED", "NEO4J_SEARCH_FAILED"}
        ]
        if not candidates and dependency_warnings:
            raise _dependency_error(dependency_warnings[0])

        deduplicated = _deduplicate_candidates(candidates)
        with tracer.start_as_current_span(
            "retrieval.cross_encoder_rerank"
        ) as rerank_span:
            rerank_span.set_attribute("retrieval.candidate.count", len(deduplicated))
            rerank_span.set_attribute("gen_ai.prompt.version", "reranker-v1.0.0")
            reranked = await self._reranker.rerank(
                plan.rewrittenQuery,
                deduplicated,
            )
            rerank_span.set_attribute("retrieval.result.count", len(reranked))
        evidence = _compress_and_package(
            reranked,
            request.maxResults,
            self._settings.retrieval_context_max_chars,
            self._settings.retrieval_min_relevance_score,
        )
        root_span.set_attribute("retrieval.candidate.count", len(candidates))
        root_span.set_attribute("retrieval.result.count", len(evidence))
        root_span.set_attribute("operation.success", True)
        _log_mock_mode(self._embedding_provider.mode.value, "retrieval_search")
        return RetrievalSearchResponse(
            rewrittenQuery=plan.rewrittenQuery,
            intent=plan.intent,
            routes=plan.routes,
            evidence=evidence,
            evidenceInsufficient=not evidence,
            realtimeToolRequired=plan.realtimeToolRequired,
            providerMode=self._embedding_provider.mode,
            warnings=warnings,
        )

    @tracer.start_as_current_span("rag.graph_retrieval")
    async def graph_search(
        self,
        request: GraphSearchRequest,
    ) -> GraphSearchResponse:
        scope, graph_hits = await self._safe_graph_search(request)
        candidates = [
            candidate
            for hit in graph_hits
            if (candidate := _graph_candidate(hit)) is not None
        ]
        with tracer.start_as_current_span(
            "retrieval.cross_encoder_rerank"
        ) as rerank_span:
            rerank_span.set_attribute("retrieval.candidate.count", len(candidates))
            rerank_span.set_attribute("gen_ai.prompt.version", "reranker-v1.0.0")
            reranked = await self._reranker.rerank(
                request.query,
                _deduplicate_candidates(candidates),
            )
            rerank_span.set_attribute("retrieval.result.count", len(reranked))
        evidence = _compress_and_package(
            reranked,
            request.maxResults,
            self._settings.retrieval_context_max_chars,
            self._settings.retrieval_min_relevance_score,
        )
        _log_mock_mode(self._embedding_provider.mode.value, "graph_search")
        return GraphSearchResponse(
            scope=scope,
            evidence=evidence,
            evidenceInsufficient=not evidence,
            providerMode=self._embedding_provider.mode,
        )

    async def _public_candidates(
        self,
        query: str,
        query_embedding: list[float],
        request: RetrievalSearchRequest,
        as_of: date,
        limit: int,
    ) -> list[RetrievalCandidate]:
        try:
            with tracer.start_as_current_span("lancedb.public_search") as span:
                hits = await asyncio.to_thread(
                    self._vector_store.hybrid_search_public,
                    query,
                    query_embedding,
                    city=request.city,
                    region=request.region,
                    language=request.language,
                    as_of=as_of,
                    limit=limit,
                )
                span.set_attribute("retrieval.result.count", len(hits))
        except (OSError, RuntimeError, ValueError) as exc:
            raise _dependency_error("LANCEDB_SEARCH_FAILED") from exc
        return [
            candidate
            for hit in hits
            if _record_is_current(hit.record, as_of)
            and (
                candidate := _recall_candidate(
                    hit,
                    RetrievalLayer.PUBLIC_KNOWLEDGE,
                )
            )
            is not None
        ]

    async def _memory_candidates(
        self,
        query: str,
        query_embedding: list[float],
        request: RetrievalSearchRequest,
        as_of: date,
        limit: int,
        tenant_id: UUID,
        user_id: UUID,
    ) -> list[RetrievalCandidate]:
        try:
            with tracer.start_as_current_span("lancedb.memory_search") as span:
                hits = await asyncio.to_thread(
                    self._vector_store.hybrid_search_memory,
                    query,
                    query_embedding,
                    tenant_id=tenant_id,
                    user_id=user_id,
                    city=request.city,
                    region=request.region,
                    language=request.language,
                    as_of=as_of,
                    limit=limit,
                )
                span.set_attribute("retrieval.result.count", len(hits))
        except (OSError, RuntimeError, ValueError) as exc:
            raise _dependency_error("LANCEDB_SEARCH_FAILED") from exc
        candidates = [
            candidate
            for hit in hits
            if hit.record.get("tenantId") == str(tenant_id)
            and hit.record.get("userId") == str(user_id)
            and _record_is_current(hit.record, as_of)
            and (
                candidate := _recall_candidate(
                    hit,
                    RetrievalLayer.USER_MEMORY,
                )
            )
            is not None
        ]
        for candidate in candidates:
            candidate.strategies.add(RetrievalStrategy.USER_MEMORY)
        return candidates

    async def _graph_candidates(
        self,
        request: GraphSearchRequest,
    ) -> list[RetrievalCandidate]:
        _, hits = await self._safe_graph_search(request)
        return [
            candidate
            for hit in hits
            if (candidate := _graph_candidate(hit)) is not None
        ]

    async def _safe_graph_search(
        self,
        request: GraphSearchRequest,
    ):
        try:
            with tracer.start_as_current_span("neo4j.graph_search") as span:
                async with asyncio.timeout(self._settings.retrieval_timeout_seconds):
                    scope, hits = await self._graph_store.search(request)
                span.set_attribute("retrieval.result.count", len(hits))
                return scope, hits
        except (Neo4jError, TimeoutError) as exc:
            raise _dependency_error("NEO4J_SEARCH_FAILED") from exc

    async def _cached_ingestion(
        self,
        scope: str,
        idempotency_key: str,
        payload_hash: str,
    ) -> IngestResponse | None:
        try:
            cached = await asyncio.to_thread(
                self._vector_store.find_ingestion,
                scope,
                idempotency_key,
                payload_hash,
            )
        except IdempotencyConflictError as exc:
            raise AppError(
                code="IDEMPOTENCY_KEY_REUSED",
                title="Idempotency key conflict",
                status=409,
                detail="The idempotency key was already used with another payload.",
            ) from exc
        except (OSError, RuntimeError, ValueError) as exc:
            raise _dependency_error("LANCEDB_READ_FAILED") from exc
        return IngestResponse.model_validate(cached) if cached is not None else None

    async def _record_ingestion(
        self,
        scope: str,
        idempotency_key: str,
        payload_hash: str,
        response: IngestResponse,
    ) -> None:
        try:
            await asyncio.to_thread(
                self._vector_store.record_ingestion,
                scope,
                idempotency_key,
                payload_hash,
                response.model_dump(mode="json"),
            )
        except (OSError, RuntimeError, ValueError) as exc:
            raise _dependency_error("LANCEDB_WRITE_FAILED") from exc


def _embedding_text(title: str, content: str) -> str:
    return f"{title}\n{content}"


def _payload_hash(request: KnowledgeIngestRequest | MemoryIngestRequest) -> str:
    canonical = json.dumps(
        request.model_dump(mode="json"),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def _ingestion_id(scope: str, idempotency_key: str) -> UUID:
    return uuid5(NAMESPACE_URL, f"tripagent:{scope}:{idempotency_key}")


def _validate_user_scope(
    request: RetrievalSearchRequest,
    tenant_id: UUID | None,
    user_id: UUID | None,
) -> None:
    if (tenant_id is None) != (user_id is None):
        raise AppError(
            code="USER_SCOPE_INCOMPLETE",
            title="User scope is incomplete",
            status=422,
            detail="X-Tenant-Id and X-User-Id must be supplied together.",
        )
    if request.includeUserMemory and (tenant_id is None or user_id is None):
        raise AppError(
            code="USER_SCOPE_REQUIRED",
            title="User scope is required",
            status=422,
            detail="User-memory retrieval requires trusted tenant and user headers.",
        )


def _recall_candidate(
    hit: RecallHit,
    layer: RetrievalLayer,
) -> RetrievalCandidate | None:
    record = hit.record
    title = str(record["title"])
    if isolate_retrieval_text(title) is None:
        return None
    excerpt = isolate_retrieval_text(str(record["content"]))
    if excerpt is None:
        return None
    return RetrievalCandidate(
        chunk_id=str(record["chunkId"]),
        source_id=str(record["sourceId"]),
        source_type=str(record["sourceType"]),
        title=title,
        excerpt=excerpt,
        updated_at=_parse_datetime(str(record["updatedAt"])),
        layer=layer,
        city=str(record.get("city") or "") or None,
        region=str(record.get("region") or "") or None,
        score=hit.score,
        strategies=set(hit.strategies),
    )


def _graph_candidate(hit: GraphHit) -> RetrievalCandidate | None:
    record = hit.record
    title = str(record["title"])
    if isolate_retrieval_text(title) is None:
        return None
    excerpt = isolate_retrieval_text(str(record["excerpt"]))
    if excerpt is None:
        return None
    return RetrievalCandidate(
        chunk_id=None,
        source_id=str(record["sourceId"]),
        source_type=str(record["sourceType"]),
        title=title,
        excerpt=excerpt,
        updated_at=_parse_datetime(str(record["updatedAt"])),
        layer=RetrievalLayer.GRAPH,
        city=str(record.get("city") or "") or None,
        region=str(record.get("region") or "") or None,
        score=hit.score,
        strategies={hit.strategy},
    )


def _deduplicate_candidates(
    candidates: list[RetrievalCandidate],
) -> list[RetrievalCandidate]:
    accepted: list[RetrievalCandidate] = []
    for candidate in sorted(candidates, key=lambda item: item.score, reverse=True):
        duplicate = next(
            (
                item
                for item in accepted
                if item.source_id == candidate.source_id
                and (
                    RetrievalLayer.GRAPH in {item.layer, candidate.layer}
                    or _similarity(item.excerpt, candidate.excerpt) >= 0.82
                )
            ),
            None,
        )
        if duplicate is None:
            accepted.append(candidate)
            continue
        duplicate.strategies.update(candidate.strategies)
        duplicate.score = max(duplicate.score, candidate.score)
        if candidate.layer is RetrievalLayer.GRAPH:
            duplicate.layer = RetrievalLayer.GRAPH
    return accepted


def _compress_and_package(
    candidates: list[RetrievalCandidate],
    max_results: int,
    max_chars: int,
    minimum_score: float,
) -> list[Evidence]:
    evidence: list[Evidence] = []
    consumed = 0
    for candidate in candidates:
        if candidate.score < minimum_score:
            continue
        excerpt = candidate.excerpt
        remaining = max_chars - consumed
        if remaining <= 0:
            break
        if len(excerpt) > remaining:
            excerpt = _excerpt(excerpt, remaining)
        if not excerpt:
            break
        evidence.append(
            Evidence(
                chunkId=candidate.chunk_id,
                sourceId=candidate.source_id,
                sourceType=candidate.source_type,
                title=candidate.title,
                excerpt=excerpt,
                relevanceScore=_score(candidate.score),
                updatedAt=candidate.updated_at,
                retrievalStrategies=sorted(
                    candidate.strategies,
                    key=lambda strategy: strategy.value,
                ),
                fromRealtimeTool=candidate.from_realtime_tool,
                layer=candidate.layer,
                city=candidate.city,
                region=candidate.region,
            )
        )
        consumed += len(excerpt)
        if len(evidence) >= max_results:
            break
    return evidence


def _record_is_current(record: dict[str, object], as_of: date) -> bool:
    valid_from = str(record.get("validFrom") or "")
    valid_to = str(record.get("validTo") or "")
    current = as_of.isoformat()
    return (not valid_from or valid_from <= current) and (
        not valid_to or valid_to >= current
    )


def _excerpt(content: str, limit: int = 1000) -> str:
    normalized = " ".join(content.split())
    if len(normalized) <= limit:
        return normalized
    if limit <= 1:
        return normalized[:limit]
    return normalized[: limit - 1].rstrip() + "…"


def _parse_datetime(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=UTC)
    return parsed.astimezone(UTC)


def _score(value: float) -> Decimal:
    bounded = min(max(value, 0.0), 1.0)
    return Decimal(str(bounded)).quantize(SCORE_QUANTUM, rounding=ROUND_HALF_UP)


def _similarity(left: str, right: str) -> float:
    return SequenceMatcher(None, left.casefold(), right.casefold()).ratio()


def _dependency_error(code: str) -> AppError:
    return AppError(
        code=code,
        title="Retrieval dependency unavailable",
        status=503,
        detail="A required retrieval dependency could not complete the operation.",
        retryable=True,
    )


def _log_mock_mode(mode: str, operation: str) -> None:
    if mode == "MOCK":
        logger.warning(
            "retrieval_mock_provider_active",
            operation=operation,
            providerMode=mode,
        )
