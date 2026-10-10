from datetime import date, datetime
from decimal import Decimal
from enum import StrEnum
from typing import Any, Self
from uuid import UUID

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StrictBool,
    field_validator,
    model_validator,
)


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ProviderMode(StrEnum):
    DEEPSEEK = "DEEPSEEK"
    MOCK = "MOCK"


class KnowledgeSourceType(StrEnum):
    CITY_GUIDE = "CITY_GUIDE"
    ATTRACTION = "ATTRACTION"
    REGION_GUIDE = "REGION_GUIDE"
    TRANSIT_RULE = "TRANSIT_RULE"
    OPENING_HOURS = "OPENING_HOURS"
    NOTICE = "NOTICE"
    DINING_AREA = "DINING_AREA"
    LODGING_AREA = "LODGING_AREA"


class MemorySourceType(StrEnum):
    TRIP_HISTORY = "TRIP_HISTORY"
    SAVED_PLACE = "SAVED_PLACE"
    BUDGET_PREFERENCE = "BUDGET_PREFERENCE"
    PACE_PREFERENCE = "PACE_PREFERENCE"
    DIETARY_RESTRICTION = "DIETARY_RESTRICTION"
    USER_FEEDBACK = "USER_FEEDBACK"


class RetrievalLayer(StrEnum):
    PUBLIC_KNOWLEDGE = "PUBLIC_KNOWLEDGE"
    USER_MEMORY = "USER_MEMORY"
    GRAPH = "GRAPH"
    REALTIME_TOOL = "REALTIME_TOOL"


class RetrievalIntent(StrEnum):
    LOCAL_NEARBY = "LOCAL_NEARBY"
    REGION_PLANNING = "REGION_PLANNING"
    PERSONALIZED_RECOMMENDATION = "PERSONALIZED_RECOMMENDATION"
    TRANSIT_CONNECTION = "TRANSIT_CONNECTION"
    REALTIME_FACT = "REALTIME_FACT"
    GENERAL_KNOWLEDGE = "GENERAL_KNOWLEDGE"


class RetrievalStrategy(StrEnum):
    VECTOR = "VECTOR"
    KEYWORD = "KEYWORD"
    HYBRID = "HYBRID"
    USER_MEMORY = "USER_MEMORY"
    LOCAL_GRAPH = "LOCAL_GRAPH"
    REGION_GRAPH = "REGION_GRAPH"
    TRANSIT_GRAPH = "TRANSIT_GRAPH"
    CROSS_ENCODER = "CROSS_ENCODER"
    REALTIME_TOOL = "REALTIME_TOOL"


class GraphScope(StrEnum):
    AUTO = "AUTO"
    LOCAL = "LOCAL"
    REGION = "REGION"
    TRANSIT = "TRANSIT"


class GraphNodeType(StrEnum):
    CITY = "CITY"
    REGION = "REGION"
    ATTRACTION = "ATTRACTION"
    RESTAURANT = "RESTAURANT"
    HOTEL = "HOTEL"
    STATION = "STATION"
    AIRPORT = "AIRPORT"
    TRANSIT_LINE = "TRANSIT_LINE"


class GraphRelationshipType(StrEnum):
    LOCATED_IN = "LOCATED_IN"
    NEAR = "NEAR"
    CONNECTED_TO = "CONNECTED_TO"
    OPEN_DURING = "OPEN_DURING"
    SUITABLE_FOR = "SUITABLE_FOR"
    CONFLICTS_WITH = "CONFLICTS_WITH"
    REQUIRES_RESERVATION = "REQUIRES_RESERVATION"


Metadata = dict[str, Any]


class ChunkBase(StrictModel):
    chunkId: str = Field(min_length=1, max_length=200)
    sourceId: str = Field(min_length=1, max_length=300)
    title: str = Field(min_length=1, max_length=300)
    city: str | None = Field(default=None, max_length=100)
    region: str | None = Field(default=None, max_length=100)
    validFrom: date | None = None
    validTo: date | None = None
    language: str = Field(min_length=2, max_length=35)
    content: str = Field(min_length=1, max_length=12000)
    updatedAt: datetime
    metadata: Metadata = Field(default_factory=dict)

    @field_validator("validFrom", "validTo", mode="before")
    @classmethod
    def require_date_input(cls, value: object) -> object:
        if value is not None and not isinstance(value, (str, date)):
            raise ValueError("date values must use ISO date strings")
        return value

    @field_validator("updatedAt", mode="before")
    @classmethod
    def require_datetime_input(cls, value: object) -> object:
        if not isinstance(value, (str, datetime)):
            raise ValueError("updatedAt must use an ISO date-time string")
        return value

    @field_validator("updatedAt")
    @classmethod
    def require_timezone(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("updatedAt must include a UTC offset")
        return value

    @model_validator(mode="after")
    def validate_dates_and_metadata(self) -> Self:
        if self.validFrom and self.validTo and self.validTo < self.validFrom:
            raise ValueError("validTo must not be earlier than validFrom")
        if len(self.metadata) > 50:
            raise ValueError("metadata must not contain more than 50 properties")
        return self


class KnowledgeChunkInput(ChunkBase):
    city: str = Field(min_length=1, max_length=100)
    sourceType: KnowledgeSourceType


class MemoryChunkInput(ChunkBase):
    sourceType: MemorySourceType


class GraphNodeInput(StrictModel):
    nodeId: str = Field(min_length=1, max_length=200)
    type: GraphNodeType
    name: str = Field(min_length=1, max_length=300)
    sourceId: str = Field(min_length=1, max_length=300)
    sourceType: KnowledgeSourceType
    title: str = Field(min_length=1, max_length=300)
    excerpt: str = Field(min_length=1, max_length=1000)
    city: str = Field(min_length=1, max_length=100)
    region: str | None = Field(default=None, max_length=100)
    validFrom: date | None = None
    validTo: date | None = None
    language: str = Field(min_length=2, max_length=35)
    updatedAt: datetime
    metadata: Metadata = Field(default_factory=dict)

    @field_validator("validFrom", "validTo", mode="before")
    @classmethod
    def require_date_input(cls, value: object) -> object:
        if value is not None and not isinstance(value, (str, date)):
            raise ValueError("date values must use ISO date strings")
        return value

    @field_validator("updatedAt", mode="before")
    @classmethod
    def require_datetime_input(cls, value: object) -> object:
        if not isinstance(value, (str, datetime)):
            raise ValueError("updatedAt must use an ISO date-time string")
        return value

    @field_validator("updatedAt")
    @classmethod
    def require_timezone(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("updatedAt must include a UTC offset")
        return value

    @model_validator(mode="after")
    def validate_dates_and_metadata(self) -> Self:
        if self.validFrom and self.validTo and self.validTo < self.validFrom:
            raise ValueError("validTo must not be earlier than validFrom")
        if len(self.metadata) > 50:
            raise ValueError("metadata must not contain more than 50 properties")
        return self


class GraphRelationshipInput(StrictModel):
    fromNodeId: str = Field(min_length=1, max_length=200)
    toNodeId: str = Field(min_length=1, max_length=200)
    type: GraphRelationshipType
    distanceMeters: int | None = Field(default=None, ge=0, le=10_000_000)
    durationMinutes: int | None = Field(default=None, ge=0, le=100_000)
    metadata: Metadata = Field(default_factory=dict)

    @model_validator(mode="after")
    def validate_relationship(self) -> Self:
        if self.fromNodeId == self.toNodeId:
            raise ValueError("graph relationships must connect distinct nodes")
        if len(self.metadata) > 50:
            raise ValueError("metadata must not contain more than 50 properties")
        return self


class GraphProjectionInput(StrictModel):
    nodes: list[GraphNodeInput] = Field(max_length=500)
    relationships: list[GraphRelationshipInput] = Field(
        max_length=1000,
    )

    @model_validator(mode="after")
    def reject_duplicate_nodes(self) -> Self:
        node_ids = [node.nodeId for node in self.nodes]
        if len(node_ids) != len(set(node_ids)):
            raise ValueError("graph nodeId values must be unique")
        known_nodes = set(node_ids)
        unknown_nodes = {
            endpoint
            for relationship in self.relationships
            for endpoint in (relationship.fromNodeId, relationship.toNodeId)
            if endpoint not in known_nodes
        }
        if unknown_nodes:
            raise ValueError(
                "graph relationship endpoints must be included in the projection"
            )
        return self


class KnowledgeIngestRequest(StrictModel):
    chunks: list[KnowledgeChunkInput] = Field(min_length=1, max_length=100)
    graph: GraphProjectionInput | None = None

    @model_validator(mode="after")
    def reject_duplicate_chunks(self) -> Self:
        _require_unique_chunk_ids(self.chunks)
        return self


class MemoryIngestRequest(StrictModel):
    chunks: list[MemoryChunkInput] = Field(min_length=1, max_length=100)

    @model_validator(mode="after")
    def reject_duplicate_chunks(self) -> Self:
        _require_unique_chunk_ids(self.chunks)
        return self


class IngestResponse(StrictModel):
    ingestionId: UUID
    layer: RetrievalLayer
    indexedCount: int = Field(ge=0)
    graphNodesUpserted: int = Field(ge=0)
    graphRelationshipsUpserted: int = Field(ge=0)
    embeddingDimensions: int = Field(ge=1)
    providerMode: ProviderMode


class RetrievalSearchRequest(StrictModel):
    query: str = Field(min_length=1, max_length=2000)
    city: str | None = Field(default=None, max_length=100)
    region: str | None = Field(default=None, max_length=100)
    travelDate: date | None = None
    language: str = Field(default="zh-CN", min_length=2, max_length=35)
    includePublicKnowledge: StrictBool = True
    includeUserMemory: StrictBool = False
    includeGraph: StrictBool = True
    maxResults: int = Field(default=8, ge=1, le=20)

    @field_validator("travelDate", mode="before")
    @classmethod
    def require_date_input(cls, value: object) -> object:
        if value is not None and not isinstance(value, (str, date)):
            raise ValueError("travelDate must use an ISO date string")
        return value


class Evidence(StrictModel):
    chunkId: str | None = Field(default=None, max_length=200)
    sourceId: str = Field(min_length=1, max_length=300)
    sourceType: str = Field(min_length=1, max_length=100)
    title: str = Field(min_length=1, max_length=300)
    excerpt: str = Field(min_length=1, max_length=1000)
    relevanceScore: Decimal = Field(ge=0, le=1, decimal_places=4)
    updatedAt: datetime
    retrievalStrategies: list[RetrievalStrategy] = Field(min_length=1)
    fromRealtimeTool: bool
    layer: RetrievalLayer
    city: str | None = Field(default=None, max_length=100)
    region: str | None = Field(default=None, max_length=100)

    @field_validator("updatedAt")
    @classmethod
    def require_timezone(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("updatedAt must include a UTC offset")
        return value


class RetrievalSearchResponse(StrictModel):
    rewrittenQuery: str = Field(min_length=1, max_length=2000)
    intent: RetrievalIntent
    routes: list[RetrievalLayer] = Field(min_length=1)
    evidence: list[Evidence] = Field(max_length=20)
    evidenceInsufficient: bool
    realtimeToolRequired: bool
    providerMode: ProviderMode
    warnings: list[str] = Field(default_factory=list, max_length=20)


class GraphSearchRequest(StrictModel):
    query: str = Field(min_length=1, max_length=2000)
    scope: GraphScope = GraphScope.AUTO
    anchorEntity: str | None = Field(default=None, max_length=300)
    city: str | None = Field(default=None, max_length=100)
    region: str | None = Field(default=None, max_length=100)
    travelDate: date | None = None
    language: str = Field(default="zh-CN", min_length=2, max_length=35)
    maxResults: int = Field(default=8, ge=1, le=20)

    @field_validator("travelDate", mode="before")
    @classmethod
    def require_date_input(cls, value: object) -> object:
        if value is not None and not isinstance(value, (str, date)):
            raise ValueError("travelDate must use an ISO date string")
        return value


class GraphSearchResponse(StrictModel):
    scope: GraphScope
    evidence: list[Evidence] = Field(max_length=20)
    evidenceInsufficient: bool
    providerMode: ProviderMode


def _require_unique_chunk_ids(chunks: list[ChunkBase]) -> None:
    chunk_ids = [chunk.chunkId for chunk in chunks]
    if len(chunk_ids) != len(set(chunk_ids)):
        raise ValueError("chunkId values must be unique within one request")
