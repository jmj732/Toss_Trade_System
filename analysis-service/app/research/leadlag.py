"""LL-1 lead-lag experiment, implemented as pre-registered in claudedocs/leadlag-preregistration.md.

Sections implemented: 3 (data inputs), 4 (model, portfolio, trial count), 5 (G1, G2, diagnostics),
6 (stop rule text). Section 7 (forward confirmation) is not computed here.

Research only. Nothing here places orders or touches broker code.

Model M0 per stock i (OLS, fitted per walk-forward fold, train 756 / test 252 / step 252 bars):
    y_i(t) = a + b1 r_SPY(t) + b2 r_sector(t) + b3 r_i(t) + e          target y(t) = open(t+2)/open(t+1) - 1
M1 adds c * r_leader(s, t), where the leader of sector s is the stock with the highest mean
raw close x raw volume over the 63 bars ending at t (point-in-time). On days when stock i is its own
sector's leader, M1 equals M0 and the M1 training rows exclude that day.

Portfolio: each day, long-only, top-k stocks with forecast > 0.30 % (round-trip cost), weight 1/k,
executed at the next open through backtest.simulate. Costs 10 bp commission + 5 bp slippage, 2x stress.

Simulation: each walk-forward fold is simulated separately with backtest.simulate on its own
calendar window [test_start-1, test_end), using that fold's universe and SPY, starting from cash.
Fold returns are concatenated (the folds tile the OOS range). Each fold's entry from cash costs the
full target turnover, and the overnight move from a fold's last close to the next fold's first open
is not captured. Both effects are documented in the report. The walk-forward start is placed after
the first complete bar of SPY and all seven sector ETFs.

Universe rules (``--universe-rule``):

- ``test_end_complete`` (default, original LL-1). A stock is a member when its own, sector ETF and SPY
  bars are complete over [train_start-1, test_end). Membership therefore reads bars inside the test
  window, i.e. it depends on future data availability. The committed report is produced with this rule.
- ``point_in_time`` (data-handling correction; same 58 trials, not new trials). Membership reads only
  [train_start-1, test_start), which is known at fold start. Inside the test window:

  1. Valuation. A missing bar in the simulation is a flat bar: open = high = low = close = the last
     observed close, volume 0. A return across a data gap is therefore realized on the next real bar.
     Flat bars exist only in the simulation bar lookup. They never enter ``Panel``, so forecasts,
     leaders and the Clark-West tests see only real bars.
  2. Forecast. A stock whose own bar on day t is missing gets no forecast on t (already true through
     the features).
  3. Unfilled orders. After ranking, a pick at t whose execution bar (open t+1) is missing is removed
     from the top-k list. Its 1/k slot stays cash and no pick is back-filled from rank k+1. This is
     the only use of day t+1 availability. It approximates an unfilled order, not a fill model.
  4. Exits. Targets are full daily targets. A held stock that is not re-selected is sold at the next
     open. Rule 3 keeps a stock with a missing open from being bought or held into the gap, so a
     stock on a gap day is sold at that open. If that open is a flat bar, the sale happens at the last
     close. Holding across a gap (return realized on the next real bar) is exercised only by unit tests.

  Per fold, the report records the flat bars valued and the unfilled orders per trial.
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any

from app.research import validation
from app.research.backtest import cost_rate_from_bps, simulate
from app.research.data import (
    REQUIRED_INTERVAL,
    Bar,
    Dataset,
    ResearchError,
    _load_symbol,
    canonical_json,
    load_dataset,
    sha256_hex,
)
from app.research.metrics import (
    annualize_sharpe,
    annualized_volatility,
    cagr,
    deflated_sharpe,
    drawdown_profile,
    equity_curve,
    probabilistic_sharpe,
    sharpe_per_period,
)

PREREG_ID = "LL-1"
PREREG_COMMIT = "84fe065"
PREREG_PATH = "claudedocs/leadlag-preregistration.md"
GENERATED_BY = "app.research.leadlag 1.0.0"
LABEL = "LL-1 리드-래그 과거 탐색 구간 판정 (walk-forward OOS, 가설적 결과, 주문 없음)"
TOTAL_TRIALS = 58
PRIOR_TRIAL_COUNT = 54
NEW_TRIAL_COUNT = 4
PRIMARY_K = 5
TOP_K_VALUES = (3, 5)
THRESHOLD = 0.0030
LEADER_WINDOW = 63
NW_LAG = 5
ALPHA = 0.05
COMMISSION_BPS = 10.0
SLIPPAGE_BPS = 5.0
BENCHMARK = "SPY"
SECTOR_STOCKS: dict[str, tuple[str, ...]] = {
    "XLK": ("AAPL", "MSFT", "NVDA", "ORCL", "CSCO"),
    "XLF": ("JPM", "BAC", "WFC", "C", "GS"),
    "XLE": ("XOM", "CVX", "COP", "SLB", "OXY"),
    "XLV": ("JNJ", "PFE", "MRK", "ABT", "AMGN"),
    "XLP": ("PG", "KO", "PEP", "WMT", "CL"),
    "XLI": ("CAT", "GE", "HON", "BA", "DE"),
    "XLY": ("AMZN", "HD", "MCD", "NKE", "LOW"),
}
SECTOR_ETFS = tuple(SECTOR_STOCKS)
STOCKS = tuple(sorted(symbol for stocks in SECTOR_STOCKS.values() for symbol in stocks))
REQUIRED_SYMBOLS = tuple(sorted((BENCHMARK,) + SECTOR_ETFS + STOCKS))
SECTOR_OF = {stock: etf for etf, stocks in SECTOR_STOCKS.items() for stock in stocks}
REQUIRED_BASIS = validation.REQUIRED_ADJUSTMENT_BASIS
MODELS = ("M0", "M1")
RULE_TEST_END_COMPLETE = "test_end_complete"
RULE_POINT_IN_TIME = "point_in_time"
UNIVERSE_RULES = (RULE_TEST_END_COMPLETE, RULE_POINT_IN_TIME)
TRIAL_GRID = tuple((model, k) for model in MODELS for k in TOP_K_VALUES)
OUTCOME_G1_FAILED = "G1_FAILED"
OUTCOME_G1_PASSED_G2_FAILED = "G1_PASSED_G2_FAILED"
OUTCOME_PASSED = "G1_G2_PASSED"
OUTCOME_INSUFFICIENT = "INSUFFICIENT_DATA"
CLASS_SIGNAL_NO_EXECUTION = "신호 있음·체결 불가"
CLASS_SIGNAL_COST_SHORTFALL = "신호 있음·비용 미달"
CLASS_NONE = "사전 분류 해당 없음"
SECTION6_TEXT = (
    "G1 통과, G2 실패: 원인을 분류한다. 비용·체결 문제이면 M2/M3도 같은 체결 제약을 받으므로 확장을 권하지 않는다. "
    "신호 강도 문제이면 M2(고객-공급사 데이터, 무료 소스 확보 가능성 먼저 확인)만 별도 등록(LL-2)으로 제안한다."
)
RECOMMENDATION = {
    OUTCOME_G1_FAILED: (
        "G1 실패: M1 개선 없음. 연구를 중단한다. M2(경제적 관계)와 M3(공시·뉴스)는 구현하지 않는다."
    ),
    OUTCOME_G1_PASSED_G2_FAILED: (
        "G1 통과, G2 실패: 원인 분류 규칙은 사전 등록에 없다. 아래 실패 기준과 진단 수치를 근거로 사람이 "
        "판단한다. " + SECTION6_TEXT
    ),
    OUTCOME_PASSED: (
        "G1·G2 통과: 전진 확증 후보로 등록하고 모의운용(기록만)을 한다. M2/M3는 전진 결과 이후에 판단한다."
    ),
    OUTCOME_INSUFFICIENT: "표본 부족(사전 등록 판정 전 최소 표본 조건 미충족). 판정하지 않는다.",
}


class LeadLagError(ResearchError):
    """Inputs cannot support the pre-registered experiment."""


# ---------------------------------------------------------------- statistics


def _normal_sf(z: float) -> float:
    return 0.5 * math.erfc(z / math.sqrt(2.0))


def newey_west_mean_test(values: Sequence[float], lag: int = NW_LAG) -> dict[str, Any] | None:
    """Test mean > 0 with Newey-West (Bartlett) variance.

    gamma_j = (1/T) sum_{t=j}^{T-1} (x_t - m)(x_{t-j} - m); V = gamma_0 + 2 sum_{j=1}^{L} (1 - j/(L+1)) gamma_j;
    se = sqrt(V / T). One-sided p = 1 - Phi(t).
    """
    n = len(values)
    if n < lag + 2:
        return None
    mean = statistics.fmean(values)
    centered = [value - mean for value in values]

    def gamma(j: int) -> float:
        return sum(centered[t] * centered[t - j] for t in range(j, n)) / n

    variance = gamma(0) + 2.0 * sum((1.0 - j / (lag + 1)) * gamma(j) for j in range(1, lag + 1))
    if not variance > 0.0:
        return None
    se = math.sqrt(variance / n)
    t_stat = mean / se
    return {"n": n, "mean": mean, "nw_standard_error": se, "t": t_stat,
            "p_one_sided": _normal_sf(t_stat), "lag": lag}


def clark_west_loss(y: float, forecast_restricted: float, forecast_full: float) -> float:
    """Clark-West (2007) adjusted loss differential: e0^2 - (e1^2 - (yhat1 - yhat0)^2)."""
    e0 = y - forecast_restricted
    e1 = y - forecast_full
    return e0 * e0 - (e1 * e1 - (forecast_full - forecast_restricted) ** 2)


def _solve(matrix: list[list[float]], vector: list[float]) -> list[float] | None:
    """Gaussian elimination with partial pivoting. None when (numerically) singular."""
    size = len(vector)
    rows = [row[:] + [vector[i]] for i, row in enumerate(matrix)]
    scale = max((abs(value) for row in matrix for value in row), default=0.0)
    if scale == 0.0:
        return None
    for col in range(size):
        pivot = max(range(col, size), key=lambda r: abs(rows[r][col]))
        if abs(rows[pivot][col]) <= 1e-12 * scale:
            return None
        rows[col], rows[pivot] = rows[pivot], rows[col]
        for r in range(col + 1, size):
            factor = rows[r][col] / rows[col][col]
            for c in range(col, size + 1):
                rows[r][c] -= factor * rows[col][c]
    solution = [0.0] * size
    for r in range(size - 1, -1, -1):
        solution[r] = (rows[r][size] - sum(rows[r][c] * solution[c] for c in range(r + 1, size))) / rows[r][r]
    return solution


def ols(design: Sequence[Sequence[float]], target: Sequence[float]) -> list[float] | None:
    """Coefficients from the normal equations. None when the design is singular or too short."""
    if not design:
        return None
    width = len(design[0])
    if len(design) <= width:
        return None
    xtx = [[0.0] * width for _ in range(width)]
    xty = [0.0] * width
    for row, y in zip(design, target):
        for i in range(width):
            xty[i] += row[i] * y
            for j in range(width):
                xtx[i][j] += row[i] * row[j]
    return _solve(xtx, xty)


def _dot(a: Sequence[float], b: Sequence[float]) -> float:
    return sum(x * y for x, y in zip(a, b))


# ---------------------------------------------------------------- panel


@dataclass
class Panel:
    """Series on one calendar (the benchmark's trading dates). None marks a missing bar."""

    dates: tuple[date, ...]
    open: dict[str, list[float | None]]
    close: dict[str, list[float | None]]
    dollar_volume: dict[str, list[float | None]]
    bars: dict[str, dict[date, Bar]]
    off_calendar: dict[str, int] = field(default_factory=dict)

    def coverage_complete(self, symbol: str, lo: int, hi: int) -> bool:
        if symbol not in self.open:
            return False
        return all(self.open[symbol][i] is not None and self.close[symbol][i] is not None
                   for i in range(lo, hi))


def build_panel(
    adjusted: Mapping[str, Sequence[Bar]],
    raw: Mapping[str, Sequence[Bar]] | None = None,
    *,
    calendar: Sequence[date] | None = None,
) -> Panel:
    """Adjusted bars give returns and targets. Raw close x raw volume gives the leader ranking.

    Bars dated off the calendar are not used and are counted per symbol. Missing bars stay None.
    Without a calendar, the union of all dates is used.
    """
    raw = raw or {}
    if calendar is None:
        calendar = sorted({bar.date for bars in adjusted.values() for bar in bars})
    dates = tuple(calendar)
    index = {day: i for i, day in enumerate(dates)}
    opens: dict[str, list[float | None]] = {}
    closes: dict[str, list[float | None]] = {}
    dollar: dict[str, list[float | None]] = {}
    lookup: dict[str, dict[date, Bar]] = {}
    off_calendar: dict[str, int] = {}
    for symbol, bars in adjusted.items():
        o: list[float | None] = [None] * len(dates)
        c: list[float | None] = [None] * len(dates)
        table: dict[date, Bar] = {}
        off = 0
        for bar in bars:
            table[bar.date] = bar
            i = index.get(bar.date)
            if i is None:
                off += 1
                continue
            o[i] = bar.open
            c[i] = bar.close
        opens[symbol] = o
        closes[symbol] = c
        lookup[symbol] = table
        off_calendar[symbol] = off
        d: list[float | None] = [None] * len(dates)
        raw_bars = raw.get(symbol)
        if raw_bars is not None:
            if {bar.date for bar in raw_bars} != {bar.date for bar in bars}:
                raise LeadLagError(f"{symbol}: raw and adjusted dates differ")
            for bar in raw_bars:
                i = index.get(bar.date)
                if i is not None:
                    d[i] = bar.close * bar.volume
        dollar[symbol] = d
    return Panel(dates, opens, closes, dollar, lookup, off_calendar)


def _returns(prices: Sequence[float | None]) -> list[float | None]:
    out: list[float | None] = [None] * len(prices)
    for i in range(1, len(prices)):
        if prices[i] is not None and prices[i - 1] is not None:
            out[i] = prices[i] / prices[i - 1] - 1.0  # type: ignore[operator]
    return out


def leader_table(panel: Panel) -> dict[str, list[tuple[str, float | None] | None]]:
    """Per sector, per calendar index: (leader symbol, leader return on that day) or None.

    The leader is the candidate with the highest mean raw dollar volume over the LEADER_WINDOW bars
    ending at t. A candidate needs a complete window and a bar on day t. Ties go to the smaller symbol.
    Only bars at or before t are read.
    """
    table: dict[str, list[tuple[str, float | None] | None]] = {}
    n = len(panel.dates)
    for etf, stocks in SECTOR_STOCKS.items():
        rows: list[tuple[str, float | None] | None] = [None] * n
        series = {}
        for stock in stocks:
            if stock not in panel.dollar_volume:
                continue
            values = panel.dollar_volume[stock]
            prefix = [0.0] * (n + 1)
            missing = [0] * (n + 1)
            for i in range(n):
                v = values[i]
                prefix[i + 1] = prefix[i] + (v if v is not None else 0.0)
                missing[i + 1] = missing[i] + (1 if v is None else 0)
            series[stock] = (prefix, missing, _returns(panel.close.get(stock, [None] * n)))
        for t in range(n):
            best: tuple[float, str] | None = None
            for stock, (prefix, missing, _) in series.items():
                lo = t - LEADER_WINDOW + 1
                if lo < 0 or missing[t + 1] - missing[lo] != 0:
                    continue
                if panel.close[stock][t] is None:
                    continue
                mean = (prefix[t + 1] - prefix[lo]) / LEADER_WINDOW
                candidate = (-mean, stock)
                if best is None or candidate < best:
                    best = candidate
            if best is None:
                continue
            leader = best[1]
            rows[t] = (leader, series[leader][2][t])
        table[etf] = rows
    return table


# ---------------------------------------------------------------- folds


@dataclass(frozen=True)
class Fold:
    index: int
    train_start: int
    train_end: int
    test_start: int
    test_end: int


def tile_folds(calendar_length: int, first_train_start: int = 1) -> list[Fold]:
    """Walk-forward folds: train TRAIN_BARS, test TEST_BARS, step STEP_BARS. No holdout."""
    folds: list[Fold] = []
    start = first_train_start
    while start + validation.TRAIN_BARS + validation.TEST_BARS <= calendar_length:
        folds.append(Fold(
            index=len(folds),
            train_start=start,
            train_end=start + validation.TRAIN_BARS,
            test_start=start + validation.TRAIN_BARS,
            test_end=start + validation.TRAIN_BARS + validation.TEST_BARS,
        ))
        start += validation.STEP_BARS
    return folds


def fold_universe(panel: Panel, fold: Fold, *, universe_rule: str = RULE_TEST_END_COMPLETE) -> list[str]:
    """Stocks whose own bars, sector ETF bars and SPY bars are complete over the rule's window.

    ``test_end_complete``: [train_start-1, test_end) (original LL-1, reads test-window bars).
    ``point_in_time``: [train_start-1, test_start) (known at fold start).
    """
    if universe_rule == RULE_TEST_END_COMPLETE:
        lo, hi = fold.train_start - 1, fold.test_end
    elif universe_rule == RULE_POINT_IN_TIME:
        lo, hi = fold.train_start - 1, fold.test_start
    else:
        raise LeadLagError(f"unknown universe rule: {universe_rule}")
    members = []
    for stock in STOCKS:
        etf = SECTOR_OF[stock]
        if not (panel.coverage_complete(stock, lo, hi) and panel.coverage_complete(etf, lo, hi)
                and panel.coverage_complete(BENCHMARK, lo, hi)):
            continue
        members.append(stock)
    return members


# ---------------------------------------------------------------- models


@dataclass
class StockFit:
    m0: list[float] | None
    m1: list[float] | None
    m1_fallback: bool


def _features(panel: Panel, rets: Mapping[str, list[float | None]], stock: str, t: int,
              leaders: Mapping[str, list[tuple[str, float | None] | None]]) -> tuple[list[float] | None,
                                                                                    list[float] | None, str | None]:
    """(x0, x1, leader symbol). x1 is None when no leader return is available for a non-self leader."""
    etf = SECTOR_OF[stock]
    spy_r = rets[BENCHMARK][t]
    sec_r = rets[etf][t]
    own_r = rets[stock][t]
    if spy_r is None or sec_r is None or own_r is None:
        return None, None, None
    x0 = [1.0, spy_r, sec_r, own_r]
    lead = leaders[etf][t]
    if lead is None:
        return x0, None, None
    leader_symbol, leader_r = lead
    if leader_symbol == stock:
        return x0, None, leader_symbol
    if leader_r is None:
        return x0, None, leader_symbol
    return x0, x0 + [leader_r], leader_symbol


def _target(panel: Panel, stock: str, t: int) -> float | None:
    o = panel.open[stock]
    if t + 2 >= len(o):
        return None
    if o[t + 1] is None or o[t + 2] is None:
        return None
    return o[t + 2] / o[t + 1] - 1.0  # type: ignore[operator]


def fit_fold(panel: Panel, fold: Fold, stock: str, rets: Mapping[str, list[float | None]],
             leaders: Mapping[str, list[tuple[str, float | None] | None]]) -> StockFit:
    """OLS on train rows whose target open(t+2) is inside the train window (t <= train_end - 3)."""
    rows0: list[list[float]] = []
    rows1: list[list[float]] = []
    targets0: list[float] = []
    targets1: list[float] = []
    for t in range(fold.train_start, fold.train_end - 2):
        x0, x1, _ = _features(panel, rets, stock, t, leaders)
        y = _target(panel, stock, t)
        if x0 is None or y is None:
            continue
        rows0.append(x0)
        targets0.append(y)
        if x1 is not None:
            rows1.append(x1)
            targets1.append(y)
    m0 = ols(rows0, targets0)
    m1 = ols(rows1, targets1)
    return StockFit(m0=m0, m1=m1, m1_fallback=m1 is None and m0 is not None and bool(rows1))


@dataclass
class Forecast:
    m0: float | None
    m1: float | None
    leader_self: bool


def forecasts_for_fold(panel: Panel, fold: Fold, universe: Sequence[str],
                       rets: Mapping[str, list[float | None]],
                       leaders: Mapping[str, list[tuple[str, float | None] | None]],
                       days: Sequence[int]) -> tuple[dict[tuple[str, int], Forecast], dict[str, Any]]:
    fits = {stock: fit_fold(panel, fold, stock, rets, leaders) for stock in universe}
    out: dict[tuple[str, int], Forecast] = {}
    stats = {"fits_m0": 0, "fits_m1": 0, "m1_fallback_to_m0": 0}
    for stock, fit in fits.items():
        if fit.m0 is not None:
            stats["fits_m0"] += 1
        if fit.m1 is not None:
            stats["fits_m1"] += 1
        if fit.m1_fallback:
            stats["m1_fallback_to_m0"] += 1
    for stock in universe:
        fit = fits[stock]
        if fit.m0 is None:
            continue
        for t in days:
            x0, x1, leader = _features(panel, rets, stock, t, leaders)
            if x0 is None:
                continue
            m0 = _dot(fit.m0, x0)
            if leader == stock:
                out[(stock, t)] = Forecast(m0, m0, True)
                continue
            if x1 is None:
                out[(stock, t)] = Forecast(m0, None, False)
                continue
            m1 = _dot(fit.m1, x1) if fit.m1 is not None else m0
            out[(stock, t)] = Forecast(m0, m1, False)
    return out, stats


# ---------------------------------------------------------------- portfolio


def ranked_picks(forecasts: Mapping[tuple[str, int], Forecast], model: str, k: int,
                 universe: Sequence[str], t: int) -> list[str]:
    """Top-k stocks above THRESHOLD on day t, before any fill filter."""
    ranked: list[tuple[float, str]] = []
    for stock in universe:
        forecast = forecasts.get((stock, t))
        if forecast is None:
            continue
        value = forecast.m0 if model == "M0" else forecast.m1
        if value is not None and value > THRESHOLD:
            ranked.append((-value, stock))
    ranked.sort()
    return [stock for _, stock in ranked[:k]]


def _fillable(panel: Panel, stock: str, t: int) -> bool:
    """Point-in-time rule: the execution bar (open t+1) must exist for the order to fill."""
    return t + 1 < len(panel.dates) and panel.open[stock][t + 1] is not None


def decisions_for(forecasts: Mapping[tuple[str, int], Forecast], model: str, k: int,
                  universe: Sequence[str], days: Sequence[int], origin: int, *,
                  panel: Panel | None = None) -> dict[int, dict[str, float]]:
    """Top-k long-only decisions keyed by local simulation index (calendar index - origin).

    With ``panel`` (point-in-time rule), a top-k pick whose open t+1 is missing is dropped after
    ranking. Its 1/k slot stays cash and no lower-ranked stock is promoted into it.
    """
    decisions: dict[int, dict[str, float]] = {}
    for t in days:
        picks = ranked_picks(forecasts, model, k, universe, t)
        if panel is not None:
            picks = [stock for stock in picks if _fillable(panel, stock, t)]
        decisions[t - origin] = {stock: 1.0 / k for stock in picks}
    return decisions


# ---------------------------------------------------------------- experiment


def _sharpe_annual(returns: Sequence[float]) -> float | None:
    return annualize_sharpe(sharpe_per_period(returns))


def _trial_summary(returns: Sequence[float], benchmark: Sequence[float]) -> dict[str, Any]:
    equity = equity_curve(returns)
    drawdown = drawdown_profile(equity) if returns else None
    active = [a - b for a, b in zip(returns, benchmark)]
    return {
        "observations": len(returns),
        "total_return": equity[-1] - 1.0 if returns else None,
        "cagr": cagr(list(returns)),
        "annualized_volatility": annualized_volatility(list(returns)),
        "sharpe_annualized": _sharpe_annual(returns),
        "sharpe_per_period": sharpe_per_period(returns),
        "max_drawdown": drawdown.max_drawdown if drawdown else None,
        "active_probabilistic_sharpe_vs_zero": probabilistic_sharpe(active) if active else None,
    }


def _first_complete_index(panel: Panel, symbol: str) -> int | None:
    for i, (o, c) in enumerate(zip(panel.open[symbol], panel.close[symbol])):
        if o is not None and c is not None:
            return i
    return None


def _bars_for(panel: Panel, symbols: Sequence[str], dates: Sequence[date]) -> dict[str, list[Bar]]:
    return {symbol: [panel.bars[symbol][day] for day in dates] for symbol in symbols}


def _last_observed_close(panel: Panel, symbol: str, index: int) -> float:
    for j in range(index, -1, -1):
        close = panel.close[symbol][j]
        if close is not None:
            return close
    raise LeadLagError(f"{symbol} has no observed close at or before {panel.dates[index]}")


def _valued_bars(panel: Panel, symbols: Sequence[str], dates: Sequence[date]) -> tuple[dict[str, list[Bar]], int]:
    """Point-in-time simulation bars: a missing bar becomes a flat bar at the last observed close.

    Returns the bars and the number of flat bars created. Flat bars are created here only; ``Panel``
    keeps None for missing bars.
    """
    position = {day: i for i, day in enumerate(panel.dates)}
    out: dict[str, list[Bar]] = {}
    flat = 0
    for symbol in symbols:
        table = panel.bars[symbol]
        bars: list[Bar] = []
        for day in dates:
            real = table.get(day)
            if real is not None:
                bars.append(real)
                continue
            last = _last_observed_close(panel, symbol, position[day])
            bars.append(Bar(day, last, last, last, last, 0.0))
            flat += 1
        out[symbol] = bars
    return out, flat


def _sim_bars(panel: Panel, symbols: Sequence[str], dates: Sequence[date], *, pit: bool) -> dict[str, list[Bar]]:
    if pit:
        return _valued_bars(panel, symbols, dates)[0]
    return _bars_for(panel, symbols, dates)


def run_experiment(
    panel: Panel,
    *,
    adjustment_basis: str,
    prior_variance: float | None,
    prior_trial_count: int,
    symbols_missing: Sequence[str] = (),
    dataset_hash: str | None = None,
    universe_rule: str = RULE_TEST_END_COMPLETE,
) -> dict[str, Any]:
    """Pre-registered experiment. Returns the report body (no timestamps).

    Folds start after every factor series (SPY and the seven sector ETFs) has its first complete bar.
    Each fold is simulated on its own calendar window [test_start-1, test_end) with that fold's
    universe and SPY, starting from cash. Fold returns are concatenated, and the folds tile the OOS
    range. SPY is one buy-and-hold over the same range. ``universe_rule`` is described in the module
    docstring.
    """
    if universe_rule not in UNIVERSE_RULES:
        raise LeadLagError(f"unknown universe rule: {universe_rule}")
    pit = universe_rule == RULE_POINT_IN_TIME
    if BENCHMARK not in panel.open:
        raise LeadLagError(f"{BENCHMARK} is required and was not found")
    factor_first: list[int] = []
    for symbol in (BENCHMARK,) + SECTOR_ETFS:
        first = _first_complete_index(panel, symbol)
        if first is None:
            raise LeadLagError(f"factor {symbol} has no complete bars")
        factor_first.append(first)
    first_train_start = max(factor_first) + 1
    rets = {symbol: _returns(panel.close[symbol]) for symbol in panel.close}
    leaders = leader_table(panel)
    folds = tile_folds(len(panel.dates), first_train_start=first_train_start)
    if not folds:
        raise LeadLagError("calendar too short for one walk-forward fold after factor warm-up")
    oos_start = folds[0].test_start
    oos_end = folds[-1].test_end
    if not pit and not panel.coverage_complete(BENCHMARK, oos_start - 1, oos_end):
        # Point-in-time valuation handles SPY gaps with flat bars; the original rule refuses them.
        raise LeadLagError(f"{BENCHMARK} has missing bars inside the OOS range")

    universes = {fold.index: fold_universe(panel, fold, universe_rule=universe_rule) for fold in folds}
    forecasts: dict[tuple[str, int], Forecast] = {}
    fold_records: list[dict[str, Any]] = []
    for fold in folds:
        days = list(range(fold.test_start - 1, fold.test_end - 1))
        fc, stats = forecasts_for_fold(panel, fold, universes[fold.index], rets, leaders, days)
        forecasts.update(fc)
        record: dict[str, Any] = {
            "fold": fold.index,
            "train_realization_range": [fold.train_start, fold.train_end],
            "test_realization_range": [fold.test_start, fold.test_end],
            "test_start": panel.dates[fold.test_start].isoformat(),
            "test_end": panel.dates[fold.test_end - 1].isoformat(),
            "universe": sorted(universes[fold.index]),
            "universe_size": len(universes[fold.index]),
            **stats,
        }
        if pit:
            record["point_in_time"] = _pit_fold_stats(panel, fold, universes[fold.index], forecasts)
        fold_records.append(record)

    cost_rate = cost_rate_from_bps(COMMISSION_BPS, SLIPPAGE_BPS)
    oos_dates = panel.dates[oos_start:oos_end]
    bench_bars = _sim_bars(panel, [BENCHMARK], panel.dates[oos_start - 1:oos_end], pit=pit)
    bench_path = simulate(bench_bars, panel.dates[oos_start - 1:oos_end], {0: {BENCHMARK: 1.0}}, cost_rate)
    bench_returns = list(bench_path.returns)

    trials: dict[str, dict[str, Any]] = {}
    returns_by: dict[str, list[float]] = {}
    stress_by: dict[str, list[float]] = {}
    gross_by: dict[str, list[float]] = {}
    for model in MODELS:
        for k in TOP_K_VALUES:
            name = f"{model}_k{k}"
            returns: list[float] = []
            stress: list[float] = []
            gross: list[float] = []
            for fold in folds:
                origin = fold.test_start - 1
                universe = universes[fold.index]
                local_dates = panel.dates[origin:fold.test_end]
                bars = _sim_bars(panel, list(universe) + [BENCHMARK], local_dates, pit=pit)
                decisions = decisions_for(forecasts, model, k, universe,
                                          list(range(origin, fold.test_end - 1)), origin,
                                          panel=panel if pit else None)
                returns.extend(simulate(bars, local_dates, decisions, cost_rate).returns)
                stress.extend(simulate(bars, local_dates, decisions,
                                       cost_rate * validation.STRESS_COST_MULTIPLIER).returns)
                gross.extend(simulate(bars, local_dates, decisions, 0.0).returns)
            if len(returns) != len(bench_returns):
                raise LeadLagError("fold returns do not tile the OOS range")
            returns_by[name] = returns
            stress_by[name] = stress
            gross_by[name] = gross
            trials[name] = {"model": model, "k": k}

    research_sharpes = [sharpe_per_period(returns_by[name]) for name in returns_by]
    if any(value is None for value in research_sharpes):
        raise LeadLagError("a trial has no defined Sharpe; the sample is not usable")
    new_variance = statistics.variance(research_sharpes)  # type: ignore[arg-type]
    used_variance = new_variance if prior_variance is None else max(new_variance, prior_variance)
    trial_count = TOTAL_TRIALS

    for name, info in trials.items():
        returns = returns_by[name]
        info["summary"] = _trial_summary(returns, bench_returns)
        info["summary_2x_cost"] = _trial_summary(stress_by[name], bench_returns)
        info["summary_gross_zero_cost"] = _trial_summary(gross_by[name], bench_returns)
        info["sharpe_per_period_new_trial"] = sharpe_per_period(returns)
        info["deflated_sharpe"] = deflated_sharpe(returns, used_variance, trial_count)

    benchmark_summary = _trial_summary(bench_returns, bench_returns)

    # Tests and diagnostics use OOS evaluation days: targets fully inside a test window.
    eval_days = [t for fold in folds for t in range(fold.test_start, fold.test_end - 2)]
    t1 = _clark_west_block(panel, forecasts, universes, folds, eval_days, outcome="target")
    diagnostics = {
        "clark_west_close_to_close": _clark_west_block(panel, forecasts, universes, folds, eval_days,
                                                       outcome="close_to_close"),
        "clark_west_overnight_gap_close_to_open": _clark_west_block(panel, forecasts, universes, folds,
                                                                    eval_days, outcome="gap"),
        "clark_west_intraday_open_to_close": _clark_west_block(panel, forecasts, universes, folds,
                                                               eval_days, outcome="intraday"),
    }
    t2_values = [a - b for a, b in zip(returns_by[f"M1_k{PRIMARY_K}"], returns_by[f"M0_k{PRIMARY_K}"])]
    t2 = newey_west_mean_test(t2_values)
    t1_pass = bool(t1 and t1["mean"] > 0.0 and t1["p_one_sided"] < ALPHA)
    t2_pass = bool(t2 and t2["mean"] > 0.0 and t2["p_one_sided"] < ALPHA)
    g1_pass = t1_pass and t2_pass
    diag_cc = diagnostics["clark_west_close_to_close"]
    cc_signal = bool(diag_cc and diag_cc["mean"] > 0.0 and diag_cc["p_one_sided"] < ALPHA)

    oos_obs = len(bench_returns)
    fold_count = len(folds)
    sample_ok = oos_obs >= validation.MIN_OOS_OBSERVATIONS and fold_count >= validation.MIN_FOLDS
    g2 = _gate_g2(trials[f"M1_k{PRIMARY_K}"], bench_summary=benchmark_summary, adjustment_basis=adjustment_basis)
    g2_secondary = _gate_g2(trials["M1_k3"], bench_summary=benchmark_summary, adjustment_basis=adjustment_basis)

    if not sample_ok:
        outcome = OUTCOME_INSUFFICIENT
    elif not g1_pass:
        outcome = OUTCOME_G1_FAILED
    elif not g2["pass"]:
        outcome = OUTCOME_G1_PASSED_G2_FAILED
    else:
        outcome = OUTCOME_PASSED
    classification: str | None = None
    if outcome == OUTCOME_G1_FAILED:
        if cc_signal and not t1_pass:
            classification = CLASS_SIGNAL_NO_EXECUTION
        elif t1_pass and not t2_pass:
            classification = CLASS_SIGNAL_COST_SHORTFALL
        else:
            classification = CLASS_NONE
    cause = ("UNCLASSIFIED: 사전 등록에 자동 분류 규칙이 없음 (사람 판단 필요)"
             if outcome == OUTCOME_G1_PASSED_G2_FAILED else None)

    config = _config()
    if pit:
        config["universe_rule"] = RULE_POINT_IN_TIME
    body: dict[str, Any] = {
        "label": LABEL,
        "generated_by": GENERATED_BY,
        "prereg": {"id": PREREG_ID, "commit": PREREG_COMMIT, "path": PREREG_PATH},
        "config": config,
        "config_hash": sha256_hex(canonical_json(config).encode("utf-8")),
        "dataset": {
            "dataset_hash": dataset_hash,
            "adjustment_basis": adjustment_basis,
            "calendar_first": panel.dates[0].isoformat(),
            "calendar_last": panel.dates[-1].isoformat(),
            "calendar_length": len(panel.dates),
            "first_train_index": first_train_start,
            "first_train_date": panel.dates[first_train_start].isoformat(),
            "factor_first_complete_index": dict(zip((BENCHMARK,) + SECTOR_ETFS, factor_first)),
            "symbols_missing": list(symbols_missing),
            "stocks_loaded": [s for s in STOCKS if s in panel.open],
            "off_calendar_bars": {s: n for s, n in sorted(panel.off_calendar.items()) if n},
            "oos_range": [oos_dates[0].isoformat(), oos_dates[-1].isoformat()],
            "oos_observations": oos_obs,
            "folds": fold_count,
        },
        "prior_trials": {"count": prior_trial_count, "variance_of_research_sharpe_per_period": prior_variance},
        "trials": {
            "count": trial_count,
            "new_count": NEW_TRIAL_COUNT,
            "new_variance_of_sharpe_per_period": new_variance,
            "used_variance_of_sharpe_per_period": used_variance,
            "new_sharpe_per_period": {name: sharpe_per_period(returns_by[name]) for name in returns_by},
        },
        "benchmark": {"symbol": BENCHMARK, "out_of_sample": benchmark_summary},
        "models": trials,
        "tests": {"T1_clark_west_target": t1, "T1_pass": t1_pass,
                  "T2_nw_return_diff_M1k5_minus_M0k5": t2, "T2_pass": t2_pass},
        "diagnostics": diagnostics,
        "gates": {"G1_pass": g1_pass, "G2_primary_M1_k5": g2, "G2_supplementary_M1_k3": g2_secondary,
                  "G2_applies_to": f"M1_k{PRIMARY_K}"},
        "sample_adequacy": {"pass": sample_ok, "min_oos_observations": validation.MIN_OOS_OBSERVATIONS,
                            "min_folds": validation.MIN_FOLDS},
        "outcome": {"status": outcome, "classification": classification, "cause": cause,
                    "recommendation": RECOMMENDATION[outcome]},
        "folds": fold_records,
    }
    if pit:
        trial_names = [f"{model}_k{k}" for model, k in TRIAL_GRID]
        body["universe_rule"] = {
            "rule": RULE_POINT_IN_TIME,
            "membership": "own, sector ETF and SPY bars complete over [train_start-1, test_start)",
            "valuation": "missing bar in simulation = flat bar at last observed close, volume 0 (not in Panel)",
            "unfilled_orders": "top-k pick dropped when open t+1 is missing; slot stays cash; no backfill",
            "trial_count_note": (f"시도 {TOTAL_TRIALS}개 유지: 동일한 사전 등록 {len(TRIAL_GRID)}개 시도의 "
                                 "데이터 처리 정정이며 신규 시도가 아님"),
            "flat_bars_total": sum(r["point_in_time"]["flat_bars"] for r in fold_records),
            "unfilled_orders_total": {
                name: sum(r["point_in_time"]["unfilled_orders"][name] for r in fold_records)
                for name in trial_names
            },
        }
    return body


def _pit_fold_stats(panel: Panel, fold: Fold, universe: Sequence[str],
                    forecasts: Mapping[tuple[str, int], Forecast]) -> dict[str, Any]:
    origin = fold.test_start - 1
    local_dates = panel.dates[origin:fold.test_end]
    _, flat = _valued_bars(panel, list(universe) + [BENCHMARK], local_dates)
    days = list(range(origin, fold.test_end - 1))
    unfilled = {}
    for model, k in TRIAL_GRID:
        count = 0
        for t in days:
            count += sum(1 for stock in ranked_picks(forecasts, model, k, universe, t)
                         if not _fillable(panel, stock, t))
        unfilled[f"{model}_k{k}"] = count
    return {"flat_bars": flat, "unfilled_orders": unfilled}


def _gate_g2(run: Mapping[str, Any], *, bench_summary: Mapping[str, Any], adjustment_basis: str) -> dict[str, Any]:
    dsr = run["deflated_sharpe"]
    sharpe = run["summary"]["sharpe_annualized"]
    bench_sharpe = bench_summary["sharpe_annualized"]
    active_psr = run["summary"]["active_probabilistic_sharpe_vs_zero"]
    stress_sharpe = run["summary_2x_cost"]["sharpe_annualized"]
    checks = {
        "DSR_AT_LEAST_0.95": dsr is not None and dsr >= validation.DSR_MINIMUM,
        "OOS_SHARPE_ABOVE_SPY": sharpe is not None and bench_sharpe is not None and sharpe > bench_sharpe,
        "ACTIVE_PSR_AT_LEAST_0.95": active_psr is not None and active_psr >= validation.PSR_MINIMUM,
        "COST_2X_OOS_SHARPE_POSITIVE": stress_sharpe is not None and stress_sharpe > 0.0,
        "ADJUSTMENT_BASIS_SPLIT_AND_DIVIDEND": adjustment_basis == REQUIRED_BASIS,
    }
    failed = [name for name, passed in checks.items() if not passed]
    return {"pass": not failed, "checks": checks, "failed_criteria": failed,
            "values": {"dsr": dsr, "oos_sharpe_annualized": sharpe, "spy_oos_sharpe_annualized": bench_sharpe,
                       "active_psr": active_psr, "cost_2x_sharpe_annualized": stress_sharpe}}


def _clark_west_block(panel: Panel, forecasts: Mapping[tuple[str, int], Forecast],
                      universe_by_fold: Mapping[int, list[str]], folds: Sequence[Fold],
                      eval_days: Sequence[int], *, outcome: str) -> dict[str, Any] | None:
    """Daily cross-sectional mean of CW loss differentials, tested with Newey-West (lag 5)."""
    fold_of_day: dict[int, Fold] = {}
    for fold in folds:
        for t in range(fold.test_start, fold.test_end - 2):
            fold_of_day[t] = fold
    daily: list[float] = []
    for t in eval_days:
        fold = fold_of_day.get(t)
        if fold is None:
            continue
        losses: list[float] = []
        for stock in universe_by_fold[fold.index]:
            forecast = forecasts.get((stock, t))
            if forecast is None or forecast.m0 is None or forecast.m1 is None:
                continue
            y = _outcome_return(panel, stock, t, outcome)
            if y is None:
                continue
            losses.append(clark_west_loss(y, forecast.m0, forecast.m1))
        if losses:
            daily.append(statistics.fmean(losses))
    return newey_west_mean_test(daily)


def _outcome_return(panel: Panel, stock: str, t: int, outcome: str) -> float | None:
    o = panel.open[stock]
    c = panel.close[stock]
    if outcome == "target":
        if t + 2 >= len(o) or o[t + 1] is None or o[t + 2] is None:
            return None
        return o[t + 2] / o[t + 1] - 1.0  # type: ignore[operator]
    if t + 1 >= len(c) or c[t] is None or c[t + 1] is None or o[t + 1] is None:
        return None
    if outcome == "close_to_close":
        return c[t + 1] / c[t] - 1.0  # type: ignore[operator]
    if outcome == "gap":
        return o[t + 1] / c[t] - 1.0  # type: ignore[operator]
    if outcome == "intraday":
        return c[t + 1] / o[t + 1] - 1.0  # type: ignore[operator]
    raise ValueError(outcome)


# ---------------------------------------------------------------- files and CLI


def _config() -> dict[str, Any]:
    return {
        "prereg": {"id": PREREG_ID, "commit": PREREG_COMMIT, "path": PREREG_PATH},
        "universe": {"benchmark": BENCHMARK, "sector_etfs": list(SECTOR_ETFS),
                     "sector_stocks": {etf: list(stocks) for etf, stocks in SECTOR_STOCKS.items()}},
        "walk_forward": {"train_bars": validation.TRAIN_BARS, "test_bars": validation.TEST_BARS,
                         "step_bars": validation.STEP_BARS, "holdout": None},
        "model": {"M0": ["const", "r_SPY", "r_sector", "r_own"],
                  "M1": ["const", "r_SPY", "r_sector", "r_own", "r_leader"],
                  "target": "open(t+2)/open(t+1)-1", "leader_window_bars": LEADER_WINDOW,
                  "leader_metric": "raw close x raw volume, trailing mean",
                  "self_leader_rule": "M1 equals M0; M1 training excludes self-leader days",
                  "m1_singular_fallback": "M1 equals M0"},
        "portfolio": {"threshold": THRESHOLD, "top_k": list(TOP_K_VALUES), "weights": "1/k",
                      "long_only": True, "execution": "next open", "commission_bps": COMMISSION_BPS,
                      "slippage_bps": SLIPPAGE_BPS, "stress_multiplier": validation.STRESS_COST_MULTIPLIER},
        "gates": {"alpha": ALPHA, "nw_lag": NW_LAG, "dsr_minimum": validation.DSR_MINIMUM,
                  "psr_minimum": validation.PSR_MINIMUM, "total_trials": TOTAL_TRIALS,
                  "prior_trials": PRIOR_TRIAL_COUNT, "new_trials": NEW_TRIAL_COUNT,
                  "primary_k": PRIMARY_K},
        "sample": {"min_oos_observations": validation.MIN_OOS_OBSERVATIONS, "min_folds": validation.MIN_FOLDS},
        "simulation": {
            "mode": "per-fold calendar window [test_start-1, test_end), universe of that fold, start from cash",
            "fold_boundary": "entry from cash each fold (full turnover charged); overnight move from fold last close "
                             "to next fold first open not captured",
            "walk_forward_start": "after first complete bar of SPY and all sector ETFs",
            "stress": "same decisions at 2x cost",
            "zero_cost": "same decisions at 0 cost (diagnostic only)",
        },
        "conventions": {
            "clark_west_average": "cross-sectional mean over the fold universe, leader rows (f = 0) included",
            "newey_west": "Bartlett weights, lag 5, normal approximation for one-sided p",
            "ties": "by symbol name (top-k and leader)",
            "m1_self_leader": "M1 equals M0 on self-leader days; excluded from M1 training",
            "m1_singular": "M1 falls back to M0 for that stock and fold",
        },
    }


def _load_raw(root: Path, manifest: Mapping[str, Any], symbols: Sequence[str]) -> tuple[dict[str, list[Bar]], list[str]]:
    raw: dict[str, list[Bar]] = {}
    missing: list[str] = []
    unadjusted = manifest.get("unadjusted", {})
    for symbol in symbols:
        entry = unadjusted.get(symbol)
        if entry is None:
            missing.append(f"{symbol}:unadjusted")
            continue
        bare = dict(entry)
        bare["file"] = Path(str(entry["file"])).name
        raw[symbol] = _load_symbol(root / "unadjusted", symbol, bare)
    return raw, missing


def run_from_directory(data: str | Path, prior_report: str | Path, *,
                       universe_rule: str = RULE_TEST_END_COMPLETE) -> dict[str, Any]:
    root = Path(data)
    dataset: Dataset = load_dataset(root)
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("interval") != REQUIRED_INTERVAL:
        raise LeadLagError("derived dataset must be 1d")
    factor_missing = [s for s in (BENCHMARK,) + SECTOR_ETFS if s not in dataset.bars]
    if factor_missing:
        # Factors are not substituted: without SPY or a sector ETF the design cannot be estimated.
        raise LeadLagError(f"factor symbols missing from the dataset (stop, no substitution): "
                           f"{', '.join(factor_missing)}")
    present = [s for s in REQUIRED_SYMBOLS if s in dataset.bars]
    missing = [s for s in REQUIRED_SYMBOLS if s not in dataset.bars]
    raw, raw_missing = _load_raw(root, manifest, present)
    calendar = [bar.date for bar in dataset.bars[BENCHMARK]]
    panel = build_panel({s: dataset.bars[s] for s in present}, raw, calendar=calendar)

    prior_path = Path(prior_report)
    prior_bytes = prior_path.read_bytes()
    prior = json.loads(prior_bytes.decode("utf-8"))
    trials = prior.get("trials", {})
    if trials.get("count") != PRIOR_TRIAL_COUNT:
        raise LeadLagError(f"prior report must have {PRIOR_TRIAL_COUNT} trials, found {trials.get('count')}")
    prior_variance = trials.get("variance_of_research_sharpe_per_period")
    if prior_variance is None:
        raise LeadLagError("prior report has no trial variance")
    body = run_experiment(
        panel,
        adjustment_basis=dataset.provenance["adjustment_basis"],
        prior_variance=float(prior_variance),
        prior_trial_count=PRIOR_TRIAL_COUNT,
        symbols_missing=missing + raw_missing,
        dataset_hash=dataset.dataset_hash,
        universe_rule=universe_rule,
    )
    body["prior_report"] = {"sha256": sha256_hex(prior_bytes), "config_hash": prior.get("config_hash"),
                            "trial_count": trials.get("count")}
    body["dataset"]["warnings"] = list(dataset.warnings)
    return body


def render_markdown(report: Mapping[str, Any]) -> str:
    outcome = report["outcome"]
    t1 = report["tests"]["T1_clark_west_target"]
    t2 = report["tests"]["T2_nw_return_diff_M1k5_minus_M0k5"]
    lines = [
        f"# {report['label']}",
        "",
        f"- 사전 등록: {report['prereg']['id']} @ {report['prereg']['commit']}",
        f"- 설정 해시: `{report['config_hash']}`",
        f"- 데이터 해시: `{report['dataset']['dataset_hash']}` (조정 기준: {report['dataset']['adjustment_basis']})",
        f"- OOS 구간: {report['dataset']['oos_range'][0]} ~ {report['dataset']['oos_range'][1]}, "
        f"관측 {report['dataset']['oos_observations']}, fold {report['dataset']['folds']}",
        f"- 누락 심볼: {', '.join(report['dataset']['symbols_missing']) or '없음'}",
    ]
    if "universe_rule" in report:
        rule = report["universe_rule"]
        lines += [
            f"- 유니버스 규칙: {rule['rule']} (설정 키 universe_rule). {rule['trial_count_note']}",
            f"- 유니버스 규칙 집계: 보정 평가 바(flat bar) {rule['flat_bars_total']}개, "
            f"미체결 주문 {rule['unfilled_orders_total']}",
        ]
    lines += [
        "",
        "## 시도(trial) 결과",
        "",
        "| 시도 | Sharpe(연) | 총수익 | 2배 비용 Sharpe | DSR(N=58) | 활성 PSR |",
        "|---|---|---|---|---|---|",
    ]
    for name, info in report["models"].items():
        s = info["summary"]
        lines.append(
            f"| {name} | {_fmt(s['sharpe_annualized'])} | {_fmt(s['total_return'])} | "
            f"{_fmt(info['summary_2x_cost']['sharpe_annualized'])} | {_fmt(info['deflated_sharpe'])} | "
            f"{_fmt(s['active_probabilistic_sharpe_vs_zero'])} |"
        )
    bench = report["benchmark"]["out_of_sample"]
    lines.append(f"| SPY (동일 OOS) | {_fmt(bench['sharpe_annualized'])} | {_fmt(bench['total_return'])} | - | - | - |")
    lines += [
        "",
        "## 판정",
        "",
        f"- T1 Clark-West (M1 vs M0, 주 목표): 평균 {_fmt(t1 and t1['mean'])}, "
        f"NW t {_fmt(t1 and t1['t'])}, 단측 p {_fmt(t1 and t1['p_one_sided'])} → {'통과' if report['tests']['T1_pass'] else '실패'}",
        f"- T2 (M1-M0, k=5 일별 순수익률 차이): 평균 {_fmt(t2 and t2['mean'])}, "
        f"NW t {_fmt(t2 and t2['t'])}, 단측 p {_fmt(t2 and t2['p_one_sided'])} → {'통과' if report['tests']['T2_pass'] else '실패'}",
        f"- G1: {'통과' if report['gates']['G1_pass'] else '실패'}",
        f"- G2 (M1, k=5): {'통과' if report['gates']['G2_primary_M1_k5']['pass'] else '실패'}, "
        f"실패 기준: {', '.join(report['gates']['G2_primary_M1_k5']['failed_criteria']) or '없음'}",
        f"- 결과 상태: {outcome['status']}",
    ]
    if outcome["classification"]:
        lines.append(f"- 진단 분류: {outcome['classification']}")
    if outcome["cause"]:
        lines.append(f"- 원인: {outcome['cause']}")
    lines += ["", "## 권고", "", outcome["recommendation"], ""]
    return "\n".join(lines)


def _fmt(value: Any) -> str:
    if value is None:
        return "n/a"
    if isinstance(value, float):
        return f"{value:.4f}"
    return str(value)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m app.research.leadlag",
                                     description="LL-1 lead-lag experiment (research only; no orders).")
    parser.add_argument("--data", required=True, type=Path, help="derived dataset from app.research.total_return")
    parser.add_argument("--prior-report", required=True, type=Path,
                        help="existing 54-trial report JSON (e.g. research-data/full-report.json)")
    parser.add_argument("--out", required=True, type=Path, help="canonical JSON report path")
    parser.add_argument("--markdown", type=Path, help="markdown summary path")
    parser.add_argument("--universe-rule", choices=UNIVERSE_RULES, default=RULE_TEST_END_COMPLETE,
                        help="test_end_complete (default, original LL-1) or point_in_time (data-handling "
                             "correction; see module docstring)")
    args = parser.parse_args(argv)
    try:
        report = run_from_directory(args.data, args.prior_report, universe_rule=args.universe_rule)
    except (ResearchError, OSError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(canonical_json(report, indent=2) + "\n", encoding="utf-8")
    if args.markdown is not None:
        args.markdown.parent.mkdir(parents=True, exist_ok=True)
        args.markdown.write_text(render_markdown(report), encoding="utf-8")
    print(f"status: {report['outcome']['status']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
