from typing import Annotated

from fastapi import APIRouter, Depends, Request

from trip_agent_ai.application_health import HealthService
from trip_agent_ai.core.security import require_service_auth
from trip_agent_ai.schemas.health import HealthResponse

router = APIRouter(
    prefix="/internal/v1",
    tags=["Internal Health"],
    dependencies=[Depends(require_service_auth)],
)


def get_health_service(request: Request) -> HealthService:
    return request.app.state.health_service


@router.get(
    "/health",
    response_model=HealthResponse,
    operation_id="getInternalHealth",
)
async def get_health(
    service: Annotated[HealthService, Depends(get_health_service)],
) -> HealthResponse:
    return await service.check()
