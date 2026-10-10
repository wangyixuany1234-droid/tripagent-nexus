from functools import lru_cache
from pathlib import Path
from typing import Annotated, Literal, Self
from urllib.parse import urlparse

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict

CsvTuple = Annotated[tuple[str, ...], NoDecode]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    app_env: str = "development"
    ai_retrieval_service_port: int = Field(default=8000, ge=1, le=65535)
    ai_service_token: SecretStr

    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-chat"
    deepseek_embedding_model: str = "deepseek-embedding"
    deepseek_api_key: SecretStr | None = None
    allowed_external_hosts: CsvTuple = (
        "localhost",
        "127.0.0.1",
        "api.deepseek.com",
    )

    neo4j_uri: str = "bolt://localhost:7687"
    neo4j_username: str = "neo4j"
    neo4j_password: SecretStr
    neo4j_database: str = "neo4j"

    lancedb_uri: Path = Path("./data/lancedb")
    lancedb_public_table: str = Field(
        default="travel_public",
        pattern=r"^[a-z][a-z0-9_]{1,62}$",
    )
    lancedb_user_memory_table: str = Field(
        default="user_memory",
        pattern=r"^[a-z][a-z0-9_]{1,62}$",
    )

    retrieval_provider_mode: Literal["deepseek", "mock"] = "deepseek"
    retrieval_embedding_dimensions: int = Field(default=1024, ge=16, le=4096)
    retrieval_timeout_seconds: float = Field(default=30.0, gt=0, le=120)
    retrieval_max_candidates: int = Field(default=40, ge=10, le=200)
    retrieval_min_relevance_score: float = Field(default=0.12, ge=0, le=1)
    retrieval_context_max_chars: int = Field(default=6000, ge=1000, le=30000)
    graph_store_mode: Literal["neo4j", "memory"] = "neo4j"

    upload_max_size_bytes: int = Field(default=10 * 1024 * 1024, gt=0)
    upload_allowed_mime_types: CsvTuple = (
        "image/jpeg",
        "image/png",
        "image/webp",
        "application/pdf",
    )
    upload_allowed_extensions: CsvTuple = (
        ".jpg",
        ".jpeg",
        ".png",
        ".webp",
        ".pdf",
    )
    upload_max_pixels: int = Field(default=40_000_000, gt=0)
    upload_max_pdf_pages: int = Field(default=20, gt=0, le=100)

    document_parser_mode: Literal["deepseek", "mock"] = "deepseek"
    document_parser_version: str = "document-parser-1.0.0"
    document_parser_timeout_seconds: float = Field(default=60.0, gt=0, le=300)
    tesseract_command: str = "tesseract"
    pdfinfo_command: str = "pdfinfo"
    pdftoppm_command: str = "pdftoppm"

    otel_enabled: bool = True
    otel_service_name: str = "ai-retrieval-service"
    otel_exporter_otlp_endpoint: str = "http://localhost:4318"
    otel_traces_sampler_arg: float = Field(default=1.0, ge=0.0, le=1.0)
    log_level: str = "INFO"

    @field_validator(
        "upload_allowed_mime_types",
        "upload_allowed_extensions",
        "allowed_external_hosts",
        mode="before",
    )
    @classmethod
    def parse_csv_tuple(cls, value: object) -> object:
        if isinstance(value, str):
            return tuple(part.strip() for part in value.split(",") if part.strip())
        return value

    @model_validator(mode="after")
    def validate_external_domains(self) -> Self:
        if (
            self.retrieval_provider_mode == "deepseek"
            or self.document_parser_mode == "deepseek"
        ):
            parsed = urlparse(self.deepseek_base_url)
            host = (parsed.hostname or "").lower()
            local = host in {"localhost", "127.0.0.1"}
            insecure = parsed.scheme != "https" and not (
                local and parsed.scheme == "http"
            )
            if (
                host not in {item.lower() for item in self.allowed_external_hosts}
                or insecure
            ):
                raise ValueError("EXTERNAL_TOOL_DOMAIN_DENIED")
        if self.app_env.casefold() in {"prod", "production"}:
            forbidden = {
                "tripagent-dev-service-token",
                "tripagent_neo4j_dev",
                "test-service-token",
            }
            configured = {
                self.ai_service_token.get_secret_value(),
                self.neo4j_password.get_secret_value(),
            }
            if configured & forbidden:
                raise ValueError("PRODUCTION_CREDENTIAL_INVALID")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
