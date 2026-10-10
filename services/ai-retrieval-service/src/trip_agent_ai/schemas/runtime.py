from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class RuntimeMetricsResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    timestamp: datetime
    processRssBytes: int = Field(ge=0)
    threadCount: int = Field(ge=0)
    eventLoopLagMs: float = Field(ge=0)
    eventLoopLagP95Ms: float = Field(ge=0)
    eventLoopLagMaxMs: float = Field(ge=0)
    eventLoopSamples: int = Field(ge=0)
