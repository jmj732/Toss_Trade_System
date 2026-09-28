import json

from fastapi import APIRouter, Response

from .evaluation import evaluate
from .models import MonitoringRequest


router = APIRouter()


@router.post("/internal/v1/monitoring/evaluations")
def monitoring_evaluation(request: MonitoringRequest) -> Response:
    body = evaluate(request)
    return Response(
        content=json.dumps(body, default=str),
        media_type="application/json",
    )
