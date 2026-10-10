from typing import Annotated

from fastapi import APIRouter, Depends, Request

from trip_agent_ai.core.security import require_service_auth
from trip_agent_ai.observability.runtime_metrics import RuntimeMetricsService
from trip_agent_ai.schemas.runtime import RuntimeMetricsResponse

router = APIRouter(
    prefix="/internal/v1",
    tags=["Internal Observability"],
    dependencies=[Depends(require_service_auth)],
)


def get_runtime_metrics_service(request: Request) -> RuntimeMetricsService:
    return request.app.state.runtime_metrics_service


@router.get(
    "/runtime-metrics",
    response_model=RuntimeMetricsResponse,
    operation_id="getRuntimeMetrics",
)
async def get_runtime_metrics(
    service: Annotated[
        RuntimeMetricsService,
        Depends(get_runtime_metrics_service),
    ],
) -> RuntimeMetricsResponse:
    return service.snapshot()
