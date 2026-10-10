from datetime import datetime
from decimal import Decimal
from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class BookingType(StrEnum):
    FLIGHT = "FLIGHT"
    HOTEL = "HOTEL"
    TICKET = "TICKET"
    TRAIN = "TRAIN"
    OTHER = "OTHER"


class EvidenceRegion(BaseModel):
    model_config = ConfigDict(extra="forbid")

    page: int = Field(ge=1)
    x: Decimal = Field(ge=0, le=1)
    y: Decimal = Field(ge=0, le=1)
    width: Decimal = Field(gt=0, le=1)
    height: Decimal = Field(gt=0, le=1)


class FieldEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid")

    field: str = Field(min_length=1, max_length=100)
    confidence: Decimal = Field(ge=0, le=1, decimal_places=4)
    textEvidence: str | None = Field(default=None, max_length=1000)
    region: EvidenceRegion | None = None

    @model_validator(mode="after")
    def require_text_or_region(self) -> "FieldEvidence":
        if not self.textEvidence and self.region is None:
            raise ValueError("textEvidence or region is required")
        return self


class ExtractedBooking(BaseModel):
    model_config = ConfigDict(extra="forbid")

    type: BookingType
    provider: str | None = Field(default=None, max_length=200)
    origin: str | None = Field(default=None, max_length=300)
    destination: str | None = Field(default=None, max_length=300)
    hotelName: str | None = Field(default=None, max_length=300)
    hotelAddress: str | None = Field(default=None, max_length=500)
    transportNumber: str | None = Field(default=None, max_length=100)
    confirmationNumber: str | None = Field(default=None, max_length=200)
    startTime: datetime | None = None
    endTime: datetime | None = None
    travelerCount: int | None = Field(default=None, ge=1, le=100)
    amount: Decimal | None = Field(default=None, ge=0, decimal_places=4)
    currency: str | None = Field(default=None, pattern=r"^[A-Z]{3}$")
    confidence: Decimal = Field(ge=0, le=1, decimal_places=4)
    missingFields: list[str] = Field(default_factory=list, max_length=20)
    fieldEvidence: list[FieldEvidence] = Field(default_factory=list, max_length=100)

    @field_validator("startTime", "endTime")
    @classmethod
    def require_timezone(cls, value: datetime | None) -> datetime | None:
        if value is not None and value.tzinfo is None:
            raise ValueError("timestamps must include an offset")
        return value


class BookingExtractionResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    bookings: list[ExtractedBooking] = Field(min_length=1, max_length=100)
    warnings: list[str] = Field(max_length=100)
    confidence: Decimal = Field(ge=0, le=1, decimal_places=4)
    parserMode: str = Field(pattern=r"^(DEEPSEEK|MOCK)$")
    parserVersion: str = Field(min_length=1, max_length=100)
    ocrEngine: str = Field(min_length=1, max_length=100)
