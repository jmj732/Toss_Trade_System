from datetime import timedelta
from decimal import Decimal

from .models import PortfolioInput


CORE_FIELDS = (
    "quantity", "avgCost", "marketValue", "weight", "sector", "factor",
    "beta", "correlation", "thesis", "primaryAlpha",
)
CONDITION_FIELDS = ("add", "reduce", "exit", "invalidation")
MAX_SNAPSHOT_AGE = timedelta(minutes=30)


def evaluate_portfolio(portfolio: PortfolioInput, as_of, market_state: str, state_evidence: list[dict]) -> dict:
    snapshot_fresh = (
        portfolio.as_of is not None
        and timedelta(0) <= as_of - portfolio.as_of <= MAX_SNAPSHOT_AGE
    )
    symbol_weights = {}
    sector_weights = {}
    factor_weights = {}
    missing_groups = {"symbol": set(), "sector": set(), "factor": set()}
    for position in portfolio.positions:
        symbol_weights.setdefault(position.symbol, Decimal(0))
        if position.weight is None:
            missing_groups["symbol"].add(position.symbol)
        else:
            symbol_weights[position.symbol] += position.weight
        if position.sector:
            sector_weights.setdefault(position.sector, Decimal(0))
            if position.weight is None:
                missing_groups["sector"].add(position.sector)
            else:
                sector_weights[position.sector] += position.weight
        if position.factor:
            factor_weights.setdefault(position.factor, Decimal(0))
            if position.weight is None:
                missing_groups["factor"].add(position.factor)
            else:
                factor_weights[position.factor] += position.weight

    symbol_weights = {key: None if key in missing_groups["symbol"] else value for key, value in symbol_weights.items()}
    sector_weights = {key: None if key in missing_groups["sector"] else value for key, value in sector_weights.items()}
    factor_weights = {key: None if key in missing_groups["factor"] else value for key, value in factor_weights.items()}

    policy_breaches = []
    concentration = portfolio.risk_policy.max_concentration
    if snapshot_fresh and concentration is not None:
        for symbol, weight in symbol_weights.items():
            if weight is not None and weight > concentration:
                policy_breaches.append({
                    "policy": "maxConcentration", "targetType": "SYMBOL", "target": symbol,
                    "value": weight, "limit": concentration,
                    "source": "RISK_POLICY_AND_PORTFOLIO_SNAPSHOT", "asOf": portfolio.as_of,
                })
    for field, cap, exposures, kind in (
        ("maxSectorWeight", portfolio.risk_policy.max_sector_weight, sector_weights, "SECTOR"),
        ("maxFactorWeight", portfolio.risk_policy.max_factor_weight, factor_weights, "FACTOR"),
    ):
        if snapshot_fresh and cap is not None:
            for name, weight in exposures.items():
                if weight is not None and weight > cap:
                    policy_breaches.append({
                        "policy": field, "targetType": kind, "target": name,
                        "value": weight, "limit": cap,
                        "source": "RISK_POLICY_AND_PORTFOLIO_SNAPSHOT", "asOf": portfolio.as_of,
                    })

    positions = []
    for position in portfolio.positions:
        unknown = []
        data = position.model_dump(by_alias=True)
        for field in CORE_FIELDS:
            if data[field] is None or data[field] == "":
                unknown.append(field)
        for field in CONDITION_FIELDS:
            if getattr(position.conditions, field) is None:
                unknown.append(f"conditions.{field}")
        if not snapshot_fresh:
            unknown.append("portfolio.asOf")
        if concentration is None:
            unknown.append("riskPolicy.maxConcentration")
        for field, cap in (("riskPolicy.maxSectorWeight", portfolio.risk_policy.max_sector_weight),
                           ("riskPolicy.maxFactorWeight", portfolio.risk_policy.max_factor_weight)):
            if cap is None:
                unknown.append(field)

        breaches = [
            breach for breach in policy_breaches
            if (breach["targetType"] == "SYMBOL" and breach["target"] == position.symbol)
            or (breach["targetType"] == "SECTOR" and breach["target"] == position.sector)
            or (breach["targetType"] == "FACTOR" and breach["target"] == position.factor)
        ]
        invalidations = [
            item for item in position.invalidation_evidence
            if item.condition_match and item.kind.value != "PRICE" and position.conditions.invalidation
        ]
        evidence = list(state_evidence) if market_state != "NORMAL" else []
        evidence.extend({
            "type": "THESIS_INVALIDATION", "kind": item.kind.value,
            "evidence": item.evidence, "source": item.source, "asOf": item.as_of,
        } for item in invalidations)
        evidence.extend({
            "type": "POLICY_BREACH", "policy": item["policy"], "target": item["target"],
            "value": item["value"], "limit": item["limit"],
            "source": item["source"], "asOf": item["asOf"],
        } for item in breaches)

        if market_state == "P0_SYSTEMIC":
            state = "P0"
        elif invalidations:
            state = "THESIS_INVALIDATED"
        elif breaches:
            state = "REDUCE_CANDIDATE"
        elif market_state in {"EARLY_WARNING", "RISK_TRANSITION", "STRONG_RISK_OFF"}:
            state = "ATTENTION"
        else:
            state = "NORMAL"
        required_unknown = {"quantity", "avgCost", "marketValue", "weight", "portfolio.asOf", "riskPolicy.maxConcentration"}
        if not unknown:
            data_quality = "COMPLETE"
        elif required_unknown.intersection(unknown):
            data_quality = "INSUFFICIENT_DATA"
        else:
            data_quality = "PARTIAL"
        positions.append({
            "symbol": position.symbol,
            "state": state,
            "dataQuality": data_quality,
            "policyBreaches": breaches,
            "combinedExposure": {
                "symbolWeight": symbol_weights.get(position.symbol),
                "sectorWeight": sector_weights.get(position.sector) if position.sector else None,
                "factorWeight": factor_weights.get(position.factor) if position.factor else None,
            },
            "evidence": evidence,
            "unknownFields": sorted(set(unknown)),
        })
    if not positions:
        data_quality = "COMPLETE" if snapshot_fresh else "INSUFFICIENT_DATA"
    elif all(position["dataQuality"] == "INSUFFICIENT_DATA" for position in positions):
        data_quality = "INSUFFICIENT_DATA"
    elif any(position["dataQuality"] != "COMPLETE" for position in positions):
        data_quality = "PARTIAL"
    else:
        data_quality = "COMPLETE"
    return {
        "positions": positions,
        "policyBreaches": policy_breaches,
        "dataQuality": data_quality,
        "unknownFields": (["riskPolicy.maxConcentration"] if concentration is None else [])
        + (["portfolio.asOf"] if portfolio.positions and not snapshot_fresh else []),
        "asOf": portfolio.as_of,
    }
