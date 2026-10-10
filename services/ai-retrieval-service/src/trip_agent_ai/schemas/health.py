from datetime import datetime
from enum import StrEnum

from pydantic import BaseModel, ConfigDict


class HealthStatus(StrEnum):
    UP = "UP"
    DEGRADED = "DEGRADED"


class DependencyStatus(StrEnum):
    UP = "UP"
    DOWN = "DOWN"
    DEGRADED = "DEGRADED"


class DependencyHealth(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str
    status: DependencyStatus


class HealthResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    status: HealthStatus
    service: str = "ai-retrieval-service"
    version: str
    time: datetime
    dependencies: list[DependencyHealth]
