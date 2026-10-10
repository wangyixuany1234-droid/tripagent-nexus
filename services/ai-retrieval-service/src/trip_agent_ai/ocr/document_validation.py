import re
import struct
from dataclasses import dataclass
from pathlib import Path

from trip_agent_ai.core.config import Settings
from trip_agent_ai.core.errors import AppError

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
PDF_PAGE_PATTERN = re.compile(rb"/Type\s*/Page(?!s)\b")
PDF_HEADER_PATTERN = re.compile(rb"^%PDF-1\.[0-7](?:\r\n|\r|\n)")
PDF_XREF_PATTERN = re.compile(rb"(?:^|[\r\n])xref(?:[\r\n])")
MIME_EXTENSIONS = {
    "image/jpeg": {".jpg", ".jpeg"},
    "image/png": {".png"},
    "image/webp": {".webp"},
    "application/pdf": {".pdf"},
}


@dataclass(frozen=True, slots=True)
class DocumentInspection:
    media_type: str
    page_count: int


class DocumentValidator:
    def __init__(self, settings: Settings) -> None:
        self._max_size = settings.upload_max_size_bytes
        self._max_pixels = settings.upload_max_pixels
        self._max_pdf_pages = settings.upload_max_pdf_pages

    def inspect(
        self,
        filename: str,
        claimed_media_type: str,
        content: bytes,
    ) -> DocumentInspection:
        if not content or len(content) > self._max_size:
            raise _violation("UPLOAD_SIZE_INVALID", 413)
        if _is_executable(content):
            raise _violation("UPLOAD_EXECUTABLE_DENIED", 415)

        media_type, dimensions, page_count = _detect(content)
        extension = Path(filename).suffix.lower()
        if media_type not in MIME_EXTENSIONS:
            raise _violation("UPLOAD_MIME_TYPE_DENIED", 415)
        if (
            claimed_media_type.lower() != media_type
            or extension not in MIME_EXTENSIONS[media_type]
        ):
            raise _violation("UPLOAD_MIME_MISMATCH", 415)

        if page_count > self._max_pdf_pages:
            raise _violation("UPLOAD_PAGE_LIMIT_EXCEEDED", 422)
        if dimensions is not None:
            width, height = dimensions
            if width <= 0 or height <= 0 or width * height > self._max_pixels:
                raise _violation("UPLOAD_PIXEL_LIMIT_EXCEEDED", 422)
        return DocumentInspection(media_type=media_type, page_count=page_count)


def _detect(content: bytes) -> tuple[str, tuple[int, int] | None, int]:
    if content.startswith(PNG_SIGNATURE) and len(content) >= 24:
        return "image/png", struct.unpack(">II", content[16:24]), 1
    if content.startswith(b"\xff\xd8\xff"):
        return "image/jpeg", _jpeg_dimensions(content), 1
    if len(content) >= 30 and content.startswith(b"RIFF") and content[8:12] == b"WEBP":
        return "image/webp", _webp_dimensions(content), 1
    if content.startswith(b"%PDF-"):
        _validate_pdf_structure(content)
        page_count = len(PDF_PAGE_PATTERN.findall(content))
        return "application/pdf", None, page_count
    raise _violation("UPLOAD_FILE_MALFORMED", 422)


def _validate_pdf_structure(content: bytes) -> None:
    eof = content.rfind(b"%%EOF")
    if (
        PDF_HEADER_PATTERN.match(content) is None
        or eof < 0
        or len(content) - (eof + len(b"%%EOF")) > 1024
        or b"startxref" not in content
        or len(PDF_PAGE_PATTERN.findall(content)) == 0
        or (PDF_XREF_PATTERN.search(content) is None and b"/Type /XRef" not in content)
    ):
        raise _violation("UPLOAD_FILE_MALFORMED", 422)


def _jpeg_dimensions(content: bytes) -> tuple[int, int]:
    offset = 2
    while offset + 9 < len(content):
        if content[offset] != 0xFF:
            offset += 1
            continue
        marker = content[offset + 1]
        offset += 2
        if marker in {0xD8, 0xD9}:
            continue
        if offset + 2 > len(content):
            break
        segment_length = int.from_bytes(content[offset : offset + 2], "big")
        if segment_length < 2 or offset + segment_length > len(content):
            break
        if marker in {
            0xC0,
            0xC1,
            0xC2,
            0xC3,
            0xC5,
            0xC6,
            0xC7,
            0xC9,
            0xCA,
            0xCB,
            0xCD,
            0xCE,
            0xCF,
        }:
            height = int.from_bytes(content[offset + 3 : offset + 5], "big")
            width = int.from_bytes(content[offset + 5 : offset + 7], "big")
            return width, height
        offset += segment_length
    raise _violation("UPLOAD_FILE_MALFORMED", 422)


def _webp_dimensions(content: bytes) -> tuple[int, int]:
    chunk_type = content[12:16]
    if chunk_type == b"VP8X" and len(content) >= 30:
        width = 1 + int.from_bytes(content[24:27], "little")
        height = 1 + int.from_bytes(content[27:30], "little")
        return width, height
    if chunk_type == b"VP8L" and len(content) >= 25:
        bits = int.from_bytes(content[21:25], "little")
        return (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1
    if chunk_type == b"VP8 " and len(content) >= 30:
        return (
            int.from_bytes(content[26:28], "little") & 0x3FFF,
            int.from_bytes(content[28:30], "little") & 0x3FFF,
        )
    raise _violation("UPLOAD_FILE_MALFORMED", 422)


def _is_executable(content: bytes) -> bool:
    return content.startswith(
        (
            b"MZ",
            b"\x7fELF",
            b"\xca\xfe\xba\xbe",
            b"\xcf\xfa\xed\xfe",
            b"\xfe\xed\xfa\xcf",
        )
    )


def _violation(code: str, status: int) -> AppError:
    return AppError(
        code=code,
        title="Document rejected",
        status=status,
        detail="The document does not satisfy the configured safety policy.",
    )
