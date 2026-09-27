from .event_monitor import evaluate_events
from .market_risk import evaluate_market
from .models import MonitoringRequest
from .portfolio_risk import evaluate_portfolio
from .watchlist import evaluate_watchlist


def evaluate(request: MonitoringRequest) -> dict:
    market = evaluate_market(request.market, request.as_of)
    return {
        "requestId": request.request_id,
        "schemaVersion": request.schema_version,
        "asOf": request.as_of,
        "market": market,
        "portfolio": evaluate_portfolio(
            request.portfolio,
            request.as_of,
            market["state"],
            market["stateEvidence"],
        ),
        "events": evaluate_events(request.events, request.as_of),
        "watchlist": evaluate_watchlist(request.watchlist, request.as_of),
    }
