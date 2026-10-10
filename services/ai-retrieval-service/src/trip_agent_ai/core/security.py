import secrets
from typing import Annotated

from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from trip_agent_ai.core.config import Settings
from trip_agent_ai.core.errors import AppError

bearer_scheme = HTTPBearer(auto_error=False)


def get_runtime_settings(request: Request) -> Settings:
    return request.app.state.settings


def require_service_auth(
    credentials: Annotated[
        HTTPAuthorizationCredentials | None,
        Depends(bearer_scheme),
    ],
    settings: Annotated[Settings, Depends(get_runtime_settings)],
) -> None:
    expected = settings.ai_service_token.get_secret_value()
    supplied = credentials.credentials if credentials is not None else ""
    scheme = credentials.scheme if credentials is not None else ""

    if (
        not expected
        or scheme.lower() != "bearer"
        or not secrets.compare_digest(supplied, expected)
    ):
        raise AppError(
            code="SERVICE_AUTHENTICATION_FAILED",
            title="Service authentication failed",
            status=401,
            detail="A valid service identity token is required.",
        )
