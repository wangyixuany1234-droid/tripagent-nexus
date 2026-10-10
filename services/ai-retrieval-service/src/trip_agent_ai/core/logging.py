import logging
import re
from collections.abc import Mapping
from typing import Any

import structlog

REDACTED = "***"
SENSITIVE_KEYS = {
    "authorization",
    "cookie",
    "setcookie",
    "xapikey",
    "apikey",
    "arkapikey",
    "password",
    "token",
    "ocrtext",
    "rawevidence",
    "textevidence",
    "imagedata",
    "content",
    "excerpt",
    "query",
    "metadata",
    "usermemory",
}
BEARER_PATTERN = re.compile(
    r"(?i)(authorization\s*[:=]\s*bearer\s+)[A-Za-z0-9._~+/=-]+"
)
SECRET_VALUE_PATTERN = re.compile(
    r"(?i)((?:api[_-]?key|ark_api_key|cookie|password|token)\s*[:=]\s*)"
    r"[^\s,;}]+"
)


def _normalized_key(value: object) -> str:
    return re.sub(r"[^a-z0-9]", "", str(value).lower())


def _redact(value: Any, key: object | None = None) -> Any:
    if key is not None and _normalized_key(key) in SENSITIVE_KEYS:
        return REDACTED
    if isinstance(value, Mapping):
        return {item_key: _redact(item, item_key) for item_key, item in value.items()}
    if isinstance(value, list):
        return [_redact(item) for item in value]
    if isinstance(value, tuple):
        return tuple(_redact(item) for item in value)
    if isinstance(value, str):
        without_bearer = BEARER_PATTERN.sub(r"\1" + REDACTED, value)
        return SECRET_VALUE_PATTERN.sub(r"\1" + REDACTED, without_bearer)
    return value


def redact_sensitive_data(
    _logger: object,
    _method_name: str,
    event_dict: dict[str, Any],
) -> dict[str, Any]:
    return _redact(event_dict)


def configure_logging(level: str) -> None:
    logging.basicConfig(
        format="%(message)s",
        level=getattr(logging, level.upper(), logging.INFO),
    )
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.stdlib.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            structlog.processors.StackInfoRenderer(),
            structlog.processors.format_exc_info,
            redact_sensitive_data,
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(
            getattr(logging, level.upper(), logging.INFO)
        ),
        logger_factory=structlog.stdlib.LoggerFactory(),
        cache_logger_on_first_use=True,
    )
