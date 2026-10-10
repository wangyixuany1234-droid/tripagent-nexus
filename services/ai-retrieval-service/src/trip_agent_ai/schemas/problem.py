from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class FieldError(BaseModel):
    model_config = ConfigDict(extra="forbid")

    field: str | None = None
    code: str
    message: str


class ProblemDetails(BaseModel):
    model_config = ConfigDict(extra="forbid")

    type: str
    title: str
    status: int = Field(ge=400, le=599)
    detail: str | None = None
    instance: str | None = None
    code: str
    message: str
    traceId: str = Field(pattern=r"^[0-9a-f]{32}$")
    timestamp: datetime
    retryable: bool
    errors: list[FieldError] = Field(default_factory=list)
