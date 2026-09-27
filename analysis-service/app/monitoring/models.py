from datetime import datetime
from decimal import Decimal
from enum import Enum
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic.alias_generators import to_camel


class Model(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


FiniteDecimal = Annotated[Decimal, Field(allow_inf_nan=False)]


def require_aware(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamps must include a timezone")
    return value


def require_aware_optional(value: datetime | None) -> datetime | None:
    return None if value is None else require_aware(value)


class Cadence(str, Enum):
    INTRADAY = "INTRADAY"
    DAILY = "DAILY"
    WEEKLY = "WEEKLY"
    MONTHLY = "MONTHLY"


class Point(Model):
    value: FiniteDecimal | None
    as_of: datetime
    collected_at: datetime

    _aware = field_validator("as_of", "collected_at")(require_aware)


class MarketSeries(Model):
    metric: str = Field(min_length=1)
    unit: str = Field(min_length=1)
    source: str = Field(min_length=1)
    cadence: Cadence
    points: list[Point]


class ShockCategory(str, Enum):
    INFLATION = "INFLATION"
    OIL = "OIL"
    GROWTH = "GROWTH"
    TREASURY_FISCAL = "TREASURY_FISCAL"
    AI_MEGA_CAP = "AI_MEGA_CAP"
    DXY_USDJPY = "DXY_USDJPY"
    GEOPOLITICAL = "GEOPOLITICAL"
    INSTITUTION = "INSTITUTION"


class Severity(str, Enum):
    WARN = "WARN"
    STRESS = "STRESS"


class Shock(Model):
    category: ShockCategory
    severity: Severity
    evidence: str = Field(min_length=1)
    source: str = Field(min_length=1)
    as_of: datetime

    _aware = field_validator("as_of")(require_aware)


class IncidentKind(str, Enum):
    INSTITUTION = "INSTITUTION"
    FUND = "FUND"
    MARKET_INFRASTRUCTURE = "MARKET_INFRASTRUCTURE"


class IncidentSource(str, Enum):
    SEC = "SEC"
    COMPANY_IR = "COMPANY_IR"
    EXCHANGE = "EXCHANGE"
    GOVERNMENT = "GOVERNMENT"
    SECONDARY = "SECONDARY"


class Incident(Model):
    kind: IncidentKind
    forced_deleveraging: bool
    source_type: IncidentSource
    official: bool
    evidence: str = Field(min_length=1)
    source: str = Field(min_length=1)
    as_of: datetime

    _aware = field_validator("as_of")(require_aware)


class MarketInput(Model):
    series: list[MarketSeries] = Field(default_factory=list)
    shocks: list[Shock] = Field(default_factory=list)
    shock_coverage_complete: bool = False
    incidents: list[Incident] = Field(default_factory=list)


class ThesisCondition(Model):
    add: str | None = None
    reduce: str | None = None
    exit: str | None = None
    invalidation: str | None = None


class InvalidationKind(str, Enum):
    FUNDAMENTAL = "FUNDAMENTAL"
    REGULATORY = "REGULATORY"
    COMPETITIVE = "COMPETITIVE"
    MANAGEMENT = "MANAGEMENT"
    PRICE = "PRICE"


class InvalidationEvidence(Model):
    kind: InvalidationKind
    condition_match: bool = False
    evidence: str = Field(min_length=1)
    source: str = Field(min_length=1)
    as_of: datetime

    _aware = field_validator("as_of")(require_aware)


class Position(Model):
    symbol: str = Field(min_length=1, max_length=32)
    quantity: FiniteDecimal | None = None
    avg_cost: FiniteDecimal | None = None
    market_value: FiniteDecimal | None = None
    weight: FiniteDecimal | None = Field(default=None, ge=0)
    sector: str | None = None
    factor: str | None = None
    beta: FiniteDecimal | None = None
    correlation: FiniteDecimal | None = None
    thesis: str | None = None
    primary_alpha: str | None = None
    conditions: ThesisCondition = Field(default_factory=ThesisCondition)
    invalidation_evidence: list[InvalidationEvidence] = Field(default_factory=list)


class RiskPolicy(Model):
    max_concentration: FiniteDecimal | None = Field(default=None, gt=0, le=1)
    max_sector_weight: FiniteDecimal | None = Field(default=None, gt=0, le=1)
    max_factor_weight: FiniteDecimal | None = Field(default=None, gt=0, le=1)


class PortfolioInput(Model):
    as_of: datetime | None = None
    positions: list[Position] = Field(default_factory=list)
    risk_policy: RiskPolicy = Field(default_factory=RiskPolicy)

    _aware = field_validator("as_of")(require_aware_optional)

    @model_validator(mode="after")
    def positions_need_timestamp(self):
        if self.positions and self.as_of is None:
            raise ValueError("portfolio.asOf is required when positions are included")
        return self


class EventKind(str, Enum):
    FILING = "FILING"
    GUIDANCE = "GUIDANCE"
    EARNINGS = "EARNINGS"
    DILUTION = "DILUTION"
    CONTRACT = "CONTRACT"
    MNA = "MNA"
    REGULATION = "REGULATION"
    OFFICER = "OFFICER"
    RATING = "RATING"
    DEFAULT = "DEFAULT"
    PROJECT_DELAY = "PROJECT_DELAY"


class EventSource(str, Enum):
    SEC = "SEC"
    COMPANY_IR = "COMPANY_IR"
    EXCHANGE = "EXCHANGE"
    GOVERNMENT = "GOVERNMENT"
    SECONDARY = "SECONDARY"


class MaterialEvent(Model):
    source_event_id: str = Field(min_length=1)
    symbol: str = Field(min_length=1, max_length=32)
    kind: EventKind
    source_type: EventSource
    source_url: str | None = None
    title: str = Field(min_length=1)
    evidence: str = Field(min_length=1)
    published_at: datetime
    collected_at: datetime
    official: bool

    _aware = field_validator("published_at", "collected_at")(require_aware)


class PriceRange(Model):
    min: FiniteDecimal
    max: FiniteDecimal

    @model_validator(mode="after")
    def ordered(self):
        if self.min > self.max:
            raise ValueError("range min must be <= max")
        return self


class WatchlistLevels(Model):
    prepare: PriceRange
    confirm: PriceRange
    pullback: PriceRange
    invalidate: PriceRange


class WatchState(str, Enum):
    WATCH = "WATCH"
    PREPARE = "PREPARE"
    ACTION_CANDIDATE = "ACTION_CANDIDATE"
    INVALIDATED = "INVALIDATED"


class WatchlistItem(Model):
    symbol: str = Field(min_length=1, max_length=32)
    status: WatchState
    levels: WatchlistLevels
    price: FiniteDecimal | None = None
    volume_multiple: FiniteDecimal | None = Field(default=None, ge=0)
    relative_strength: FiniteDecimal | None = None
    source: str | None = None
    as_of: datetime | None = None
    collected_at: datetime | None = None

    _aware = field_validator("as_of", "collected_at")(require_aware_optional)


class MonitoringRequest(Model):
    request_id: UUID
    schema_version: Literal["1"]
    as_of: datetime
    market: MarketInput
    portfolio: PortfolioInput
    events: list[MaterialEvent] = Field(default_factory=list)
    watchlist: list[WatchlistItem] = Field(default_factory=list)

    _aware = field_validator("as_of")(require_aware)
