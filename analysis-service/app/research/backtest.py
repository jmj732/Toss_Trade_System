"""Open-fill backtest engine.

Timing: a decision made at close t is executed at open t+1. Between decisions the
book is held and weights drift with prices. For realization day k (open k, close k):

    g_i        = h_i * open_k / close_{k-1}            (value fraction at open)
    nav_open   = 1 + sum_i (g_i - h_i)                 (overnight move, multiplicative)
    w_open_i   = g_i / nav_open                        (drifted pre-trade weights)
    w_new_i    = decision target, or w_open_i when no trade is due
    turnover   = sum_i |w_new_i - w_open_i|
    cost       = cost_rate * turnover                  (cost_rate = (bps_commission + bps_slippage)/1e4)
    intraday   = sum_i w_new_i * (close_k / open_k - 1)
    r_k        = nav_open * (1 - cost) * (1 + intraday) - 1
    h_i        = w_new_i * close_k / open_k / (1 + intraday)

Targets are fractions of NAV net of trading costs, so cash is (1 - sum w)/(1 + intraday) >= 0
and a 100% target never borrows to pay costs. A single asset held with no trades
compounds to exactly close_T / open_0 (buy-and-hold identity). Entering at 100%
from cash gives close_T / open_0 * (1 - cost). The spec's additive form
Σw_old(o/c-1) + Σw_new(c/o-1) - cost*turnover agrees to first order; it differs only
by cross terms of the same size as the overnight move times intraday/cost.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date

from app.research.data import Bar, ResearchError

TRADE_EPSILON = 1e-12


@dataclass(frozen=True)
class Trade:
    date: date
    symbol: str
    from_weight: float
    to_weight: float


@dataclass(frozen=True)
class BacktestResult:
    """Path of one simulation. ``returns[k-1]`` is the return realized on ``dates[k]``."""

    dates: tuple[date, ...]
    returns: tuple[float, ...]
    equity: tuple[float, ...]
    weights: tuple[Mapping[str, float], ...]
    trades: tuple[Trade, ...]
    turnover: tuple[float, ...]
    held_gross: tuple[float, ...]
    cost_rate: float

    def window(self, start: int, end: int) -> tuple[tuple[date, ...], tuple[float, ...]]:
        """Realized dates and returns for realization indices in [start, end)."""
        lo = max(start, 1)
        hi = min(end, len(self.dates))
        if hi <= lo:
            return (), ()
        return self.dates[lo:hi], self.returns[lo - 1 : hi - 1]

    def window_returns(self, start: int, end: int) -> list[float]:
        return list(self.window(start, end)[1])


def _check_decisions(decisions: Mapping[int, Mapping[str, float]], n_dates: int, symbols: set[str]) -> None:
    for t, target in decisions.items():
        if not 0 <= t <= n_dates - 2:
            raise ResearchError(f"decision index {t} has no execution day")
        for symbol, weight in target.items():
            if symbol not in symbols:
                raise ResearchError(f"decision references unknown symbol {symbol}")
            if not 0.0 <= weight <= 1.0:
                raise ResearchError(f"weight {weight} outside [0, 1]")
        if sum(target.values()) > 1.0 + 1e-12:
            raise ResearchError(f"decision at {t} sums above 1")


def simulate(
    bars: Mapping[str, Sequence[Bar]],
    dates: Sequence[date],
    decisions: Mapping[int, Mapping[str, float]],
    cost_rate: float,
) -> BacktestResult:
    """Simulate the book given decisions keyed by decision index. Missing keys mean hold."""
    if cost_rate < 0:
        raise ResearchError("cost_rate must be >= 0")
    symbols = sorted(bars)
    _check_decisions(decisions, len(dates), set(symbols))
    holdings = {symbol: 0.0 for symbol in symbols}
    equity = [1.0]
    returns: list[float] = []
    weights_path: list[Mapping[str, float]] = [dict(holdings)]
    turnover_path: list[float] = []
    held_gross: list[float] = []
    trades: list[Trade] = []
    for k in range(1, len(dates)):
        prev = {symbol: bars[symbol][k - 1] for symbol in symbols}
        cur = {symbol: bars[symbol][k] for symbol in symbols}
        grown = {symbol: holdings[symbol] * cur[symbol].open / prev[symbol].close for symbol in symbols}
        nav_open = 1.0 + sum(grown[symbol] - holdings[symbol] for symbol in symbols)
        if nav_open <= 0.0:
            raise ResearchError(f"book wiped out on {dates[k]}")
        drifted = {symbol: grown[symbol] / nav_open for symbol in symbols}
        target = decisions.get(k - 1)
        new = drifted if target is None else {symbol: float(target.get(symbol, 0.0)) for symbol in symbols}
        turnover = sum(abs(new[symbol] - drifted[symbol]) for symbol in symbols)
        intraday = sum(new[symbol] * (cur[symbol].close / cur[symbol].open - 1.0) for symbol in symbols)
        cost_factor = 1.0 - cost_rate * turnover
        if cost_factor <= 0.0 or 1.0 + intraday <= 0.0:
            raise ResearchError(f"book wiped out on {dates[k]}")
        realized = nav_open * cost_factor * (1.0 + intraday) - 1.0
        holdings = {
            symbol: new[symbol] * cur[symbol].close / cur[symbol].open / (1.0 + intraday)
            for symbol in symbols
        }
        for symbol in symbols:
            if abs(new[symbol] - drifted[symbol]) > TRADE_EPSILON:
                trades.append(Trade(dates[k], symbol, drifted[symbol], new[symbol]))
        returns.append(realized)
        equity.append(equity[-1] * (1.0 + realized))
        weights_path.append(dict(holdings))
        turnover_path.append(turnover)
        held_gross.append(sum(drifted.values()))
    return BacktestResult(
        dates=tuple(dates),
        returns=tuple(returns),
        equity=tuple(equity),
        weights=tuple(weights_path),
        trades=tuple(trades),
        turnover=tuple(turnover_path),
        held_gross=tuple(held_gross),
        cost_rate=cost_rate,
    )


def cost_rate_from_bps(commission_bps: float, slippage_bps: float) -> float:
    """Per unit traded notional. Commission is a configurable assumption; see report bias controls."""
    if commission_bps < 0 or slippage_bps < 0:
        raise ResearchError("costs must be non-negative")
    return (commission_bps + slippage_bps) / 10_000.0


def restrict_decisions(
    decisions: Mapping[int, Mapping[str, float]], start: int, end: int
) -> dict[int, Mapping[str, float]]:
    """Decisions whose execution day (t+1) lies in realization range [start, end)."""
    return {t: target for t, target in decisions.items() if start <= t + 1 < end}


def mean_gross_exposure(result: BacktestResult, start: int, end: int) -> float | None:
    """Average gross held weight at the open over realization days in [start, end)."""
    lo = max(start, 1)
    hi = min(end, len(result.dates))
    values = result.held_gross[lo - 1 : hi - 1]
    if not values:
        return None
    return sum(values) / len(values)
