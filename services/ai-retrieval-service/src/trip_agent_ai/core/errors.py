from dataclasses import dataclass, field
from datetime import UTC, datetime

import structlog
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from trip_agent_ai.observability.tracing import current_trace_id
from trip_agent_ai.schemas.problem import FieldError, ProblemDetails

logger = structlog.get_logger(__name__)
PROBLEM_BASE = "https://tripagent.example/problems/"


@dataclass(slots=True)
class AppError(Exception):
    code: str
    title: str
    status: int
    detail: str
    retryable: bool = False
    errors: list[FieldError] = field(default_factory=list)


def _response(
    request: Request,
    error: AppError,
    headers: dict[str, str] | None = None,
) -> JSONResponse:
    body = ProblemDetails(
        type=PROBLEM_BASE + error.code.lower().replace("_", "-"),
        title=error.title,
        status=error.status,
        detail=error.detail,
        instance=request.url.path,
        code=error.code,
        message=error.detail,
        traceId=current_trace_id(),
        timestamp=datetime.now(UTC),
        retryable=error.retryable,
        errors=error.errors,
    )
    return JSONResponse(
        status_code=error.status,
        content=body.model_dump(mode="json"),
        headers=headers,
        media_type="application/problem+json",
    )


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(AppError)
    async def handle_app_error(request: Request, exception: AppError) -> JSONResponse:
        return _response(request, exception)

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(
        request: Request,
        exception: RequestValidationError,
    ) -> JSONResponse:
        errors = [
            FieldError(
                field="/" + "/".join(str(part) for part in issue["loc"]),
                code="FIELD_INVALID",
                message=issue["msg"],
            )
            for issue in exception.errors()
        ]
        return _response(
            request,
            AppError(
                code="VALIDATION_FAILED",
                title="Request validation failed",
                status=422,
                detail="One or more fields are invalid.",
                errors=errors,
            ),
        )

    @app.exception_handler(StarletteHTTPException)
    async def handle_http_error(
        request: Request,
        exception: StarletteHTTPException,
    ) -> JSONResponse:
        errors = {
            400: (
                "REQUEST_INVALID",
                "Request body invalid",
                "The request body could not be parsed.",
            ),
            404: (
                "RESOURCE_NOT_FOUND",
                "Resource not found",
                "The requested resource was not found.",
            ),
            405: (
                "METHOD_NOT_ALLOWED",
                "Method not allowed",
                "The HTTP method is not supported for this resource.",
            ),
        }
        code, title, detail = errors.get(
            exception.status_code,
            (
                "REQUEST_REJECTED",
                "Request rejected",
                "The request could not be accepted.",
            ),
        )
        return _response(
            request,
            AppError(
                code=code,
                title=title,
                status=exception.status_code,
                detail=detail,
            ),
            headers=exception.headers,
        )

    @app.exception_handler(Exception)
    async def handle_unexpected_error(
        request: Request,
        exception: Exception,
    ) -> JSONResponse:
        logger.exception(
            "unhandled_request_failure",
            path=request.url.path,
            exceptionType=type(exception).__name__,
        )
        return _response(
            request,
            AppError(
                code="INTERNAL_ERROR",
                title="Internal server error",
                status=500,
                detail="The request could not be completed.",
            ),
        )
