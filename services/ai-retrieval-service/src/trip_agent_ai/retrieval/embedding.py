import hashlib
import math
import re
from typing import Protocol

import httpx
from opentelemetry import trace
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from trip_agent_ai.core.config import Settings
from trip_agent_ai.core.errors import AppError
from trip_agent_ai.schemas.retrieval import ProviderMode

TOKEN_PATTERN = re.compile(r"[\u3400-\u9fff]|[a-z0-9]+", re.IGNORECASE)
tracer = trace.get_tracer(__name__)


class EmbeddingProvider(Protocol):
    mode: ProviderMode
    dimensions: int

    async def embed(self, texts: list[str]) -> list[list[float]]: ...


class _EmbeddingItem(BaseModel):
    model_config = ConfigDict(extra="ignore")

    index: int = Field(ge=0)
    embedding: list[float]


class _EmbeddingResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    data: list[_EmbeddingItem]
    usage: dict[str, int] = Field(default_factory=dict)


class DeepseekEmbeddingProvider:
    mode = ProviderMode.DEEPSEEK

    def __init__(self, settings: Settings) -> None:
        self.dimensions = settings.retrieval_embedding_dimensions
        self._api_key = settings.deepseek_api_key
        self._base_url = settings.deepseek_base_url.rstrip("/")
        self._model = settings.deepseek_embedding_model
        self._timeout = settings.retrieval_timeout_seconds

    async def embed(self, texts: list[str]) -> list[list[float]]:
        if self._api_key is None or not self._api_key.get_secret_value():
            raise _provider_error(
                "MODEL_CREDENTIAL_MISSING",
                "DEEPSEEK_API_KEY is required for production retrieval.",
                503,
            )
        try:
            with tracer.start_as_current_span("model.embedding") as span:
                span.set_attribute("gen_ai.operation.name", "embeddings")
                span.set_attribute("gen_ai.provider.name", "deepseek")
                span.set_attribute("gen_ai.request.model", self._model)
                span.set_attribute("gen_ai.prompt.version", "embedding-v1.0.0")
                span.set_attribute("gen_ai.request.count", len(texts))
                async with httpx.AsyncClient(timeout=self._timeout) as client:
                    response = await client.post(
                        f"{self._base_url}/embeddings",
                        headers={
                            "Authorization": (
                                f"Bearer {self._api_key.get_secret_value()}"
                            ),
                            "Content-Type": "application/json",
                        },
                        json={
                            "model": self._model,
                            "input": texts,
                            "dimensions": self.dimensions,
                        },
                    )
                    response.raise_for_status()
                    parsed = _EmbeddingResponse.model_validate(response.json())
                span.set_attribute(
                    "gen_ai.usage.input_tokens",
                    parsed.usage.get("prompt_tokens", 0),
                )
                span.set_attribute(
                    "gen_ai.usage.total_tokens",
                    parsed.usage.get("total_tokens", 0),
                )
                span.set_attribute("operation.success", True)
        except (httpx.HTTPError, ValidationError, ValueError) as exc:
            raise _provider_error(
                "EMBEDDING_PROVIDER_FAILED",
                "The embedding provider did not return a valid response.",
                502,
            ) from exc

        ordered = sorted(parsed.data, key=lambda item: item.index)
        vectors = [item.embedding for item in ordered]
        if len(vectors) != len(texts) or any(
            len(vector) != self.dimensions for vector in vectors
        ):
            raise _provider_error(
                "EMBEDDING_DIMENSION_MISMATCH",
                "The embedding provider returned an unexpected vector shape.",
                502,
            )
        return [_normalize(vector) for vector in vectors]


class MockEmbeddingProvider:
    mode = ProviderMode.MOCK

    def __init__(self, dimensions: int) -> None:
        self.dimensions = dimensions

    async def embed(self, texts: list[str]) -> list[list[float]]:
        return [_hash_embedding(text, self.dimensions) for text in texts]


def create_embedding_provider(settings: Settings) -> EmbeddingProvider:
    if settings.retrieval_provider_mode == "mock":
        return MockEmbeddingProvider(settings.retrieval_embedding_dimensions)
    return DeepseekEmbeddingProvider(settings)


def _hash_embedding(text: str, dimensions: int) -> list[float]:
    vector = [0.0] * dimensions
    tokens = _tokens(text)
    if not tokens:
        tokens = [text.strip().lower() or "_"]
    for token in tokens:
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        index = int.from_bytes(digest[:4], "big") % dimensions
        sign = 1.0 if digest[4] & 1 else -1.0
        vector[index] += sign
    return _normalize(vector)


def _tokens(text: str) -> list[str]:
    normalized = text.lower()
    base = TOKEN_PATTERN.findall(normalized)
    compact_cjk = "".join(token for token in base if len(token) == 1)
    ngrams = [
        compact_cjk[index : index + size]
        for size in (2, 3)
        for index in range(max(0, len(compact_cjk) - size + 1))
    ]
    return base + ngrams


def _normalize(vector: list[float]) -> list[float]:
    magnitude = math.sqrt(sum(value * value for value in vector))
    if magnitude == 0:
        return vector
    return [value / magnitude for value in vector]


def _provider_error(code: str, detail: str, status: int) -> AppError:
    return AppError(
        code=code,
        title="Retrieval model provider failed",
        status=status,
        detail=detail,
        retryable=status >= 500,
    )
