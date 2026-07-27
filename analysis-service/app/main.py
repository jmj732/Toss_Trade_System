from datetime import datetime
from decimal import Decimal, ROUND_HALF_EVEN
from enum import Enum
from typing import Annotated, Literal
from uuid import UUID

from fastapi import FastAPI
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


RATIO_SCALE = Decimal("0.0000000001")
Money = Annotated[Decimal, Field(ge=0, allow_inf_nan=False)]
SignedMoney = Annotated[Decimal, Field(allow_inf_nan=False)]


class ContractModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class Currency(str, Enum):
    KRW = "KRW"
    USD = "USD"


class Quality(ContractModel):
    stale: bool
    partial: bool
    unknown_fields: list[str]


class PositionInput(ContractModel):
    symbol: Annotated[str, Field(min_length=1, max_length=30)]
    currency: Currency
    market_value: Money
    profit_loss: SignedMoney | None


class PortfolioAnalysisRequest(ContractModel):
    request_id: UUID
    schema_version: Literal["1"]
    as_of: datetime
    quality: Quality
    positions: list[PositionInput]


class PositionAnalysis(PositionInput):
    weight: Decimal


class CurrencyTotal(ContractModel):
    currency: Currency
    market_value: Decimal
    profit_loss: Decimal | None
    concentration: Decimal


class Status(str, Enum):
    COMPLETED = "COMPLETED"
    DEGRADED = "DEGRADED"


class PortfolioAnalysisResponse(ContractModel):
    request_id: UUID
    schema_version: Literal["1"]
    as_of: datetime
    status: Status
    quality: Quality
    positions: list[PositionAnalysis]
    currency_totals: list[CurrencyTotal]


app = FastAPI(title="Portfolio Analysis Service", version="1")


@app.get("/internal/v1/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.post(
    "/internal/v1/portfolio-analyses",
    response_model=PortfolioAnalysisResponse,
)
def analyze_portfolio(request: PortfolioAnalysisRequest) -> PortfolioAnalysisResponse:
    totals = {
        currency: sum(
            (position.market_value for position in request.positions if position.currency == currency),
            Decimal(0),
        )
        for currency in sorted({position.currency for position in request.positions}, key=lambda item: item.value)
    }
    positions = [
        PositionAnalysis(
            **position.model_dump(),
            weight=ratio(position.market_value, totals[position.currency]),
        )
        for position in request.positions
    ]
    currency_totals = [
        CurrencyTotal(
            currency=currency,
            market_value=market_value,
            profit_loss=profit_loss(request.positions, currency),
            concentration=max(
                (position.weight for position in positions if position.currency == currency),
                default=Decimal(0),
            ),
        )
        for currency, market_value in totals.items()
    ]
    degraded = (
        request.quality.stale
        or request.quality.partial
        or bool(request.quality.unknown_fields)
    )
    return PortfolioAnalysisResponse(
        request_id=request.request_id,
        schema_version=request.schema_version,
        as_of=request.as_of,
        status=Status.DEGRADED if degraded else Status.COMPLETED,
        quality=request.quality,
        positions=positions,
        currency_totals=currency_totals,
    )


def ratio(value: Decimal, total: Decimal) -> Decimal:
    if total == 0:
        return Decimal(0)
    return (value / total).quantize(RATIO_SCALE, rounding=ROUND_HALF_EVEN)


def profit_loss(positions: list[PositionInput], currency: Currency) -> Decimal | None:
    values = [
        position.profit_loss
        for position in positions
        if position.currency == currency
    ]
    if any(value is None for value in values):
        return None
    return sum(values, Decimal(0))
