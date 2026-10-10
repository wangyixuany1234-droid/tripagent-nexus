import hashlib
import secrets
from typing import Annotated
from uuid import UUID

import structlog
from fastapi import APIRouter, Depends, File, Form, Header, Request, UploadFile

from trip_agent_ai.core.config import Settings
from trip_agent_ai.core.errors import AppError
from trip_agent_ai.core.security import get_runtime_settings, require_service_auth
from trip_agent_ai.ocr.parser import DocumentParser
from trip_agent_ai.schemas.booking import BookingExtractionResult

router = APIRouter(
    prefix="/internal/v1/documents",
    tags=["internal-documents"],
    dependencies=[Depends(require_service_auth)],
)
logger = structlog.get_logger(__name__)
Traceparent = Annotated[
    str | None,
    Header(alias="traceparent", max_length=128),
]


@router.post(
    "/parse",
    response_model=BookingExtractionResult,
    operation_id="parseDocumentInternal",
)
async def parse_document(
    request: Request,
    document_id: Annotated[UUID, Form(alias="documentId")],
    task_id: Annotated[UUID, Form(alias="taskId")],
    media_type: Annotated[str, Form(alias="mediaType")],
    sha256: Annotated[str, Form(pattern=r"^[0-9a-f]{64}$")],
    file: Annotated[UploadFile, File()],
    settings: Annotated[Settings, Depends(get_runtime_settings)],
    locale: Annotated[str, Form(max_length=35)] = "zh-CN",
    traceparent: Traceparent = None,
) -> BookingExtractionResult:
    content = await _read_bounded(file, settings.upload_max_size_bytes)
    actual_hash = hashlib.sha256(content).hexdigest()
    if not secrets.compare_digest(actual_hash, sha256):
        raise AppError(
            code="DOCUMENT_HASH_MISMATCH",
            title="Document integrity check failed",
            status=422,
            detail="The document content does not match its declared SHA-256.",
        )
    if file.content_type and file.content_type.lower() != media_type.lower():
        raise AppError(
            code="UPLOAD_MIME_MISMATCH",
            title="Document rejected",
            status=415,
            detail="Multipart and declared media types do not match.",
        )

    parser: DocumentParser = request.app.state.document_parser
    result = await parser.parse(
        file.filename or "document",
        media_type,
        content,
        locale,
    )
    logger.info(
        "document_parse_completed",
        documentId=str(document_id),
        taskId=str(task_id),
        parserMode=result.parserMode,
        bookingCount=len(result.bookings),
    )
    return result


async def _read_bounded(file: UploadFile, max_size: int) -> bytes:
    chunks: list[bytes] = []
    total = 0
    try:
        while chunk := await file.read(64 * 1024):
            total += len(chunk)
            if total > max_size:
                raise AppError(
                    code="UPLOAD_SIZE_INVALID",
                    title="Document rejected",
                    status=413,
                    detail="The document exceeds the configured size limit.",
                )
            chunks.append(chunk)
        return b"".join(chunks)
    finally:
        await file.close()
