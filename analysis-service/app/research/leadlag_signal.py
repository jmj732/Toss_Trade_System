"""LL-1 signal diagnostics. Read-only: no orders, no broker code, no new trials.

Exploratory analysis of data the LL-1 report already used (the 58-trial report, point-in-time universe).
Not an independent validation. Results cannot change the LL-1 outcome, which is fixed by
``app.research.leadlag``.

Inputs are the same panel, folds, forecasts and fill rule as ``leadlag.run_experiment``. Reused from
``leadlag``: ``build_panel``, ``tile_folds``, ``fold_universe``, ``forecasts_for_fold``, ``ranked_picks``,
``decisions_for``, ``_valued_bars``. The random baseline reuses ``leadlag_audit._random_decisions``.

Sections (each markdown section prints WHY and its interpretation rule before any number):

a. Predictive power. Daily cross-sectional Spearman IC between the forecast at t and open(t+1)->open(t+1+h),
   h in 1, 2, 3, 5, 10, 20. Newey-West t with lag h+4. Also the untradable gap close(t)->open(t+1), and the
   intraday open(t+1)->close(t+1) (one-day NW lag 5).
b. Persistence. Rank autocorrelation of forecasts at lag 1, 2, 5 (same fold only). Day-to-day overlap of
   top-k picks for k = 3, 5.
c. Event study, not a portfolio backtest. For each day t and each top-k pick with forecast > 0.30%, the
   cumulative excess return over the same-day equal-weight fold universe, from open(t+1) to open(t+1+h).
   The daily mean over picks is the series that gets the NW test (lag h+4). Compared with 30 bp (round trip)
   and 60 bp (2x stress). Also the gross CAGR per unit turnover and the breakeven one-way cost.
d. Exposure attribution. OLS of the zero-cost (gross) daily returns on SPY and the EW universe, or on SPY and
   the seven sector ETFs. HAC standard errors (Bartlett, lag 5). Exposure-matched random selection, seeds
   0..49.
e. Survivorship sensitivity. Picks bucketed by their own day-t return; per-decade stability. Survivorship
   cannot be quantified without delisted data, and this module does not claim otherwise.
f. Verdict. CONVERTIBLE or NOT_CONVERTIBLE, computed from the rules in ``VERDICT_RULE``.

Pre-stated choices (fixed in code before any run):

- Forecast signal (a, b, c, e): forecasts for the model and k, from the PIT universe. Picks for (b, c, e) are
  the top-k before the fill filter, because they measure the signal. The engine's fill filter is applied only
  to the simulated book (zero-cost gross returns in c and d). Unfilled orders are counted in the report.
- Primary trial: M1 with k=5. Other trials are reported beside it and are not used for the verdict.
- Event excess: pick return minus the mean return of the same-day fold universe members with usable bars
  over the same horizon. Missing bars drop that pick for that horizon only.
- Daily series: one mean per day over that day's picks. NW tests run on those daily series, not on events.
- Factor returns for d use the valued bars (flat bar at the last close on a data gap), the same convention the
  engine uses.
- Decades: 2001-2009 (includes the first OOS days from 2001-12-28), 2010-2017, 2018-2026. The 2001 start is a
  deviation from the requested 2002-2009 split, so no OOS day is dropped.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from app.research import leadlag as L
from app.research.backtest import cost_rate_from_bps, simulate
from app.research.data import REQUIRED_INTERVAL, Bar, ResearchError, canonical_json, load_dataset, sha256_hex
from app.research.leadlag_audit import _random_decisions
from app.research.metrics import cagr
from app.research.validation import STRESS_COST_MULTIPLIER

SIGNAL_LABEL = "LL-1 신호 진단 (읽기 전용, 탐색적: 이미 본 데이터, 새 시도 아님, 독립 검증 아님, 주문 없음)"
IC_HORIZONS = (1, 2, 3, 5, 10, 20)
EVENT_HORIZONS = tuple(range(1, 21))
DAYS_PER_YEAR = 252
ONE_WAY_COST = cost_rate_from_bps(L.COMMISSION_BPS, L.SLIPPAGE_BPS)
ROUND_TRIP_COST = 2.0 * ONE_WAY_COST
STRESS_ROUND_TRIP_COST = STRESS_COST_MULTIPLIER * ROUND_TRIP_COST
MIN_CROSS_SECTION = 5
ALPHA_NW_LAG = 5
GAP_NW_LAG = 5
CUM_T_MIN = 2.0
ALPHA_T_MIN = 2.0
PRIMARY_MODEL, PRIMARY_K = "M1", 5
SEEDS = tuple(range(50))
DECADES = (("2001-2009", 2001, 2009), ("2010-2017", 2010, 2017), ("2018-2026", 2018, 2026))
BUCKET_LABELS = ("r < -5%", "-5% <= r < -2%", "-2% <= r <= 0%", "r > 0%")
VERDICT_RULE = (
    "CONVERTIBLE: 어떤 h(1..20)에서 평균 누적 초과수익 > 30bp 이고 NW t > 2, 그리고 "
    "SPY+EW 회귀 알파 t > 2 (주 시도 M1_k5). 두 조건 중 하나라도 실패하면 NOT_CONVERTIBLE."
)
RULE_TEXT = {
    "a": "예측력 있음: 실현 가능한 open->open 구간(h >= 1) 중 하나 이상에서 평균 IC > 0 이고 NW t > 2. "
         "갭(close->open)은 체결 불가라 참고용이다.",
    "b": "지속성이 높으면 목록이 천천히 바뀌어 턴오버가 낮다. 임계 없이 수치만 보고한다.",
    "c": "최소 h에서 누적 초과수익 > 30bp 이고 NW t > 2 이면 '비용 넘김'. 60bp도 같은 방식으로 본다. "
         "한 시점 누적은 정책이 아니라 신호 감쇠 곡선이다.",
    "d": "알파 t > 2 이면 SPY·EW(또는 섹터 ETF)로 설명되지 않는 잔차 수익이 있다고 본다. 무작위 노출 일치 "
         "평균이 전략 평균보다 낮으면 선택 자체의 기여가 있다고 본다.",
    "e": "하락 후 반등 구간이 가장 크면 생존편향 민감도가 크다는 신호다. 연대별 평균이 한 방향으로 줄면 "
         "감쇠로 본다. 생존편향 크기는 상장폐지 데이터 없이 정량화할 수 없다.",
}


# ---------------------------------------------------------------- statistics


def _average_ranks(values: Sequence[float]) -> list[float]:
    order = sorted(range(len(values)), key=lambda i: values[i])
    ranks = [0.0] * len(values)
    i = 0
    while i < len(values):
        j = i
        while j + 1 < len(values) and values[order[j + 1]] == values[order[i]]:
            j += 1
        average = (i + j) / 2.0 + 1.0
        for m in range(i, j + 1):
            ranks[order[m]] = average
        i = j + 1
    return ranks


def _pearson(a: Sequence[float], b: Sequence[float]) -> float | None:
    n = len(a)
    if n < 2:
        return None
    ma = sum(a) / n
    mb = sum(b) / n
    saa = sum((x - ma) ** 2 for x in a)
    sbb = sum((y - mb) ** 2 for y in b)
    if saa <= 0.0 or sbb <= 0.0:
        return None
    return sum((x - ma) * (y - mb) for x, y in zip(a, b)) / math.sqrt(saa * sbb)


def spearman(xs: Sequence[float], ys: Sequence[float]) -> float | None:
    if len(xs) != len(ys):
        raise ResearchError("spearman needs equal-length inputs")
    return _pearson(_average_ranks(xs), _average_ranks(ys))


def _nw(values: Sequence[float], lag: int) -> dict[str, Any] | None:
    return L.newey_west_mean_test(list(values), lag=lag)


def hac_ols(design: Sequence[Sequence[float]], target: Sequence[float], lag: int = ALPHA_NW_LAG) -> dict[str, Any]:
    """OLS with Newey-West (Bartlett) sandwich covariance: (X'X)^-1 Omega (X'X)^-1."""
    n = len(target)
    p = len(design[0])
    if n <= p + 1:
        raise ResearchError("regression has too few observations")
    xtx = [[0.0] * p for _ in range(p)]
    xty = [0.0] * p
    for row, y in zip(design, target):
        for i in range(p):
            xty[i] += row[i] * y
            for j in range(p):
                xtx[i][j] += row[i] * row[j]
    beta = L._solve(xtx, xty)
    if beta is None:
        raise ResearchError("singular regression design")
    resid = [y - sum(r * b for r, b in zip(row, beta)) for row, y in zip(design, target)]
    scores = [[row[i] * e for i in range(p)] for row, e in zip(design, resid)]
    omega = [[0.0] * p for _ in range(p)]
    for s in scores:
        for i in range(p):
            for j in range(p):
                omega[i][j] += s[i] * s[j]
    for j in range(1, lag + 1):
        weight = 1.0 - j / (lag + 1)
        for t in range(j, n):
            a, b = scores[t], scores[t - j]
            for i in range(p):
                for m in range(p):
                    omega[i][m] += weight * (a[i] * b[m] + b[i] * a[m])
    columns = []
    for c in range(p):
        column = L._solve(xtx, [1.0 if r == c else 0.0 for r in range(p)])
        if column is None:
            raise ResearchError("singular regression design")
        columns.append(column)
    inv = [[columns[j][i] for j in range(p)] for i in range(p)]
    tmp = [[sum(inv[i][k] * omega[k][j] for k in range(p)) for j in range(p)] for i in range(p)]
    cov = [[sum(tmp[i][k] * inv[k][j] for k in range(p)) for j in range(p)] for i in range(p)]
    se = [math.sqrt(max(cov[i][i], 0.0)) for i in range(p)]
    ybar = sum(target) / n
    sst = sum((y - ybar) ** 2 for y in target)
    ssr = sum(e * e for e in resid)
    return {
        "n": n,
        "coef": beta,
        "se": se,
        "t": [beta[i] / se[i] if se[i] > 0.0 else None for i in range(p)],
        "r2": 1.0 - ssr / sst if sst > 0.0 else None,
        "nw_lag": lag,
    }


# ---------------------------------------------------------------- state


@dataclass
class State:
    panel: L.Panel
    folds: list[L.Fold]
    universes: dict[int, list[str]]
    forecasts: dict[tuple[str, int], L.Forecast]
    fold_of_day: dict[int, L.Fold]
    days: list[int]
    pit: bool
    dataset_hash: str | None = None
    fold_values: dict[int, tuple[list[date], dict[str, list[Bar]], int]] = field(default_factory=dict)
    dec_cal: dict[str, dict[int, dict[int, dict[str, float]]]] = field(default_factory=dict)

    @property
    def flat_bars(self) -> int:
        return sum(flat for _, _, flat in self.fold_values.values())


def _value(forecast: L.Forecast, model: str) -> float | None:
    return forecast.m0 if model == "M0" else forecast.m1


def _open_to_open(panel: L.Panel, stock: str, t: int, h: int) -> float | None:
    """Realized return open(t+1) -> open(t+1+h) on real bars only."""
    o = panel.open[stock]
    a, b = t + 1, t + 1 + h
    if b >= len(o) or o[a] is None or o[b] is None:
        return None
    return o[b] / o[a] - 1.0  # type: ignore[operator]


def _gap(panel: L.Panel, stock: str, t: int) -> float | None:
    """Untradable: close(t) -> open(t+1)."""
    o, c = panel.open[stock], panel.close[stock]
    if t + 1 >= len(o) or c[t] is None or o[t + 1] is None:
        return None
    return o[t + 1] / c[t] - 1.0  # type: ignore[operator]


def _intraday(panel: L.Panel, stock: str, t: int) -> float | None:
    """open(t+1) -> close(t+1)."""
    o, c = panel.open[stock], panel.close[stock]
    if t + 1 >= len(o) or o[t + 1] is None or c[t + 1] is None:
        return None
    return c[t + 1] / o[t + 1] - 1.0  # type: ignore[operator]


def _own_return(panel: L.Panel, stock: str, t: int) -> float | None:
    c = panel.close[stock]
    if t < 1 or c[t] is None or c[t - 1] is None:
        return None
    return c[t] / c[t - 1] - 1.0  # type: ignore[operator]


# ---------------------------------------------------------------- a. predictive power


def daily_ic(panel: L.Panel, days: Sequence[int], fold_of_day: Mapping[int, L.Fold],
             universes: Mapping[int, Sequence[str]], forecasts: Mapping[tuple[str, int], L.Forecast],
             model: str, outcome: str, h: int = 1) -> list[float]:
    """Daily cross-sectional Spearman IC. outcome: 'open_fwd' (h), 'gap' or 'intraday'."""
    series: list[float] = []
    for t in days:
        universe = universes[fold_of_day[t].index]
        xs: list[float] = []
        ys: list[float] = []
        for stock in universe:
            forecast = forecasts.get((stock, t))
            if forecast is None:
                continue
            value = _value(forecast, model)
            if value is None:
                continue
            if outcome == "open_fwd":
                y = _open_to_open(panel, stock, t, h)
            elif outcome == "gap":
                y = _gap(panel, stock, t)
            else:
                y = _intraday(panel, stock, t)
            if y is None:
                continue
            xs.append(value)
            ys.append(y)
        if len(xs) >= MIN_CROSS_SECTION:
            rho = spearman(xs, ys)
            if rho is not None:
                series.append(rho)
    return series


def ic_summary(series: Sequence[float], lag: int) -> dict[str, Any]:
    stats = _nw(series, lag)
    return {"days": len(series), "mean_ic": stats["mean"] if stats else None,
            "nw_t": stats["t"] if stats else None, "nw_lag": lag}


# ---------------------------------------------------------------- b. persistence


def rank_autocorrelation(days: Sequence[int], fold_of_day: Mapping[int, L.Fold],
                         universes: Mapping[int, Sequence[str]], forecasts: Mapping[tuple[str, int], L.Forecast],
                         model: str, lag: int) -> dict[str, Any]:
    values: list[float] = []
    for t in days:
        fold = fold_of_day[t]
        s = t - lag
        if fold_of_day.get(s) is None or fold_of_day[s].index != fold.index:
            continue
        xs: list[float] = []
        ys: list[float] = []
        for stock in universes[fold.index]:
            now = forecasts.get((stock, t))
            old = forecasts.get((stock, s))
            if now is None or old is None:
                continue
            a, b = _value(now, model), _value(old, model)
            if a is None or b is None:
                continue
            xs.append(a)
            ys.append(b)
        if len(xs) >= MIN_CROSS_SECTION:
            rho = spearman(xs, ys)
            if rho is not None:
                values.append(rho)
    return {"days": len(values), "mean_rank_corr": sum(values) / len(values) if values else None}


def pick_overlap(days: Sequence[int], fold_of_day: Mapping[int, L.Fold],
                 universes: Mapping[int, Sequence[str]], forecasts: Mapping[tuple[str, int], L.Forecast],
                 model: str, k: int) -> dict[str, Any]:
    """Share of today's top-k picks that were also top-k picks yesterday. Only full k-name days count."""
    values: list[float] = []
    for t in days:
        fold = fold_of_day[t]
        prev = fold_of_day.get(t - 1)
        if prev is None or prev.index != fold.index:
            continue
        now = L.ranked_picks(forecasts, model, k, universes[fold.index], t)
        before = L.ranked_picks(forecasts, model, k, universes[fold.index], t - 1)
        if len(now) == k and len(before) == k:
            values.append(len(set(now) & set(before)) / k)
    return {"days": len(values), "mean_overlap": sum(values) / len(values) if values else None}


# ---------------------------------------------------------------- c. event study


@dataclass
class EventRecord:
    t: int
    stock: str
    own_return: float | None
    excess: dict[int, float]


@dataclass
class EventStudy:
    model: str
    k: int
    records: list[EventRecord]
    daily: dict[int, list[tuple[int, float]]]


def event_study(panel: L.Panel, days: Sequence[int], fold_of_day: Mapping[int, L.Fold],
                universes: Mapping[int, Sequence[str]], forecasts: Mapping[tuple[str, int], L.Forecast],
                model: str, k: int, horizons: Sequence[int] = EVENT_HORIZONS) -> EventStudy:
    records: list[EventRecord] = []
    daily: dict[int, list[tuple[int, float]]] = {h: [] for h in horizons}
    for t in days:
        universe = universes[fold_of_day[t].index]
        picks = L.ranked_picks(forecasts, model, k, universe, t)
        if not picks:
            continue
        per_pick: dict[str, dict[int, float]] = {stock: {} for stock in picks}
        day_values: dict[int, list[float]] = {h: [] for h in horizons}
        for h in horizons:
            members = [r for r in (_open_to_open(panel, stock, t, h) for stock in universe) if r is not None]
            if not members:
                continue
            equal_weight = sum(members) / len(members)
            for stock in picks:
                realized = _open_to_open(panel, stock, t, h)
                if realized is None:
                    continue
                excess = realized - equal_weight
                per_pick[stock][h] = excess
                day_values[h].append(excess)
        for h in horizons:
            if day_values[h]:
                daily[h].append((t, sum(day_values[h]) / len(day_values[h])))
        for stock in picks:
            records.append(EventRecord(t, stock, _own_return(panel, stock, t), per_pick[stock]))
    return EventStudy(model, k, records, daily)


def cumulative_table(study: EventStudy, horizons: Sequence[int] = EVENT_HORIZONS) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for h in horizons:
        stats = _nw([value for _, value in study.daily[h]], lag=h + 4)
        mean = stats["mean"] if stats else None
        t_stat = stats["t"] if stats else None
        events = sum(1 for record in study.records if h in record.excess)
        rows.append({
            "h": h,
            "days": len(study.daily[h]),
            "events": events,
            "mean_cum_excess": mean,
            "nw_t": t_stat,
            "nw_lag": h + 4,
            "above_30bp_t2": bool(mean is not None and t_stat is not None
                                  and mean > ROUND_TRIP_COST and t_stat > CUM_T_MIN),
            "above_60bp_t2": bool(mean is not None and t_stat is not None
                                  and mean > STRESS_ROUND_TRIP_COST and t_stat > CUM_T_MIN),
        })
    return rows


def first_horizon(rows: Sequence[Mapping[str, Any]], flag: str) -> int | None:
    hits = [row["h"] for row in rows if row[flag]]
    return min(hits) if hits else None


def _bucket(return_value: float) -> int:
    if return_value < -0.05:
        return 0
    if return_value < -0.02:
        return 1
    if return_value <= 0.0:
        return 2
    return 3


def survivorship_buckets(study: EventStudy, panel: L.Panel) -> list[dict[str, Any]]:
    """Buckets by the pick's own day-t return. Uses the 1-day excess (open t+1 -> open t+2)."""
    rows = [(record, record.excess[1]) for record in study.records
            if record.own_return is not None and 1 in record.excess]
    total = sum(value for _, value in rows)
    out: list[dict[str, Any]] = []
    for index, label in enumerate(BUCKET_LABELS):
        members = [value for record, value in rows if _bucket(record.own_return) == index]  # type: ignore[arg-type]
        bucket_total = sum(members)
        out.append({
            "bucket": label,
            "count": len(members),
            "share_of_total_excess": bucket_total / total if total else None,
            "mean_1day_excess": bucket_total / len(members) if members else None,
        })
    return out


def decade_table(study: EventStudy, panel: L.Panel) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for label, lo, hi in DECADES:
        values = [record.excess[1] for record in study.records
                  if 1 in record.excess and lo <= panel.dates[record.t].year <= hi]
        out.append({"period": label, "events": len(values),
                    "mean_1day_excess": sum(values) / len(values) if values else None})
    return out


# ---------------------------------------------------------------- d. exposure attribution


def gross_and_turnover(state: State, name: str) -> dict[str, Any]:
    """Zero-cost and net daily returns for one trial. Same engine path as the LL-1 run."""
    gross: list[float] = []
    net: list[float] = []
    turnover: list[float] = []
    per_fold: dict[int, list[float]] = {}
    for fold in state.folds:
        origin = fold.test_start - 1
        local_dates, values, _ = state.fold_values[fold.index]
        decisions = {key - origin: weights for key, weights in state.dec_cal[name][fold.index].items()}
        members = set(state.universes[fold.index]) | {L.BENCHMARK}
        bars = {symbol: series for symbol, series in values.items() if symbol in members}
        zero = simulate(bars, local_dates, decisions, 0.0)
        paid = simulate(bars, local_dates, decisions, ONE_WAY_COST)
        gross.extend(zero.returns)
        net.extend(paid.returns)
        turnover.extend(zero.turnover)
        per_fold[fold.index] = list(paid.returns)
    return {"gross": gross, "net": net, "turnover": turnover, "per_fold_net": per_fold}


def factor_rows(state: State) -> tuple[list[list[float]], list[list[float]]]:
    """Design rows per realization day: [1, SPY, EW] and [1, SPY, seven sector ETFs] (both with intercept).

    Realization days are the fold's test days, in the same order the engine returns them. Factor returns use
    the valued bars, the same convention as the engine.
    """
    spy_ew: list[list[float]] = []
    sectors: list[list[float]] = []
    for fold in state.folds:
        local_dates, values, _ = state.fold_values[fold.index]
        universe = state.universes[fold.index]

        def ret(symbol: str, idx: int) -> float:
            series = values[symbol]
            return series[idx].close / series[idx - 1].close - 1.0

        for idx in range(1, len(local_dates)):
            spy = ret(L.BENCHMARK, idx)
            ew = sum(ret(stock, idx) for stock in universe) / len(universe)
            spy_ew.append([1.0, spy, ew])
            sectors.append([1.0, spy] + [ret(etf, idx) for etf in L.SECTOR_ETFS])
    return spy_ew, sectors


def alpha_regression(gross: Sequence[float], design: Sequence[Sequence[float]]) -> dict[str, Any]:
    fit = hac_ols(design, gross, lag=ALPHA_NW_LAG)
    return {
        "n": fit["n"],
        "alpha_annualized": fit["coef"][0] * DAYS_PER_YEAR,
        "alpha_se_annualized": fit["se"][0] * DAYS_PER_YEAR,
        "alpha_t": fit["t"][0],
        "betas": {"SPY": fit["coef"][1]},
        "beta_t": {"SPY": fit["t"][1]},
        "r2": fit["r2"],
        "nw_lag": fit["nw_lag"],
        "coef_names": ["const", "SPY"] + (["EW"] if len(design[0]) == 3 else list(L.SECTOR_ETFS)),
        "coef": fit["coef"],
        "t": fit["t"],
    }


def random_matched(state: State, name: str, k: int, strategy_gross_daily: float) -> dict[str, Any]:
    """Random picks per day with the same daily count (equal weight), from the fold universe, seeds 0..49.

    Random picks ignore the fill filter. Unfilled orders are zero in the PIT run, so this is not material.
    """
    es = SimpleNamespace(folds=state.folds, universes=state.universes, dec_cal=state.dec_cal)
    means: list[float] = []
    for seed in SEEDS:
        picks = _random_decisions(es, name, k, seed)
        returns: list[float] = []
        for fold in state.folds:
            origin = fold.test_start - 1
            local_dates, values, _ = state.fold_values[fold.index]
            members = set(state.universes[fold.index]) | {L.BENCHMARK}
            bars = {symbol: series for symbol, series in values.items() if symbol in members}
            decisions = {key - origin: weights for key, weights in picks[fold.index].items()}
            returns.extend(simulate(bars, local_dates, decisions, 0.0).returns)
        means.append(sum(returns) / len(returns))
    below = sum(1 for value in means if value < strategy_gross_daily)
    return {"seeds": len(SEEDS), "mean_gross_daily": sum(means) / len(means),
            "min_gross_daily": min(means), "max_gross_daily": max(means),
            "strategy_gross_daily": strategy_gross_daily,
            "share_of_seeds_below_strategy": below / len(means)}


# ---------------------------------------------------------------- f. verdict


def verdict(cumulative: Sequence[Mapping[str, Any]], alpha_t: float | None) -> dict[str, Any]:
    qualifying = [row["h"] for row in cumulative if row["above_30bp_t2"]]
    cum_pass = bool(qualifying)
    alpha_pass = alpha_t is not None and alpha_t > ALPHA_T_MIN
    reasons: list[str] = []
    if not cum_pass:
        reasons.append("h = 1..20 어디에서도 평균 누적 초과수익 > 30bp 이고 t > 2 인 구간이 없다")
    if not alpha_pass:
        reasons.append(f"SPY+EW 회귀 알파 t = {_fmt(alpha_t)} (기준 > 2 미달)")
    status = "CONVERTIBLE" if cum_pass and alpha_pass else "NOT_CONVERTIBLE"
    return {"status": status, "rule": VERDICT_RULE, "cumulative_pass": cum_pass,
            "qualifying_horizons": qualifying, "alpha_pass": alpha_pass, "alpha_t": alpha_t,
            "reasons": reasons}


# ---------------------------------------------------------------- build


def build_state(root: str | Path, universe_rule: str = L.RULE_POINT_IN_TIME) -> State:
    root = Path(root)
    dataset = load_dataset(root)
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("interval") != REQUIRED_INTERVAL:
        raise ResearchError("derived dataset must be 1d")
    factor_missing = [s for s in (L.BENCHMARK,) + L.SECTOR_ETFS if s not in dataset.bars]
    if factor_missing:
        raise ResearchError(f"factor symbols missing from the dataset: {', '.join(factor_missing)}")
    present = [s for s in L.REQUIRED_SYMBOLS if s in dataset.bars]
    raw, _ = L._load_raw(root, manifest, present)
    calendar = [bar.date for bar in dataset.bars[L.BENCHMARK]]
    panel = L.build_panel({s: dataset.bars[s] for s in present}, raw, calendar=calendar)
    first = max(L._first_complete_index(panel, s) for s in (L.BENCHMARK,) + L.SECTOR_ETFS) + 1  # type: ignore[type-var]
    rets = {s: L._returns(panel.close[s]) for s in panel.close}
    leaders = L.leader_table(panel)
    folds = L.tile_folds(len(panel.dates), first_train_start=first)
    if not folds:
        raise ResearchError("calendar too short for one walk-forward fold")
    pit = universe_rule == L.RULE_POINT_IN_TIME
    universes = {f.index: L.fold_universe(panel, f, universe_rule=universe_rule) for f in folds}
    forecasts: dict[tuple[str, int], L.Forecast] = {}
    fold_of_day: dict[int, L.Fold] = {}
    for fold in folds:
        days = list(range(fold.test_start - 1, fold.test_end - 1))
        fc, _ = L.forecasts_for_fold(panel, fold, universes[fold.index], rets, leaders, days)
        forecasts.update(fc)
        for t in days:
            fold_of_day[t] = fold
    state = State(panel=panel, folds=folds, universes=universes, forecasts=forecasts,
                  fold_of_day=fold_of_day, days=sorted(fold_of_day), pit=pit,
                  dataset_hash=dataset.dataset_hash)
    symbols_all = sorted({L.BENCHMARK, *L.SECTOR_ETFS})
    for fold in folds:
        origin = fold.test_start - 1
        local_dates = panel.dates[origin:fold.test_end]
        members = sorted(set(universes[fold.index]) | set(symbols_all))
        values, flat = L._valued_bars(panel, members, local_dates)
        state.fold_values[fold.index] = (list(local_dates), values, flat)
    for model, k in L.TRIAL_GRID:
        name = f"{model}_k{k}"
        state.dec_cal[name] = {}
        for fold in folds:
            origin = fold.test_start - 1
            local = L.decisions_for(forecasts, model, k, universes[fold.index],
                                    list(range(origin, fold.test_end - 1)), origin,
                                    panel=panel if pit else None)
            state.dec_cal[name][fold.index] = {key + origin: weights for key, weights in local.items()}
    return state


# ---------------------------------------------------------------- report


def _fmt(value: Any, digits: int = 4) -> str:
    if value is None:
        return "n/a"
    if isinstance(value, bool):
        return "예" if value else "아니오"
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


def _pct(value: Any) -> str:
    return "n/a" if value is None else f"{value * 100:.3f}%"


def run_diagnostics(root: str | Path, universe_rule: str = L.RULE_POINT_IN_TIME,
                    reference: Mapping[str, Any] | None = None) -> dict[str, Any]:
    state = build_state(root, universe_rule)
    panel = state.panel
    days = state.days
    fod, uni, fc = state.fold_of_day, state.universes, state.forecasts

    ic: dict[str, Any] = {}
    for model in L.MODELS:
        rows = []
        for h in IC_HORIZONS:
            rows.append({"h": h, "open_to_open": ic_summary(daily_ic(panel, days, fod, uni, fc, model, "open_fwd", h), h + 4)})
        rows.append({"h": "gap_close_to_open", "untradable": True,
                     "summary": ic_summary(daily_ic(panel, days, fod, uni, fc, model, "gap"), GAP_NW_LAG)})
        rows.append({"h": "intraday_open_to_close", "summary": ic_summary(
            daily_ic(panel, days, fod, uni, fc, model, "intraday"), GAP_NW_LAG)})
        ic[model] = rows

    persistence = {}
    for model in L.MODELS:
        persistence[model] = {
            "rank_autocorr": {f"lag{lag}": rank_autocorrelation(days, fod, uni, fc, model, lag) for lag in (1, 2, 5)},
            "topk_overlap": {f"k{k}": pick_overlap(days, fod, uni, fc, model, k) for k in (3, 5)},
        }

    trials: dict[str, Any] = {}
    for model, k in L.TRIAL_GRID:
        name = f"{model}_k{k}"
        run = gross_and_turnover(state, name)
        gross = run["gross"]
        turnover = run["turnover"]
        gross_cagr = cagr(gross)
        annual_turnover = sum(turnover) / len(turnover) * DAYS_PER_YEAR
        trials[name] = {
            "net_total_return": _compound(run["net"]),
            "gross_total_return": _compound(gross),
            "gross_cagr": gross_cagr,
            "annual_turnover_one_way": annual_turnover,
            "gross_cagr_per_annual_turnover": (gross_cagr / annual_turnover) if gross_cagr is not None else None,
            "breakeven_one_way_cost_arithmetic": (sum(gross) / len(gross)) * DAYS_PER_YEAR / annual_turnover,
            "gross_daily_mean": sum(gross) / len(gross),
            "observations": len(gross),
            "_gross": gross,
            "_net": run["net"],
        }
    if reference is not None:
        ref_models = reference.get("models", {})
        for name, info in trials.items():
            if name in ref_models:
                info["reference_net_total_return"] = ref_models[name]["summary"]["total_return"]
                info["reference_diff"] = abs(info["net_total_return"] - ref_models[name]["summary"]["total_return"])

    spy_ew, sectors = factor_rows(state)
    exposure: dict[str, Any] = {}
    random_baseline: dict[str, Any] = {}
    for name in trials:
        gross = trials[name]["_gross"]
        exposure[name] = {
            "spy_ew": alpha_regression(gross, spy_ew),
            "spy_sectors": alpha_regression(gross, sectors),
        }
        k = int(name.split("_k")[1])
        random_baseline[name] = random_matched(state, name, k, trials[name]["gross_daily_mean"])

    events: dict[str, Any] = {}
    studies: dict[str, EventStudy] = {}
    for model in L.MODELS:
        for k in L.TOP_K_VALUES:
            study = event_study(panel, days, fod, uni, fc, model, k)
            studies[f"{model}_k{k}"] = study
            events[f"{model}_k{k}"] = cumulative_table(study)
    primary_name = f"{PRIMARY_MODEL}_k{PRIMARY_K}"
    primary_study = studies[primary_name]
    primary_cumulative = events[primary_name]
    buckets = {f"{m}_k{PRIMARY_K}": survivorship_buckets(studies[f"{m}_k{PRIMARY_K}"], panel) for m in L.MODELS}
    decades = {f"{m}_k{PRIMARY_K}": decade_table(studies[f"{m}_k{PRIMARY_K}"], panel) for m in L.MODELS}

    alpha_t = exposure[primary_name]["spy_ew"]["alpha_t"]
    result = verdict(primary_cumulative, alpha_t)
    for info in trials.values():
        info.pop("_gross", None)
        info.pop("_net", None)

    return {
        "label": SIGNAL_LABEL,
        "universe_rule": universe_rule,
        "dataset_hash": state.dataset_hash,
        "flat_bars_in_valued_windows": state.flat_bars,
        "oos_days": len(days),
        "oos_range": [panel.dates[days[0]].isoformat(), panel.dates[days[-1] + 1].isoformat()],
        "costs": {"one_way": ONE_WAY_COST, "round_trip": ROUND_TRIP_COST,
                  "round_trip_stress": STRESS_ROUND_TRIP_COST},
        "a_predictive_power": {"ic": ic},
        "b_persistence": persistence,
        "c_event_study": {"cumulative": events,
                          "first_horizon_above_30bp_t2": {k: first_horizon(v, "above_30bp_t2") for k, v in events.items()},
                          "first_horizon_above_60bp_t2": {k: first_horizon(v, "above_60bp_t2") for k, v in events.items()},
                          "primary": primary_name},
        "c_engine_efficiency": trials,
        "d_exposure": {"exposure": exposure, "random_matched": random_baseline},
        "e_survivorship": {"buckets": buckets, "decades": decades},
        "f_verdict": result,
        "primary_trial": primary_name,
    }


def _compound(returns: Sequence[float]) -> float:
    value = 1.0
    for r in returns:
        value *= 1.0 + r
    return value - 1.0


# ---------------------------------------------------------------- markdown


def render_markdown(report: Mapping[str, Any]) -> str:
    lines: list[str] = [f"# {report['label']}", ""]
    lines += [
        "- 목적: LL-1 신호가 어디서 생기고 비용과 노출로 설명되는지 본다. 판정(LL-1)은 바꾸지 않는다.",
        "- 성격: 이미 본 데이터에 대한 탐색이다. 새 시도를 만들지 않는다(시도 58 유지). 독립 검증이 아니다.",
        f"- 유니버스 규칙: `{report['universe_rule']}`. 데이터 해시: `{report['dataset_hash']}`",
        f"- 예측일: {report['oos_range'][0]} ~ 마지막 실현일 {report['oos_range'][1]} (예측 {report['oos_days']}일)",
        f"- 비용: 편도 {_pct(report['costs']['one_way'])}, 왕복 {_pct(report['costs']['round_trip'])}, "
        f"2배 스트레스 왕복 {_pct(report['costs']['round_trip_stress'])}",
        f"- 보정 평가 바(flat bar) 수: {report['flat_bars_in_valued_windows']}",
        "",
    ]
    lines += _section_a(report)
    lines += _section_b(report)
    lines += _section_c(report)
    lines += _section_d(report)
    lines += _section_e(report)
    lines += _section_f(report)
    return "\n".join(lines) + "\n"


def _section_a(report: Mapping[str, Any]) -> list[str]:
    lines = ["## a. 예측력 (일별 횡단면 Spearman IC)", "",
             "- 왜: 점수가 다음 날 시가 이후 수익을 순위로 맞히는지 본다. 갭(종가→시가)은 체결할 수 없어 따로 본다.",
             f"- 해석 규칙(사전 고정): {RULE_TEXT['a']}", "",
             "| 모델 | 구간 | 일수 | 평균 IC | NW t (lag) |", "|---|---|---|---|---|"]
    for model in L.MODELS:
        for row in report["a_predictive_power"]["ic"][model]:
            if "open_to_open" in row:
                s = row["open_to_open"]
                lines.append(f"| {model} | open(t+1)→open(t+{1 + row['h']}) | {s['days']} | {_fmt(s['mean_ic'])} | "
                             f"{_fmt(s['nw_t'], 2)} (lag {s['nw_lag']}) |")
            elif row["h"] == "gap_close_to_open":
                s = row["summary"]
                lines.append(f"| {model} | close(t)→open(t+1) 갭, 체결 불가 | {s['days']} | {_fmt(s['mean_ic'])} | "
                             f"{_fmt(s['nw_t'], 2)} (lag {s['nw_lag']}) |")
            else:
                s = row["summary"]
                lines.append(f"| {model} | open(t+1)→close(t+1) 장중 | {s['days']} | {_fmt(s['mean_ic'])} | "
                             f"{_fmt(s['nw_t'], 2)} (lag {s['nw_lag']}) |")
    lines.append("")
    return lines


def _section_b(report: Mapping[str, Any]) -> list[str]:
    lines = ["## b. 지속성 (순위 자기상관과 연속일 목록 겹침)", "",
             "- 왜: 목록이 자주 바뀌면 같은 신호라도 턴오버 비용을 많이 낸다.",
             "- 해석 규칙(사전 고정): " + RULE_TEXT["b"], "",
             "| 모델 | 지표 | 값 | 일수 |", "|---|---|---|---|"]
    for model in L.MODELS:
        block = report["b_persistence"][model]
        for key, value in block["rank_autocorr"].items():
            lines.append(f"| {model} | 순위 자기상관 {key} | {_fmt(value['mean_rank_corr'])} | {value['days']} |")
        for key, value in block["topk_overlap"].items():
            lines.append(f"| {model} | 연속일 top-{key[1:]} 겹침 비율 | {_fmt(value['mean_overlap'])} | {value['days']} |")
    lines.append("")
    return lines


def _section_c(report: Mapping[str, Any]) -> list[str]:
    cum = report["c_event_study"]["cumulative"]
    lines = ["## c. 사건 연구 (포트폴리오 백테스트 아님)", "",
             "- 왜: 점수 상위 종목이 신호 발생 후 며칠에 걸쳐 같은 날 동일가중 유니버스보다 얼마나 앞서는지 "
             "감쇠 곡선으로 본다. 비용 30bp와 60bp를 넘는 최소 기간을 찾는다.",
             f"- 해석 규칙(사전 고정): {RULE_TEXT['c']} 이벤트는 예측값 > 0.30%인 상위 k 종목이며, "
             "누적은 open(t+1)→open(t+1+h)이다. 날짜별 평균을 NW 검정 단위로 쓴다.", ""]
    for key in cum:
        rows = cum[key]
        first30 = report["c_event_study"]["first_horizon_above_30bp_t2"][key]
        first60 = report["c_event_study"]["first_horizon_above_60bp_t2"][key]
        lines += [f"### {key} (주 시도)" if key == report["c_event_study"]["primary"] else f"### {key}",
                  "", f"- 30bp·t>2 최소 h: {first30 if first30 is not None else '없음'}, "
                      f"60bp·t>2 최소 h: {first60 if first60 is not None else '없음'}",
                  "", "| h | 사건 수 | 일수 | 평균 누적 초과수익 | NW t | 30bp 초과·t>2 | 60bp 초과·t>2 |",
                  "|---|---|---|---|---|---|---|"]
        for row in rows:
            lines.append(f"| {row['h']} | {row['events']} | {row['days']} | {_fmt(row['mean_cum_excess'], 5)} | "
                         f"{_fmt(row['nw_t'], 2)} | {_fmt(row['above_30bp_t2'])} | {_fmt(row['above_60bp_t2'])} |")
        lines.append("")
    lines += ["### 엔진 기준 턴오버 효율 (영비용 총수익의 CAGR / 연간 턴오버)", "",
              "- 왜: 비용 전 신호가 턴오버 한 단위당 얼마나 남기는지 보고, 손익분기 편도 비용을 구한다.",
              "- 해석 규칙(사전 고정): 손익분기 편도 비용이 편도 15bp보다 작으면 비용 후 음수다. 턴오버는 "
              "엔진의 편도 체결 금액(cost_rate와 같은 단위)이다.", "",
              "| 시도 | 영비용 CAGR | 연간 턴오버(편도) | CAGR/턴오버 = 손익분기 편도 비용 | 산술 손익분기 편도 | 비용 후 총수익 |",
              "|---|---|---|---|---|---|"]
    for name, info in report["c_engine_efficiency"].items():
        lines.append(f"| {name} | {_fmt(info['gross_cagr'])} | {_fmt(info['annual_turnover_one_way'], 2)} | "
                     f"{_fmt(info['gross_cagr_per_annual_turnover'], 6)} | "
                     f"{_fmt(info['breakeven_one_way_cost_arithmetic'], 6)} | {_fmt(info['net_total_return'])} |")
    lines += [f"- 편도 비용 기준: {ONE_WAY_COST:.4f}", ""]
    return lines


def _section_d(report: Mapping[str, Any]) -> list[str]:
    lines = ["## d. 노출 귀속 (영비용 일별 수익 회귀)", "",
             "- 왜: 전략 수익이 시장(SPY)과 동일가중 유니버스, 또는 섹터 ETF 노출로 설명되는지 본다. "
             "무작위 선택은 같은 종목 수와 동일가중을 쓰는 기준선이다.",
             f"- 해석 규칙(사전 고정): {RULE_TEXT['d']} 무작위 기준선은 종목 수만 맞추며 섹터·베타는 맞추지 않는다.",
             "- 회귀: HAC(Bartlett, lag 5) 표준오차. 알파는 일 수익 기준 연환산(×252).", "",
             "| 시도 | 회귀 | 알파(연) | 알파 t | SPY β | EW β 또는 섹터 | R² |", "|---|---|---|---|---|---|---|"]
    for name, blocks in report["d_exposure"]["exposure"].items():
        for label, block in (("SPY+EW", blocks["spy_ew"]), ("SPY+섹터 ETF 7개", blocks["spy_sectors"])):
            coef = block["coef"]
            names = block["coef_names"]
            extra = ", ".join(f"{names[i]}={_fmt(coef[i], 3)}(t {_fmt(block['t'][i], 1)})"
                              for i in range(2, len(names)))
            lines.append(f"| {name} | {label} | {_fmt(block['alpha_annualized'])} | {_fmt(block['alpha_t'], 2)} | "
                         f"{_fmt(coef[1], 3)} | {extra} | {_fmt(block['r2'], 3)} |")
    lines += ["", "### 무작위 노출 일치 기준선 (seeds 0..49)", "",
              "| 시도 | 전략 영비용 일평균 | 무작위 평균 | 무작위 최소 | 무작위 최대 | 전략보다 낮은 seed 비중 |",
              "|---|---|---|---|---|---|"]
    for name, info in report["d_exposure"]["random_matched"].items():
        lines.append(f"| {name} | {_fmt(info['strategy_gross_daily'], 6)} | {_fmt(info['mean_gross_daily'], 6)} | "
                     f"{_fmt(info['min_gross_daily'], 6)} | {_fmt(info['max_gross_daily'], 6)} | "
                     f"{_fmt(info['share_of_seeds_below_strategy'], 2)} |")
    lines.append("")
    return lines


def _section_e(report: Mapping[str, Any]) -> list[str]:
    lines = ["## e. 생존편향 민감도 (수익률 구간별, 연대별)", "",
             "- 왜: 오늘 생존 종목만 있는 유니버스에서는 크게 떨어진 뒤의 반등이 과대평가될 수 있다. 계속 떨어져 "
             "상장폐지된 종목은 표본에 없기 때문이다. 이 편향의 크기는 상장폐지 데이터 없이 정량화할 수 없다.",
             f"- 해석 규칙(사전 고정): {RULE_TEXT['e']} 구간 기준은 pick의 당일 수익률(종가 t-1→t)이다. 성과는 1일 초과수익"
             "(시가 t+1→t+2 수익에서 같은 날 EW 수익을 뺀 값)으로 본다. 구간 경계: 하한 포함, 상한 미포함이며 "
             "0%는 -2%..0% 구간에 든다.", ""]
    for key, rows in report["e_survivorship"]["buckets"].items():
        lines += [f"### {key} 구간", "", "| 구간 | 건수 | 총 초과수익 중 비중 | 1일 평균 초과수익 |", "|---|---|---|---|"]
        for row in rows:
            lines.append(f"| {row['bucket']} | {row['count']} | {_fmt(row['share_of_total_excess'], 3)} | "
                         f"{_fmt(row['mean_1day_excess'], 5)} |")
        lines.append("")
    for key, rows in report["e_survivorship"]["decades"].items():
        lines += [f"### {key} 연대별", "", "| 기간 | 건수 | 1일 평균 초과수익 |", "|---|---|---|"]
        for row in rows:
            lines.append(f"| {row['period']} | {row['events']} | {_fmt(row['mean_1day_excess'], 5)} |")
        lines.append("")
    lines.append("- 생존편향은 이 데이터로 정량화할 수 없다. 위 수치는 방향성 참고용이다.")
    lines.append("")
    return lines


def _section_f(report: Mapping[str, Any]) -> list[str]:
    v = report["f_verdict"]
    lines = ["## f. 판정 (사전 규칙 기계 적용)", "", f"- 규칙: {v['rule']}",
             f"- 30bp·t>2를 넘는 h: {v['qualifying_horizons'] or '없음'}",
             f"- 알파 t(SPY+EW, {report['primary_trial']}): {_fmt(v['alpha_t'], 2)}",
             f"- **판정: {v['status']}**",
             f"- 판정 규칙 밖의 참고 수치(판정에 쓰지 않음): SPY+섹터 ETF 회귀 알파 t = "
             f"{_fmt(report['d_exposure']['exposure'][report['primary_trial']]['spy_sectors']['alpha_t'], 2)}"]
    if v["reasons"]:
        lines.append("- 사유: " + "; ".join(v["reasons"]))
    lines.append("")
    return lines


# ---------------------------------------------------------------- CLI


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m app.research.leadlag_signal",
                                     description="LL-1 signal diagnostics (read-only; no orders).")
    parser.add_argument("--data", required=True, type=Path, help="derived dataset (leadlag-tr)")
    parser.add_argument("--universe-rule", choices=L.UNIVERSE_RULES, default=L.RULE_POINT_IN_TIME)
    parser.add_argument("--reference-report", type=Path,
                        help="optional LL-1 report JSON to reconcile the engine net totals against")
    parser.add_argument("--out", required=True, type=Path, help="canonical JSON path")
    parser.add_argument("--markdown", type=Path, help="Korean markdown path")
    args = parser.parse_args(argv)
    try:
        reference = json.loads(args.reference_report.read_text(encoding="utf-8")) if args.reference_report else None
        report = run_diagnostics(args.data, args.universe_rule, reference)
    except (ResearchError, OSError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(canonical_json(report, indent=2) + "\n", encoding="utf-8")
    if args.markdown is not None:
        args.markdown.parent.mkdir(parents=True, exist_ok=True)
        args.markdown.write_text(render_markdown(report), encoding="utf-8")
    print(f"verdict: {report['f_verdict']['status']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
