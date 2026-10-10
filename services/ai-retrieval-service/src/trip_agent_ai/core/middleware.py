import json
import re
import time
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime
from uuid import uuid4

import structlog
from fastapi import FastAPI, Request, Response
from fastapi.responses import JSONResponse
from structlog.contextvars import bind_contextvars, clear_contextvars

from trip_agent_ai.observability.tracing import current_trace_id

REQUEST_ID_HEADER = "X-Request-Id"
TRACE_ID_HEADER = "X-Trace-Id"
SAFE_REQUEST_ID = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
logger = structlog.get_logger(__name__)


class DuplicateJsonKeyError(ValueError):
    pass


def install_request_context_middleware(app: FastAPI) -> None:
    @app.middleware("http")
    async def request_context(
        request: Request,
        call_next: Callable[[Request], Awaitable[Response]],
    ) -> Response:
        started_at = time.perf_counter()
        supplied_request_id = request.headers.get(REQUEST_ID_HEADER, "")
        request_id = (
            supplied_request_id
            if SAFE_REQUEST_ID.fullmatch(supplied_request_id)
            else str(uuid4())
        )
        trace_id = current_trace_id()
        clear_contextvars()
        bind_contextvars(requestId=request_id, traceId=trace_id)

        try:
            duplicate_key = await _duplicate_json_key(request)
            if duplicate_key is not None:
                response = JSONResponse(
                    status_code=400,
                    media_type="application/problem+json",
                    content={
                        "type": "https://tripagent.example/problems/request-invalid",
                        "title": "Request body invalid",
                        "status": 400,
                        "detail": "The request body could not be parsed.",
                        "instance": request.url.path,
                        "code": "REQUEST_INVALID",
                        "message": "The request body could not be parsed.",
                        "traceId": trace_id,
                        "timestamp": datetime.now(UTC).isoformat(),
                        "retryable": False,
                        "errors": [],
                    },
                )
            else:
                response = await call_next(request)
        except Exception:
            await logger.aexception(
                "request_failed",
                method=request.method,
                path=request.url.path,
                durationMs=round((time.perf_counter() - started_at) * 1000, 2),
            )
            clear_contextvars()
            raise

        response.headers[REQUEST_ID_HEADER] = request_id
        response.headers[TRACE_ID_HEADER] = trace_id
        await logger.ainfo(
            "request_completed",
            method=request.method,
            path=request.url.path,
            statusCode=response.status_code,
            durationMs=round((time.perf_counter() - started_at) * 1000, 2),
        )
        clear_contextvars()
        return response


async def _duplicate_json_key(request: Request) -> str | None:
    content_type = request.headers.get("content-type", "").split(";", 1)[0].strip()
    if content_type != "application/json":
        return None
    body = await request.body()
    if not body:
        return None
    try:
        json.loads(body, object_pairs_hook=_unique_object)
    except DuplicateJsonKeyError as exception:
        return str(exception)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return None
    return None


def _unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    value: dict[str, object] = {}
    for key, item in pairs:
        if key in value:
            raise DuplicateJsonKeyError(key)
        value[key] = item
    return value
