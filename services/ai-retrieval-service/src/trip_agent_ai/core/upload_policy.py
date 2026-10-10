from pathlib import Path

from trip_agent_ai.core.config import Settings
from trip_agent_ai.core.errors import AppError


class UploadPolicy:
    def __init__(self, settings: Settings) -> None:
        self._max_size_bytes = settings.upload_max_size_bytes
        self._allowed_mime_types = {
            value.strip().lower() for value in settings.upload_allowed_mime_types
        }
        self._allowed_extensions = {
            self._normalize_extension(value)
            for value in settings.upload_allowed_extensions
        }

    def validate(self, filename: str, media_type: str, size_bytes: int) -> None:
        if size_bytes <= 0 or size_bytes > self._max_size_bytes:
            raise self._violation("UPLOAD_SIZE_INVALID")
        if media_type.strip().lower() not in self._allowed_mime_types:
            raise self._violation("UPLOAD_MIME_TYPE_DENIED")
        if Path(filename).suffix.lower() not in self._allowed_extensions:
            raise self._violation("UPLOAD_EXTENSION_DENIED")

    @staticmethod
    def _normalize_extension(value: str) -> str:
        normalized = value.strip().lower()
        return normalized if normalized.startswith(".") else f".{normalized}"

    @staticmethod
    def _violation(code: str) -> AppError:
        return AppError(
            code=code,
            title="Upload rejected",
            status=422,
            detail="The upload does not satisfy the configured policy.",
        )
