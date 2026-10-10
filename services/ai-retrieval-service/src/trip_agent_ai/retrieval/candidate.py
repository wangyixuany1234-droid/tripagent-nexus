from dataclasses import dataclass, field
from datetime import datetime

from trip_agent_ai.schemas.retrieval import RetrievalLayer, RetrievalStrategy


@dataclass(slots=True)
class RetrievalCandidate:
    chunk_id: str | None
    source_id: str
    source_type: str
    title: str
    excerpt: str
    updated_at: datetime
    layer: RetrievalLayer
    city: str | None
    region: str | None
    score: float
    strategies: set[RetrievalStrategy] = field(default_factory=set)
    from_realtime_tool: bool = False

    @property
    def provenance_key(self) -> tuple[RetrievalLayer, str, str | None]:
        return (self.layer, self.source_id, self.chunk_id)
