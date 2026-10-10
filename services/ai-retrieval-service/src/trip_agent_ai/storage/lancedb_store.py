import json
import threading
from dataclasses import dataclass, field
from datetime import UTC, date
from pathlib import Path
from typing import Any
from uuid import UUID

import lancedb
import pyarrow as pa
from lancedb.index import FTS

from trip_agent_ai.core.config import Settings
from trip_agent_ai.schemas.retrieval import (
    KnowledgeChunkInput,
    MemoryChunkInput,
    RetrievalStrategy,
)

INGESTION_LEDGER_TABLE = "_retrieval_ingestion_ledger"


@dataclass(slots=True)
class RecallHit:
    record: dict[str, Any]
    score: float = 0.0
    strategies: set[RetrievalStrategy] = field(default_factory=set)


class IdempotencyConflictError(ValueError):
    pass


class LanceDbStore:
    def __init__(self, uri: Path, settings: Settings | None = None) -> None:
        self._uri = uri
        self._dimensions = settings.retrieval_embedding_dimensions if settings else 1024
        self._public_table = (
            settings.lancedb_public_table if settings else "travel_public"
        )
        self._memory_table = (
            settings.lancedb_user_memory_table if settings else "user_memory"
        )
        self._lock = threading.RLock()

    def verify_connectivity(self) -> None:
        self._uri.mkdir(parents=True, exist_ok=True)
        lancedb.connect(str(self._uri))

    def find_ingestion(
        self,
        scope: str,
        idempotency_key: str,
        payload_hash: str,
    ) -> dict[str, Any] | None:
        with self._lock:
            database = self._connect()
            if INGESTION_LEDGER_TABLE not in set(database.list_tables().tables):
                return None
            table = database.open_table(INGESTION_LEDGER_TABLE)
            rows = (
                table.search()
                .where(
                    " AND ".join(
                        [
                            f"scope = {_literal(scope)}",
                            f"idempotencyKey = {_literal(idempotency_key)}",
                        ]
                    )
                )
                .limit(1)
                .to_list()
            )
            if not rows:
                return None
            row = rows[0]
            if row["payloadHash"] != payload_hash:
                raise IdempotencyConflictError(
                    "idempotency key was already used with another payload"
                )
            return json.loads(str(row["response"]))

    def record_ingestion(
        self,
        scope: str,
        idempotency_key: str,
        payload_hash: str,
        response: dict[str, Any],
    ) -> None:
        row = {
            "scope": scope,
            "idempotencyKey": idempotency_key,
            "payloadHash": payload_hash,
            "response": json.dumps(
                response,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ),
        }
        with self._lock:
            database = self._connect()
            if INGESTION_LEDGER_TABLE in set(database.list_tables().tables):
                table = database.open_table(INGESTION_LEDGER_TABLE)
            else:
                table = database.create_table(
                    INGESTION_LEDGER_TABLE,
                    schema=pa.schema(
                        [
                            pa.field("scope", pa.string(), nullable=False),
                            pa.field("idempotencyKey", pa.string(), nullable=False),
                            pa.field("payloadHash", pa.string(), nullable=False),
                            pa.field("response", pa.string(), nullable=False),
                        ]
                    ),
                )
            (
                table.merge_insert(["scope", "idempotencyKey"])
                .when_matched_update_all()
                .when_not_matched_insert_all()
                .execute([row])
            )

    def upsert_public(
        self,
        chunks: list[KnowledgeChunkInput],
        embeddings: list[list[float]],
    ) -> int:
        rows = [
            _public_row(chunk, embedding)
            for chunk, embedding in zip(chunks, embeddings, strict=True)
        ]
        self._upsert(self._public_table, rows, ["city", "chunkId"], memory=False)
        return len(rows)

    def upsert_memory(
        self,
        tenant_id: UUID,
        user_id: UUID,
        chunks: list[MemoryChunkInput],
        embeddings: list[list[float]],
    ) -> int:
        rows = [
            _memory_row(tenant_id, user_id, chunk, embedding)
            for chunk, embedding in zip(chunks, embeddings, strict=True)
        ]
        self._upsert(
            self._memory_table,
            rows,
            ["tenantId", "userId", "chunkId"],
            memory=True,
        )
        return len(rows)

    def delete_memory(
        self,
        tenant_id: UUID,
        user_id: UUID,
        preference_id: UUID,
    ) -> None:
        with self._lock:
            database = self._connect()
            if self._memory_table not in set(database.list_tables().tables):
                return
            identifier = str(preference_id)
            database.open_table(self._memory_table).delete(
                " AND ".join(
                    [
                        f"tenantId = {_literal(str(tenant_id))}",
                        f"userId = {_literal(str(user_id))}",
                        (
                            f"(chunkId = {_literal(identifier)} "
                            f"OR sourceId = {_literal(identifier)})"
                        ),
                    ]
                )
            )

    def hybrid_search_public(
        self,
        query: str,
        query_embedding: list[float],
        *,
        city: str | None,
        region: str | None,
        language: str,
        as_of: date,
        limit: int,
    ) -> list[RecallHit]:
        filters = _base_filters(city, region, language, as_of)
        return self._hybrid_search(
            self._public_table,
            query,
            query_embedding,
            filters,
            limit,
            memory_scope=None,
        )

    def hybrid_search_memory(
        self,
        query: str,
        query_embedding: list[float],
        *,
        tenant_id: UUID,
        user_id: UUID,
        city: str | None,
        region: str | None,
        language: str,
        as_of: date,
        limit: int,
    ) -> list[RecallHit]:
        scope = (str(tenant_id), str(user_id))
        filters = _base_filters(
            city,
            region,
            language,
            as_of,
            include_unscoped_location=True,
        )
        filters.extend(
            [
                f"tenantId = {_literal(scope[0])}",
                f"userId = {_literal(scope[1])}",
            ]
        )
        return self._hybrid_search(
            self._memory_table,
            query,
            query_embedding,
            filters,
            limit,
            memory_scope=scope,
        )

    def _upsert(
        self,
        table_name: str,
        rows: list[dict[str, Any]],
        merge_keys: list[str],
        *,
        memory: bool,
    ) -> None:
        if not rows:
            return
        with self._lock:
            table = self._open_or_create(table_name, memory)
            (
                table.merge_insert(merge_keys)
                .when_matched_update_all()
                .when_not_matched_insert_all()
                .execute(rows)
            )
            table.create_index(
                "content",
                config=FTS(
                    base_tokenizer="ngram",
                    ngram_min_length=1,
                    ngram_max_length=3,
                    lower_case=True,
                    stem=False,
                    remove_stop_words=False,
                    ascii_folding=False,
                ),
                replace=True,
            )

    def _hybrid_search(
        self,
        table_name: str,
        query: str,
        query_embedding: list[float],
        filters: list[str],
        limit: int,
        memory_scope: tuple[str, str] | None,
    ) -> list[RecallHit]:
        with self._lock:
            database = self._connect()
            if table_name not in set(database.list_tables().tables):
                return []
            table = database.open_table(table_name)
            where = " AND ".join(filters)
            vector_rows = (
                table.search(query_embedding, vector_column_name="embedding")
                .where(where, prefilter=True)
                .limit(limit)
                .to_list()
            )
            keyword_rows = (
                table.search(query, query_type="fts", fts_columns="content")
                .where(where, prefilter=True)
                .limit(limit)
                .to_list()
            )
        return _fuse(vector_rows, keyword_rows, limit, memory_scope)

    def _open_or_create(self, table_name: str, memory: bool):
        database = self._connect()
        if table_name in set(database.list_tables().tables):
            table = database.open_table(table_name)
            vector_type = table.schema.field("embedding").type
            if vector_type.list_size != self._dimensions:
                raise ValueError(
                    f"LanceDB table {table_name} embedding dimension mismatch"
                )
            return table
        return database.create_table(
            table_name,
            schema=_schema(self._dimensions, memory),
        )

    def _connect(self):
        self._uri.mkdir(parents=True, exist_ok=True)
        return lancedb.connect(str(self._uri))


def _schema(dimensions: int, memory: bool) -> pa.Schema:
    fields = [
        pa.field("chunkId", pa.string(), nullable=False),
        pa.field("sourceId", pa.string(), nullable=False),
        pa.field("sourceType", pa.string(), nullable=False),
        pa.field("title", pa.string(), nullable=False),
        pa.field("city", pa.string(), nullable=False),
        pa.field("region", pa.string(), nullable=False),
        pa.field("validFrom", pa.string(), nullable=False),
        pa.field("validTo", pa.string(), nullable=False),
        pa.field("language", pa.string(), nullable=False),
        pa.field("content", pa.string(), nullable=False),
        pa.field(
            "embedding",
            pa.list_(pa.float32(), dimensions),
            nullable=False,
        ),
        pa.field("updatedAt", pa.string(), nullable=False),
        pa.field("metadata", pa.string(), nullable=False),
    ]
    if memory:
        fields.extend(
            [
                pa.field("tenantId", pa.string(), nullable=False),
                pa.field("userId", pa.string(), nullable=False),
            ]
        )
    return pa.schema(fields)


def _public_row(
    chunk: KnowledgeChunkInput,
    embedding: list[float],
) -> dict[str, Any]:
    return _base_row(chunk, embedding)


def _memory_row(
    tenant_id: UUID,
    user_id: UUID,
    chunk: MemoryChunkInput,
    embedding: list[float],
) -> dict[str, Any]:
    return {
        **_base_row(chunk, embedding),
        "tenantId": str(tenant_id),
        "userId": str(user_id),
    }


def _base_row(
    chunk: KnowledgeChunkInput | MemoryChunkInput,
    embedding: list[float],
) -> dict[str, Any]:
    return {
        "chunkId": chunk.chunkId,
        "sourceId": chunk.sourceId,
        "sourceType": chunk.sourceType.value,
        "title": chunk.title,
        "city": chunk.city or "",
        "region": chunk.region or "",
        "validFrom": chunk.validFrom.isoformat() if chunk.validFrom else "",
        "validTo": chunk.validTo.isoformat() if chunk.validTo else "",
        "language": chunk.language,
        "content": chunk.content,
        "embedding": embedding,
        "updatedAt": chunk.updatedAt.astimezone(UTC).isoformat(),
        "metadata": json.dumps(
            chunk.metadata,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ),
    }


def _base_filters(
    city: str | None,
    region: str | None,
    language: str,
    as_of: date,
    *,
    include_unscoped_location: bool = False,
) -> list[str]:
    current = as_of.isoformat()
    filters = [
        f"language = {_literal(language)}",
        f"(validFrom = '' OR validFrom <= {_literal(current)})",
        f"(validTo = '' OR validTo >= {_literal(current)})",
    ]
    if city:
        condition = f"city = {_literal(city)}"
        filters.append(
            f"(city = '' OR {condition})" if include_unscoped_location else condition
        )
    if region:
        condition = f"region = {_literal(region)}"
        filters.append(
            f"(region = '' OR {condition})" if include_unscoped_location else condition
        )
    return filters


def _literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def _fuse(
    vector_rows: list[dict[str, Any]],
    keyword_rows: list[dict[str, Any]],
    limit: int,
    memory_scope: tuple[str, str] | None,
) -> list[RecallHit]:
    hits: dict[str, RecallHit] = {}
    for rank, row in enumerate(vector_rows, start=1):
        key = _row_key(row, memory_scope)
        if not _belongs_to_scope(row, memory_scope):
            continue
        distance = max(float(row.get("_distance", 4.0)), 0.0)
        hit = hits.setdefault(key, RecallHit(record=_clean_row(row)))
        hit.score += 0.55 * (1.0 / (1.0 + distance)) + 1.0 / (60 + rank)
        hit.strategies.add(RetrievalStrategy.VECTOR)

    max_keyword = max(
        (max(float(row.get("_score", 0.0)), 0.0) for row in keyword_rows),
        default=1.0,
    )
    for rank, row in enumerate(keyword_rows, start=1):
        key = _row_key(row, memory_scope)
        if not _belongs_to_scope(row, memory_scope):
            continue
        keyword_score = max(float(row.get("_score", 0.0)), 0.0) / max_keyword
        hit = hits.setdefault(key, RecallHit(record=_clean_row(row)))
        hit.score += 0.35 * keyword_score + 1.0 / (60 + rank)
        hit.strategies.add(RetrievalStrategy.KEYWORD)

    for hit in hits.values():
        if len(hit.strategies) > 1:
            hit.strategies.add(RetrievalStrategy.HYBRID)
        hit.score = min(hit.score, 1.0)
    return sorted(hits.values(), key=lambda item: item.score, reverse=True)[:limit]


def _row_key(
    row: dict[str, Any],
    memory_scope: tuple[str, str] | None,
) -> str:
    if memory_scope is None:
        return ":".join((str(row.get("city", "")), str(row["chunkId"])))
    return ":".join(
        (
            str(row.get("tenantId", "")),
            str(row.get("userId", "")),
            str(row["chunkId"]),
        )
    )


def _belongs_to_scope(
    row: dict[str, Any],
    memory_scope: tuple[str, str] | None,
) -> bool:
    if memory_scope is None:
        return True
    return (
        row.get("tenantId") == memory_scope[0] and row.get("userId") == memory_scope[1]
    )


def _clean_row(row: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in row.items() if not key.startswith("_")}
