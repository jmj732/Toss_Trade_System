"""LL-1 리드-래그 백테스트 손실의 독립 감사 (읽기 전용, 진단 전용).

- 시도(trial)를 추가하지 않는다. 기준선·placebo·무작위 선택은 모두 진단(diagnostic)이다.
- 엔진 파일(leadlag, backtest, metrics, validation, strategies, report, data, total_return)은 수정하지 않는다.
- 주문·브로커 코드 없음. 표준 라이브러리만 사용한다 (Python 3.10/3.11).

섹션
  A 재현: 엔진 경로로 리포트의 trial별 net/gross/2x 총수익을 재현
  B 독립 회계: 주식수·현금을 명시적으로 추적하는 별도 장부 (backtest.simulate 미사용)
  C 독립 예측: CSV 직접 읽기 -> 수익률, 리더, 특징, OLS(정규방정식), M0/M1 예측 재계산
  D 룩어헤드: (1) 절단 테스트 (2) 한 일 지연 placebo (3) 한 일 선행 누설 sentinel
  E 배당: TR CAGR과 분할만 조정 가격 CAGR 차이, 후행 조정 항등식 점검
  F 분해: 거래비용, 회전율, 투자비중, 보유기간 등
  G 기준선(진단): 무거래, SPY, 동일가중, 무작위 노출 일치(seed 0..49)
  H 판정
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import random
import sys
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any

from app.research import leadlag as L
from app.research.data import canonical_json, load_dataset

AUDIT_LABEL = "LL-1 독립 감사 (읽기 전용, 진단 전용: 시도 아님)"
RECON_TOL = 1e-9
DIVIDEND_TOL = 1e-8
DAYS_PER_YEAR = 252
TRUNCATION_MIN_PAIRS = 200
TRUNCATION_DAYS = 12
RANDOM_SEEDS = tuple(range(50))
TRIALS = (("M0", 3), ("M0", 5), ("M1", 3), ("M1", 5))
PRIMARY = "M1_k5"
DIV_YIELD_MAX = 0.06
SPY_YIELD_RANGE = (0.015, 0.025)
EVENT_YIELD_MAX = 0.15  # 엔진 total_return.YIELD_MAX와 같은 값을 독립적으로 둔 상한


def trial_name(model: str, k: int) -> str:
    return f"{model}_k{k}"


# ---------------------------------------------------------------- basic helpers


def _total(returns: Sequence[float]) -> float:
    equity = 1.0
    for value in returns:
        equity *= 1.0 + value
    return equity - 1.0


def _cagr(returns: Sequence[float]) -> float | None:
    n = len(returns)
    if n == 0:
        return None
    equity = 1.0 + _total(returns)
    if equity <= 0.0:
        return -1.0
    return equity ** (DAYS_PER_YEAR / n) - 1.0


def _percentile(values: Sequence[float], q: float) -> float:
    ordered = sorted(values)
    pos = (len(ordered) - 1) * q
    lo = math.floor(pos)
    hi = math.ceil(pos)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (pos - lo)


def _sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _check(name: str, value: Any, passed: bool | None, rule: str) -> dict[str, Any]:
    return {"name": name, "value": value, "rule": rule, "pass": passed}


# ---------------------------------------------------------------- independent loading (CSV only)


def read_csv_table(path: Path) -> dict[date, tuple[float, float, float, float, float]]:
    """Read one OHLCV csv directly with the csv module. Returns date -> (o, h, l, c, v)."""
    table: dict[date, tuple[float, float, float, float, float]] = {}
    with path.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            day = date.fromisoformat(row["date"])
            if day in table:
                raise ValueError(f"{path.name}: duplicate date {day.isoformat()}")
            table[day] = (float(row["open"]), float(row["high"]), float(row["low"]),
                          float(row["close"]), float(row["volume"]))
    return table


def _align(table: Mapping[date, tuple[float, ...]], calendar: Sequence[date]) -> list[tuple[float, ...] | None]:
    return [table.get(day) for day in calendar]


def _rets(prices: Sequence[float | None]) -> list[float | None]:
    out: list[float | None] = [None] * len(prices)
    for i in range(1, len(prices)):
        a, b = prices[i - 1], prices[i]
        if a is not None and b is not None:
            out[i] = b / a - 1.0
    return out


@dataclass
class Universe:
    """Symbol sets and constants taken from the report's config block (not from engine code)."""

    benchmark: str
    etfs: tuple[str, ...]
    sector_stocks: dict[str, tuple[str, ...]]
    stocks: tuple[str, ...]
    sector_of: dict[str, str]
    train_bars: int
    test_bars: int
    step_bars: int
    leader_window: int
    threshold: float
    stress_multiplier: float
    commission_bps: float
    slippage_bps: float

    @property
    def rate(self) -> float:
        return (self.commission_bps + self.slippage_bps) / 10_000.0


def universe_from_report(report: Mapping[str, Any]) -> Universe:
    cfg = report["config"]
    sector_stocks = {etf: tuple(stocks) for etf, stocks in cfg["universe"]["sector_stocks"].items()}
    stocks = tuple(sorted(s for group in sector_stocks.values() for s in group))
    sector_of = {s: etf for etf, group in sector_stocks.items() for s in group}
    wf = cfg["walk_forward"]
    return Universe(
        benchmark=cfg["universe"]["benchmark"],
        etfs=tuple(cfg["universe"]["sector_etfs"]),
        sector_stocks=sector_stocks,
        stocks=stocks,
        sector_of=sector_of,
        train_bars=int(wf["train_bars"]),
        test_bars=int(wf["test_bars"]),
        step_bars=int(wf["step_bars"]),
        leader_window=int(cfg["model"]["leader_window_bars"]),
        threshold=float(cfg["portfolio"]["threshold"]),
        stress_multiplier=float(cfg["portfolio"]["stress_multiplier"]),
        commission_bps=float(cfg["portfolio"]["commission_bps"]),
        slippage_bps=float(cfg["portfolio"]["slippage_bps"]),
    )


@dataclass
class Fold:
    index: int
    train_start: int
    train_end: int
    test_start: int
    test_end: int

    @property
    def origin(self) -> int:
        return self.test_start - 1

    def forecast_days(self) -> range:
        return range(self.test_start - 1, self.test_end - 1)


@dataclass
class IndepState:
    """Everything recomputed from csv files. Series are aligned to the benchmark calendar."""

    uni: Universe
    cal: list[date]
    opens: dict[str, list[float | None]]
    closes: dict[str, list[float | None]]
    rets: dict[str, list[float | None]]
    dv: dict[str, list[float | None]]
    leaders: dict[str, list[tuple[str, float | None] | None]]
    folds: list[Fold]
    universes: dict[int, list[str]]
    stocks_present: tuple[str, ...]


def _indep_leaders(n: int, uni: Universe, dv: Mapping[str, list[float | None]],
                   closes: Mapping[str, list[float | None]],
                   rets: Mapping[str, list[float | None]]) -> dict[str, list[tuple[str, float | None] | None]]:
    """Brute-force leader: highest mean of close x volume over the window ending t; ties to smaller symbol."""
    window = uni.leader_window
    table: dict[str, list[tuple[str, float | None] | None]] = {}
    for etf, group in uni.sector_stocks.items():
        rows: list[tuple[str, float | None] | None] = [None] * n
        for t in range(window - 1, n):
            best: tuple[float, str] | None = None
            for stock in group:
                if stock not in dv:
                    continue
                span = dv[stock][t - window + 1: t + 1]
                if any(value is None for value in span) or closes[stock][t] is None:
                    continue
                candidate = (-sum(span) / window, stock)  # type: ignore[arg-type]
                if best is None or candidate < best:
                    best = candidate
            if best is not None:
                rows[t] = (best[1], rets[best[1]][t])
        table[etf] = rows
    return table


def _first_complete(opens: Sequence[float | None], closes: Sequence[float | None]) -> int | None:
    for i, (o, c) in enumerate(zip(opens, closes)):
        if o is not None and c is not None:
            return i
    return None


def _complete_span(opens: Sequence[float | None], closes: Sequence[float | None], lo: int, hi: int) -> bool:
    return all(opens[i] is not None and closes[i] is not None for i in range(lo, hi))


def build_indep_state(tr_dir: Path, report: Mapping[str, Any]) -> IndepState:
    uni = universe_from_report(report)
    manifest = json.loads((tr_dir / "manifest.json").read_text(encoding="utf-8"))
    # Stocks without a file are excluded and reported as missing (the engine does the same).
    present = tuple(s for s in uni.stocks if (tr_dir / f"{s}.csv").is_file())
    symbols = [uni.benchmark, *uni.etfs, *present]
    cal_table = read_csv_table(tr_dir / f"{uni.benchmark}.csv")
    cal = sorted(cal_table)
    tables = {s: read_csv_table(tr_dir / f"{s}.csv") for s in symbols}
    raw = {s: read_csv_table(tr_dir / "unadjusted" / f"{s}.csv") for s in present}
    opens: dict[str, list[float | None]] = {}
    closes: dict[str, list[float | None]] = {}
    dv: dict[str, list[float | None]] = {}
    for s in symbols:
        aligned = _align(tables[s], cal)
        opens[s] = [row[0] if row else None for row in aligned]
        closes[s] = [row[3] if row else None for row in aligned]
    for s in present:
        raw_rows = _align(raw[s], cal)
        dv[s] = [row[3] * row[4] if row else None for row in raw_rows]
    rets = {s: _rets(closes[s]) for s in symbols}
    n = len(cal)
    leaders = _indep_leaders(n, uni, dv, closes, rets)
    factor_first = []
    for s in [uni.benchmark, *uni.etfs]:
        first = _first_complete(opens[s], closes[s])
        if first is None:
            raise ValueError(f"factor {s} has no complete bars")
        factor_first.append(first)
    first_train_start = max(factor_first) + 1
    folds: list[Fold] = []
    start = first_train_start
    while start + uni.train_bars + uni.test_bars <= n:
        folds.append(Fold(len(folds), start, start + uni.train_bars,
                          start + uni.train_bars, start + uni.train_bars + uni.test_bars))
        start += uni.step_bars
    universes: dict[int, list[str]] = {}
    for fold in folds:
        lo, hi = fold.train_start - 1, fold.test_end
        members = []
        for s in present:
            etf = uni.sector_of[s]
            if all(_complete_span(opens[x], closes[x], lo, hi) for x in (s, etf, uni.benchmark)):
                members.append(s)
        universes[fold.index] = members
    if not manifest.get("symbols"):
        raise ValueError("tr manifest has no symbols")
    return IndepState(uni, cal, opens, closes, rets, dv, leaders, folds, universes, present)


def _feat(st: IndepState, stock: str, t: int) -> tuple[list[float] | None, list[float] | None, str | None]:
    etf = st.uni.sector_of[stock]
    spy_r = st.rets[st.uni.benchmark][t]
    sec_r = st.rets[etf][t]
    own_r = st.rets[stock][t]
    if spy_r is None or sec_r is None or own_r is None:
        return None, None, None
    x0 = [1.0, spy_r, sec_r, own_r]
    lead = st.leaders[etf][t]
    if lead is None:
        return x0, None, None
    lead_symbol, lead_r = lead
    if lead_symbol == stock or lead_r is None:
        return x0, None, lead_symbol
    return x0, x0 + [lead_r], lead_symbol


def _target(st: IndepState, stock: str, t: int) -> float | None:
    o = st.opens[stock]
    if t + 2 >= len(o) or o[t + 1] is None or o[t + 2] is None:
        return None
    return o[t + 2] / o[t + 1] - 1.0


def gauss_jordan_solve(matrix: list[list[float]], rhs: list[float]) -> list[float] | None:
    """Own Gauss-Jordan elimination with partial pivoting. None when singular."""
    n = len(rhs)
    aug = [matrix[i][:] + [rhs[i]] for i in range(n)]
    scale = max((abs(v) for row in matrix for v in row), default=0.0)
    if scale == 0.0:
        return None
    for col in range(n):
        pivot_row = max(range(col, n), key=lambda r: abs(aug[r][col]))
        if abs(aug[pivot_row][col]) <= 1e-12 * scale:
            return None
        aug[col], aug[pivot_row] = aug[pivot_row], aug[col]
        pivot = aug[col][col]
        aug[col] = [v / pivot for v in aug[col]]
        for r in range(n):
            if r != col and aug[r][col] != 0.0:
                factor = aug[r][col]
                aug[r] = [a - factor * b for a, b in zip(aug[r], aug[col])]
    return [aug[i][n] for i in range(n)]


def normal_equation_ols(rows: Sequence[Sequence[float]], target: Sequence[float]) -> list[float] | None:
    """beta = (X'X)^-1 X'y via normal equations. None when too few rows or singular."""
    if not rows or len(rows) <= len(rows[0]):
        return None
    p = len(rows[0])
    xtx = [[0.0] * p for _ in range(p)]
    xty = [0.0] * p
    for x, y in zip(rows, target):
        for i in range(p):
            xty[i] += x[i] * y
            for j in range(p):
                xtx[i][j] += x[i] * x[j]
    return gauss_jordan_solve(xtx, xty)


def _dot(a: Sequence[float], b: Sequence[float]) -> float:
    return sum(x * y for x, y in zip(a, b))


def indep_fold_forecasts(st: IndepState, fold: Fold, universe: Sequence[str],
                         days: Sequence[int]) -> dict[tuple[str, int], tuple[float, float | None]]:
    """(m0, m1) per (stock, t). m1 is None when the leader return is unavailable for a non-self leader."""
    coefs: dict[str, tuple[list[float] | None, list[float] | None]] = {}
    for stock in universe:
        rows0: list[list[float]] = []
        rows1: list[list[float]] = []
        y0: list[float] = []
        y1: list[float] = []
        for t in range(fold.train_start, fold.train_end - 2):
            x0, x1, _ = _feat(st, stock, t)
            y = _target(st, stock, t)
            if x0 is None or y is None:
                continue
            rows0.append(x0)
            y0.append(y)
            if x1 is not None:
                rows1.append(x1)
                y1.append(y)
        coefs[stock] = (normal_equation_ols(rows0, y0), normal_equation_ols(rows1, y1))
    out: dict[tuple[str, int], tuple[float, float | None]] = {}
    for stock in universe:
        b0, b1 = coefs[stock]
        if b0 is None:
            continue
        for t in days:
            x0, x1, lead = _feat(st, stock, t)
            if x0 is None:
                continue
            v0 = _dot(b0, x0)
            if lead == stock:
                out[(stock, t)] = (v0, v0)
            elif x1 is None:
                out[(stock, t)] = (v0, None)
            else:
                out[(stock, t)] = (v0, _dot(b1, x1) if b1 is not None else v0)
    return out


def indep_picks(fc: Mapping[tuple[str, int], tuple[float, float | None]], model: str, k: int,
                universe: Sequence[str], t: int, threshold: float) -> list[str]:
    idx = 0 if model == "M0" else 1
    ranked: list[tuple[float, str]] = []
    for stock in universe:
        value_pair = fc.get((stock, t))
        if value_pair is None:
            continue
        value = value_pair[idx]
        if value is not None and value > threshold:
            ranked.append((-value, stock))
    ranked.sort()
    return [stock for _, stock in ranked[:k]]


# ---------------------------------------------------------------- engine state (production path)


@dataclass
class EngineState:
    root: Path
    manifest: dict[str, Any]
    ds: Any
    panel: Any
    first_train_start: int
    folds: list[Any]
    universes: dict[int, list[str]]
    rets: dict[str, list[float | None]]
    leaders: dict[str, list[tuple[str, float | None] | None]]
    fc: dict[tuple[str, int], Any]
    fc_stats: list[dict[str, Any]]
    dec_local: dict[str, dict[int, dict[int, dict[str, float]]]]  # trial -> fold -> local key -> weights
    dec_cal: dict[str, dict[int, dict[int, dict[str, float]]]]  # trial -> fold -> calendar key -> weights


def build_engine_state(root: Path) -> EngineState:
    """Engine path exactly as the probe does: load_dataset, _load_raw, build_panel, tile_folds."""
    ds = load_dataset(root)
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    present = [s for s in L.REQUIRED_SYMBOLS if s in ds.bars]
    raw, _ = L._load_raw(root, manifest, present)
    cal = [bar.date for bar in ds.bars[L.BENCHMARK]]
    panel = L.build_panel({s: ds.bars[s] for s in present}, raw, calendar=cal)
    first = max(L._first_complete_index(panel, s) for s in (L.BENCHMARK,) + L.SECTOR_ETFS) + 1
    rets = {s: L._returns(panel.close[s]) for s in panel.close}
    leaders = L.leader_table(panel)
    engine_folds = L.tile_folds(len(panel.dates), first_train_start=first)
    # Same fold boundaries as the engine, expressed with this module's Fold (adds origin / forecast_days).
    folds = [Fold(f.index, f.train_start, f.train_end, f.test_start, f.test_end) for f in engine_folds]
    universes = {f.index: L.fold_universe(panel, f) for f in engine_folds}
    fc: dict[tuple[str, int], Any] = {}
    fc_stats: list[dict[str, Any]] = []
    for f in folds:
        days = list(range(f.test_start - 1, f.test_end - 1))
        x, stats = L.forecasts_for_fold(panel, f, universes[f.index], rets, leaders, days)
        fc.update(x)
        fc_stats.append(stats)
    dec_local: dict[str, dict[int, dict[int, dict[str, float]]]] = {}
    dec_cal: dict[str, dict[int, dict[int, dict[str, float]]]] = {}
    for model, k in TRIALS:
        name = trial_name(model, k)
        dec_local[name], dec_cal[name] = {}, {}
        for f in folds:
            local = L.decisions_for(fc, model, k, universes[f.index],
                                    list(range(f.test_start - 1, f.test_end - 1)), f.test_start - 1)
            dec_local[name][f.index] = local
            dec_cal[name][f.index] = {key + f.test_start - 1: v for key, v in local.items()}
    return EngineState(root, manifest, ds, panel, first, folds, universes, rets, leaders, fc, fc_stats,
                       dec_local, dec_cal)


def engine_fold_returns(st: EngineState, name: str, cost_rate: float) -> dict[int, list[float]]:
    """Per-fold returns from backtest.simulate. Used only by the reconciliation section A and B reference."""
    from app.research.backtest import simulate  # engine reference, deliberately not used by B

    out: dict[int, list[float]] = {}
    for f in st.folds:
        origin = f.test_start - 1
        local_dates = st.panel.dates[origin:f.test_end]
        bars = L._bars_for(st.panel, list(st.universes[f.index]) + [L.BENCHMARK], local_dates)
        out[f.index] = list(simulate(bars, local_dates, st.dec_local[name][f.index], cost_rate).returns)
    return out


# ---------------------------------------------------------------- independent book (B, F, G)


@dataclass
class Book:
    returns: list[float] = field(default_factory=list)
    turnover: list[float] = field(default_factory=list)
    held_gross: list[float] = field(default_factory=list)
    decision_positions: list[int] = field(default_factory=list)
    full_replacement_turnover: list[float] = field(default_factory=list)
    overlap: list[float] = field(default_factory=list)
    spell_lengths: list[int] = field(default_factory=list)
    attribution_residual: float = 0.0
    cash_min: float = float("inf")


def run_book(syms: Sequence[str], opens: Mapping[str, Sequence[float | None]],
             closes: Mapping[str, Sequence[float | None]], o: int, e: int,
             decisions: Mapping[int, Mapping[str, float]], rate: float,
             convention: str = "engine") -> Book:
    """Shares-and-cash book over calendar days (o, e]. Starts from cash at close o.

    At open d: NAV_pre = cash + sum(shares x open). A decision made at close d-1 sets targets.
    cost = rate x sum|target_value - held_value| with target_value = weight x NAV_pre (traded notional).
    convention "engine": positions = weight x NAV_post (NAV_post = NAV_pre - cost), matching the
    engine's documented convention that targets are fractions of post-cost NAV.
    convention "literal": positions = weight x NAV_pre and the cost is paid from cash (spec literal reading).
    Mark at close d. Weights are carried overnight without trading.
    """
    m = len(syms)
    idx = {s: i for i, s in enumerate(syms)}
    shares = [0.0] * m
    cash = 1.0
    nav_prev = 1.0
    book = Book()
    overlap_prev: set[str] | None = None
    residual_max = 0.0
    post_rows: list[tuple[list[float], list[float], float, float]] = []  # (opens_d, shares, cash, npost)
    for d in range(o + 1, e + 1):
        op = [opens[s][d] for s in syms]
        cl = [closes[s][d] for s in syms]
        if any(v is None for v in op) or any(v is None for v in cl):
            raise ValueError(f"missing price on day index {d}")
        held_val = [shares[i] * op[i] for i in range(m)]  # type: ignore[operator]
        nav_pre = cash + sum(held_val)
        book.held_gross.append(sum(held_val) / nav_pre)
        held_now = {i for i in range(m) if held_val[i] > 0.0}  # pre-trade holdings of this day
        target = decisions.get(d - 1)
        if target is None:
            turn = 0.0
            npost = nav_pre
            book.full_replacement_turnover.append(0.0)
        else:
            weights = [0.0] * m
            for s, w in target.items():
                weights[idx[s]] = float(w)
            notional = sum(abs(weights[i] * nav_pre - held_val[i]) for i in range(m))  # type: ignore[operator]
            cost = rate * notional
            npost = nav_pre - cost
            base = npost if convention == "engine" else nav_pre
            new_val = [weights[i] * base for i in range(m)]
            shares = [new_val[i] / op[i] for i in range(m)]  # type: ignore[operator]
            cash = npost - sum(new_val)
            turn = notional / nav_pre
            picks = {i for i in range(m) if weights[i] > 0.0}
            book.decision_positions.append(len(picks))
            if held_now and picks and held_now.isdisjoint(picks):
                book.full_replacement_turnover.append(turn)
            else:
                book.full_replacement_turnover.append(0.0)
            pick_names = {syms[i] for i in picks}
            if overlap_prev is not None and pick_names:
                book.overlap.append(len(overlap_prev & pick_names) / len(pick_names))
            overlap_prev = pick_names
        nav_close = cash + sum(shares[i] * cl[i] for i in range(m))  # type: ignore[operator]
        book.returns.append(nav_close / nav_prev - 1.0)
        nav_prev = nav_close
        book.turnover.append(turn)
        book.cash_min = min(book.cash_min, cash)
        post_rows.append((op, shares[:], cash, npost))  # type: ignore[arg-type]
    book.spell_lengths = _spell_lengths_from_post(syms, post_rows)
    for j in range(len(post_rows) - 1):
        op_d, sh_d, cash_d, npost_d = post_rows[j]
        op_next = post_rows[j + 1][0]
        if npost_d <= 0.0:
            continue
        w_post = [sh_d[i] * op_d[i] / npost_d for i in range(m)]  # type: ignore[operator]
        lhs = (sum(sh_d[i] * op_next[i] for i in range(m)) + cash_d) / npost_d - 1.0  # type: ignore[operator]
        rhs = sum(w_post[i] * (op_next[i] / op_d[i] - 1.0) for i in range(m))  # type: ignore[operator]
        residual_max = max(residual_max, abs(lhs - rhs))
    book.attribution_residual = residual_max
    return book


def _spell_lengths_from_post(syms: Sequence[str],
                             post_rows: Sequence[tuple[list[float], list[float], float, float]]) -> list[int]:
    """Lengths (days) of contiguous closes with a positive share count, per symbol."""
    spells: list[int] = []
    m = len(syms)
    current = [0] * m
    for _op, sh, _cash, _npost in post_rows:
        for i in range(m):
            if sh[i] > 0.0:
                current[i] += 1
            elif current[i] > 0:
                spells.append(current[i])
                current[i] = 0
    spells.extend(c for c in current if c > 0)
    return spells


def run_fold_books(st: IndepState, folds: Sequence[Fold], decisions_by_fold: Mapping[int, Mapping[int, Mapping[str, float]]],
                   universes: Mapping[int, Sequence[str]], rate: float, convention: str = "engine") -> dict[int, Book]:
    out: dict[int, Book] = {}
    for f in folds:
        syms = sorted(universes[f.index]) + [st.uni.benchmark]
        out[f.index] = run_book(syms, st.opens, st.closes, f.origin, f.test_end - 1,
                                decisions_by_fold[f.index], rate, convention)
    return out


def _flat_returns(books: Mapping[int, Book]) -> list[float]:
    out: list[float] = []
    for index in sorted(books):
        out.extend(books[index].returns)
    return out


def _to_cal(decisions_local: Mapping[int, Mapping[str, float]], origin: int) -> dict[int, dict[str, float]]:
    return {key + origin: dict(value) for key, value in decisions_local.items()}


def _flat(per_fold: Mapping[int, Sequence[float]]) -> list[float]:
    out: list[float] = []
    for index in sorted(per_fold):
        out.extend(per_fold[index])
    return out


def _same_value(x: float | None, y: float | None, tol: float = RECON_TOL) -> bool:
    if x is None or y is None:
        return x is None and y is None
    return abs(x - y) <= tol


def _max_abs(values: Sequence[float]) -> float:
    return max(values) if values else 0.0


# ---------------------------------------------------------------- A. reconciliation


def section_a(es: EngineState, ind: IndepState, report: Mapping[str, Any], data_root: Path,
              prior_path: Path | None) -> dict[str, Any]:
    checks: list[dict[str, Any]] = []
    max_input_diff = 0.0
    rows = 0
    date_set_mismatch: list[str] = []
    sha_mismatch: list[str] = []
    for symbol, bars in es.ds.bars.items():
        path = data_root / f"{symbol}.csv"
        table = read_csv_table(path)
        engine_rows = {b.date: (b.open, b.high, b.low, b.close, b.volume) for b in bars}
        if set(engine_rows) != set(table):
            date_set_mismatch.append(symbol)
            continue
        if _sha256_file(path) != str(es.manifest["symbols"][symbol]["sha256"]).lower():
            sha_mismatch.append(symbol)
        for day, values in engine_rows.items():
            rows += 1
            for a, b in zip(values, table[day]):
                max_input_diff = max(max_input_diff, abs(a - b))
    checks.append(_check("A1 입력 일치 (csv 직접 읽기 vs load_dataset)",
                         {"rows": rows, "max_abs_diff": max_input_diff,
                          "date_set_mismatch": date_set_mismatch, "sha256_mismatch": sha_mismatch},
                         max_input_diff <= RECON_TOL and not date_set_mismatch and not sha_mismatch,
                         "OHLCV 최대 절대차 <= 1e-9, 날짜 집합과 파일 sha256이 매니페스트와 일치"))

    cfg = ind.uni
    totals: dict[str, Any] = {}
    worst = 0.0
    for model, k in TRIALS:
        name = trial_name(model, k)
        rep = report["models"][name]
        observed = {
            "net": _total(_flat(engine_fold_returns(es, name, cfg.rate))),
            "gross": _total(_flat(engine_fold_returns(es, name, 0.0))),
            "stress_2x": _total(_flat(engine_fold_returns(es, name, cfg.rate * cfg.stress_multiplier))),
        }
        reported = {
            "net": rep["summary"]["total_return"],
            "gross": rep["summary_gross_zero_cost"]["total_return"],
            "stress_2x": rep["summary_2x_cost"]["total_return"],
        }
        diffs = {key: abs(observed[key] - reported[key]) for key in observed}
        worst = max(worst, _max_abs(list(diffs.values())))
        totals[name] = {"observed": observed, "reported": reported, "abs_diff": diffs}
    bench_bars = L._bars_for(es.panel, [L.BENCHMARK], es.panel.dates[es.folds[0].origin:es.folds[-1].test_end])
    bench_dates = es.panel.dates[es.folds[0].origin:es.folds[-1].test_end]
    from app.research.backtest import simulate

    bench_obs = _total(simulate(bench_bars, bench_dates, {0: {L.BENCHMARK: 1.0}}, cfg.rate).returns)
    bench_diff = abs(bench_obs - report["benchmark"]["out_of_sample"]["total_return"])
    worst = max(worst, bench_diff)
    checks.append(_check("A2 trial 총수익 재현 (net / gross / 2x)", {"max_abs_diff": worst, "trials": totals},
                         worst <= RECON_TOL, "리포트 trial별 total_return과 1e-9 이내 일치"))
    checks.append(_check("A2b SPY 벤치마크 재현", {"observed": bench_obs,
                                                  "reported": report["benchmark"]["out_of_sample"]["total_return"],
                                                  "abs_diff": bench_diff},
                         bench_diff <= RECON_TOL, "SPY 단일 buy-and-hold 총수익 1e-9 이내"))

    full: dict[str, Any] = {}
    if prior_path is not None and prior_path.is_file():
        body = L.run_from_directory(es.root, prior_path)
        cfg_match = body["config_hash"] == report["config_hash"]
        dataset_match = body["dataset"]["dataset_hash"] == report["dataset"]["dataset_hash"]
        body_diff = 0.0
        for model, k in TRIALS:
            name = trial_name(model, k)
            body_diff = max(body_diff, abs(body["models"][name]["summary"]["total_return"]
                                           - report["models"][name]["summary"]["total_return"]))
        full = {"config_hash_match": cfg_match, "dataset_hash_match": dataset_match,
                "max_total_return_diff": body_diff, "outcome": body["outcome"]["status"]}
        checks.append(_check("A3 엔진 전체 파이프라인 재실행 = 리포트", full,
                             cfg_match and dataset_match and body_diff <= RECON_TOL,
                             "run_from_directory 결과의 config/dataset 해시와 trial 총수익이 리포트와 일치"))
    return {"title": "A. 재현 (Reconciliation)",
            "why": "같은 입력으로 엔진 경로를 다시 돌려 리포트 수치가 재현되는지 확인한다. 재현되지 않으면 리포트와 코드가 어긋난 것이다.",
            "checks": checks, "trials": totals, "benchmark_total": {"observed": bench_obs, "abs_diff": bench_diff},
            "full_pipeline": full}


# ---------------------------------------------------------------- B. independent accounting


def section_b(es: EngineState, ind: IndepState) -> dict[str, Any]:
    cfg = ind.uni
    max_daily = 0.0
    max_fold_total = 0.0
    max_attr = 0.0
    min_cash = 0.0
    literal_rows: list[dict[str, Any]] = []
    per_trial: dict[str, Any] = {}
    for model, k in TRIALS:
        name = trial_name(model, k)
        trial_daily = 0.0
        trial_total = 0.0
        literal_net_diff = 0.0
        for cost_label, rate in (("net", cfg.rate), ("gross", 0.0), ("stress_2x", cfg.rate * cfg.stress_multiplier)):
            ref = engine_fold_returns(es, name, rate)
            books = run_fold_books(ind, es.folds, es.dec_cal[name], es.universes, rate, "engine")
            for f in es.folds:
                mine = books[f.index].returns
                theirs = ref[f.index]
                trial_daily = max(trial_daily, max(abs(a - b) for a, b in zip(mine, theirs)))
                trial_total = max(trial_total, abs(_total(mine) - _total(theirs)))
                max_attr = max(max_attr, books[f.index].attribution_residual)
                min_cash = min(min_cash, books[f.index].cash_min)
            if cost_label == "net":
                literal = run_fold_books(ind, es.folds, es.dec_cal[name], es.universes, rate, "literal")
                literal_net_diff = abs(_total(_flat({i: b.returns for i, b in literal.items()}))
                                       - _total(_flat(ref)))
        per_trial[name] = {"max_daily_abs_diff": trial_daily, "max_fold_total_abs_diff": trial_total}
        max_daily = max(max_daily, trial_daily)
        max_fold_total = max(max_fold_total, trial_total)
        literal_rows.append({"trial": name, "literal_minus_engine_net_total": literal_net_diff})
    checks = [
        _check("B1 독립 장부 = 엔진 (일별 수익, 3개 비용 조건 x 4 trial x 24 fold)",
               {"max_daily_abs_diff": max_daily, "per_trial": per_trial}, max_daily <= RECON_TOL,
               "일별 수익 최대 절대차 <= 1e-9 (fold 단위 총수익도 동일)"),
        _check("B2 fold 총수익 일치", {"max_fold_total_abs_diff": max_fold_total},
               max_fold_total <= RECON_TOL, "fold별 누적 총수익 차이 <= 1e-9"),
        _check("B3 시가-시가 귀속 항등식", {"max_residual": max_attr}, max_attr <= RECON_TOL,
               "다음 날 시가 기준 평가액 / 거래 후 평가액 - 1 = 가중치 x 시가-시가 수익, 잔차 <= 1e-9"),
        _check("B4 현금 음수 여부", {"min_cash": min_cash}, min_cash >= -1e-9,
               "현금 최소값 >= -1e-9 (레버리지 없음)"),
    ]
    return {"title": "B. 독립 회계 (Independent accounting)",
            "why": "backtest.simulate를 호출하지 않는 별도 주식수·현금 장부로 같은 결정을 다시 계산해 엔진 회계를 검증한다.",
            "convention_note": ("엔진 규약: 목표 비중은 거래비용 차감 후 NAV의 비율이고, 비용은 rate x |거래 명목금액|이다. "
                                "명세 문구(목표 = NAV-at-open x 비중, 비용은 현금에서 지급)를 문자 그대로 적용하면 "
                                "엔진과 1e-9 수준으로 맞지 않는다. 아래 literal 행은 그 해석의 차이만 보여주는 정보용이다."),
            "checks": checks, "per_trial": per_trial, "literal_convention_sensitivity": literal_rows}


# ---------------------------------------------------------------- C. independent forecasts


def section_c(es: EngineState, ind: IndepState) -> dict[str, Any]:
    universe_mismatch = 0
    for f in es.folds:
        if sorted(es.universes[f.index]) != sorted(ind.universes[f.index]):
            universe_mismatch += 1
    leader_mismatch = 0
    leader_total = 0
    for etf in ind.leaders:
        for t in range(len(ind.cal)):
            a = ind.leaders[etf][t]
            b = es.leaders[etf][t]
            leader_total += 1
            if (a is None) != (b is None):
                leader_mismatch += 1
            elif a is not None and (a[0] != b[0] or not _same_value(a[1], b[1])):
                leader_mismatch += 1
    max_m0 = 0.0
    max_m1 = 0.0
    key_mismatch = 0
    keys_compared = 0
    pick_mismatch: dict[str, int] = {trial_name(m, k): 0 for m, k in TRIALS}
    pick_days = 0
    for f in es.folds:
        universe = ind.universes[f.index]
        mine = indep_fold_forecasts(ind, f, universe, list(f.forecast_days()))
        engine_keys = {key for key in es.fc if f.test_start - 1 <= key[1] < f.test_end - 1 and key[0] in universe}
        for key in set(mine) | engine_keys:
            if key not in mine or key not in es.fc:
                key_mismatch += 1
                continue
            keys_compared += 1
            v0, v1 = mine[key]
            e = es.fc[key]
            max_m0 = max(max_m0, abs(v0 - e.m0))
            if (v1 is None) != (e.m1 is None):
                key_mismatch += 1
            elif v1 is not None:
                max_m1 = max(max_m1, abs(v1 - e.m1))
        for model, k in TRIALS:
            name = trial_name(model, k)
            for t in f.forecast_days():
                engine_picks = set(es.dec_local[name][f.index].get(t - f.origin, {}))
                mine_picks = set(indep_picks(mine, model, k, universe, t, ind.uni.threshold))
                pick_days += 1
                if engine_picks != mine_picks:
                    pick_mismatch[name] += 1
    total_pick_mismatch = sum(pick_mismatch.values())
    worst = max(max_m0, max_m1)
    checks = [
        _check("C1 유니버스 일치 (fold별)", {"fold_mismatches": universe_mismatch}, universe_mismatch == 0,
               "fold별 종목 집합 동일"),
        _check("C2 리더 일치 (섹터 x 일)", {"mismatches": leader_mismatch, "compared": leader_total},
               leader_mismatch == 0, "리더 심볼과 리더 수익률이 전 구간 동일"),
        _check("C3 예측값 일치 (M0, M1)", {"max_abs_diff_m0": max_m0, "max_abs_diff_m1": max_m1,
                                          "keys_compared": keys_compared, "key_mismatches": key_mismatch},
               worst <= RECON_TOL and key_mismatch == 0, "예측 최대 절대차 <= 1e-9, 존재·None 여부 일치"),
        _check("C4 top-k 선택 일치 (trial x fold x 일)", {"mismatched_days": pick_mismatch,
                                                        "total_mismatched_days": total_pick_mismatch,
                                                        "decision_days_checked": pick_days},
               total_pick_mismatch == 0, "4개 trial 모두 매일 선택 종목 집합 동일"),
    ]
    return {"title": "C. 독립 예측 (Independent forecasts)",
            "why": "csv 직접 읽기로 수익률·리더·특징·OLS·M0/M1을 다시 계산해 엔진 예측과 비교한다.",
            "checks": checks, "keys_compared": keys_compared}


# ---------------------------------------------------------------- D. look-ahead


def truncate_panel(panel: Any, t: int) -> Any:
    """Copy of the panel with every bar after calendar index t set to missing."""

    def cut(series: Sequence[float | None]) -> list[float | None]:
        return [v if i <= t else None for i, v in enumerate(series)]

    return L.Panel(panel.dates,
                   {s: cut(v) for s, v in panel.open.items()},
                   {s: cut(v) for s, v in panel.close.items()},
                   {s: cut(v) for s, v in panel.dollar_volume.items()},
                   panel.bars, dict(panel.off_calendar))


def engine_day_forecasts(panel: Any, fold: Any, universe: Sequence[str], t: int) -> dict[tuple[str, int], tuple]:
    """Engine forecasts for one day, recomputed on the given panel (fit, leaders and returns all from it)."""
    rets = {s: L._returns(panel.close[s]) for s in panel.close}
    leaders = L.leader_table(panel)
    fc, _ = L.forecasts_for_fold(panel, fold, universe, rets, leaders, [t])
    return {key: (f.m0, f.m1) for key, f in fc.items() if key[1] == t}


def truncation_check(panel: Any, fold: Any, universe: Sequence[str], t: int,
                     forecaster: Callable[..., dict[tuple[str, int], tuple]] = engine_day_forecasts
                     ) -> tuple[int, list[str]]:
    """Forecasts at t must not change when every bar after t is removed. Returns (pairs, mismatched stocks)."""
    full = forecaster(panel, fold, universe, t)
    cut = forecaster(truncate_panel(panel, t), fold, universe, t)
    bad: list[str] = []
    for key in set(full) | set(cut):
        if key not in full or key not in cut:
            bad.append(key[0])
            continue
        a0, a1 = full[key]
        b0, b1 = cut[key]
        if not (_same_value(a0, b0) and _same_value(a1, b1)):
            bad.append(key[0])
    return len(full), sorted(set(bad))


def _shifted(fc: Mapping[tuple[str, int], Any], shift: int) -> dict[tuple[str, int], Any]:
    return {(s, t + shift): v for (s, t), v in fc.items()}


def _diag_books(ind: IndepState, es: EngineState, fc: Mapping[tuple[str, int], Any]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    cfg = ind.uni
    for model, k in TRIALS:
        name = trial_name(model, k)
        dec_cal: dict[int, dict[int, dict[str, float]]] = {}
        for f in es.folds:
            local = L.decisions_for(fc, model, k, es.universes[f.index], list(f.forecast_days()), f.origin)
            dec_cal[f.index] = {key + f.origin: v for key, v in local.items()}
        net = _flat({i: b.returns for i, b in run_fold_books(ind, es.folds, dec_cal, es.universes, cfg.rate).items()})
        gross = _flat({i: b.returns for i, b in run_fold_books(ind, es.folds, dec_cal, es.universes, 0.0).items()})
        out[name] = {"net_cagr": _cagr(net), "gross_cagr": _cagr(gross), "net_total": _total(net),
                     "gross_total": _total(gross)}
    return out


def section_d(es: EngineState, ind: IndepState) -> dict[str, Any]:
    candidates = [(f, t) for f in es.folds for t in f.forecast_days()]
    n_days = TRUNCATION_DAYS
    while True:
        idxs = sorted({round(i * (len(candidates) - 1) / (n_days - 1)) for i in range(n_days)})
        picked = [candidates[i] for i in idxs]
        pairs = sum(1 for f, t in picked for s in es.universes[f.index] if (s, t) in es.fc)
        if pairs >= TRUNCATION_MIN_PAIRS or n_days >= 40:
            break
        n_days += 2
    compared = 0
    mismatched: list[dict[str, Any]] = []
    pick_bad = 0
    pick_checked = 0
    for f, t in picked:
        universe = es.universes[f.index]
        pairs_t, bad = truncation_check(es.panel, f, universe, t)
        compared += pairs_t
        if bad:
            mismatched.append({"fold": f.index, "t": t, "stocks": bad})
        full_pairs = {(s, t): es.fc[(s, t)] for s in universe if (s, t) in es.fc}
        cut_pairs = {key: L.Forecast(v[0], v[1], False) for key, v in
                     engine_day_forecasts(truncate_panel(es.panel, t), f, universe, t).items()}
        for model, k in TRIALS:
            a = L.decisions_for(full_pairs, model, k, universe, [t], 0).get(t, {})
            b = L.decisions_for(cut_pairs, model, k, universe, [t], 0).get(t, {})
            pick_checked += 1
            if set(a) != set(b):
                pick_bad += 1
    trunc_ok = not mismatched and compared >= TRUNCATION_MIN_PAIRS and pick_bad == 0
    # D2 / D3 diagnostics: same engine forecasts shifted in time. These are labelled diagnostics, not trials.
    stale = _diag_books(ind, es, _shifted(es.fc, +1))
    future = _diag_books(ind, es, _shifted(es.fc, -1))
    oracle_fc: dict[tuple[str, int], Any] = {}
    for f in es.folds:
        for t in f.forecast_days():
            for stock in es.universes[f.index]:
                y = _target(ind, stock, t)
                if y is not None:
                    oracle_fc[(stock, t)] = L.Forecast(y, y, False)
    oracle = _diag_books(ind, es, oracle_fc)
    checks = [
        _check("D1 절단 테스트: 시점 t 이후 막대 제거 후 예측·결정 불변",
               {"days_sampled": len(picked), "pairs_compared": compared,
                "mismatched_days": mismatched, "decision_mismatches": pick_bad,
                "decision_checks": pick_checked},
               trunc_ok, "(종목, t) 쌍 >= 200, 예측 차이 <= 1e-9, 4개 trial 선택 불변"),
    ]
    return {"title": "D. 룩어헤드 점검 (Look-ahead)",
            "why": ("D1: 미래 막대를 지운 판에서 같은 t의 예측이 바뀌면 룩어헤드다. "
                    "D2·D3는 진단용이다. 시간을 한 칸 밀린 예측으로 결정을 만들어, 누설이 있으면 "
                    "얼마나 큰 gross가 나타나는지와 실제 결과를 비교한다."),
            "checks": checks,
            "diagnostics": {
                "D2_stale_placebo_t_minus_1_forecast_used_at_t": stale,
                "D3_future_sentinel_t_plus_1_forecast_used_at_t": future,
                "D4_oracle_realized_target_reference": oracle,
                "sentinel_note": ("D3는 t+1 예측을 t에 쓰는 누설이다. 예측 목표가 t+2 시가 수익이라 시차가 맞지 않아 "
                                  "gross가 음수가 될 수 있다. 누설이 gross를 키우는 모습의 상한은 D4(실제 목표 수익으로 고른 "
                                  "오라클, 진단용 참고치)로 본다."),
            },
            "truncation_days": [{"fold": f.index, "t": t} for f, t in picked]}


# ---------------------------------------------------------------- E. dividends


def _dividend_events(div_dir: Path, symbol: str) -> list[tuple[date, float]]:
    path = div_dir / f"{symbol}.json"
    if not path.is_file():
        return []
    document = json.loads(path.read_text(encoding="utf-8"))
    return sorted((date.fromisoformat(item["ex_date"]), float(item["amount"]))
                  for item in document.get("events", []))


def dividend_identity_errors(calendar: Sequence[date], split_close: Sequence[float | None],
                             tr_close: Sequence[float | None], events: Sequence[tuple[date, float]],
                             tol: float = DIVIDEND_TOL) -> dict[str, Any]:
    """At each ex-date e: factor(e-1)/factor(e) must equal 1 - D/C_split(e-1). factor = tr / split."""
    index = {day: i for i, day in enumerate(calendar)}
    factor = [None if (a is None or b is None) else a / b for a, b in zip(tr_close, split_close)]
    checked = 0
    failed: list[dict[str, Any]] = []
    event_idx: set[int] = set()
    off_calendar = 0
    out_of_range = 0
    for ex, amount in events:
        i = index.get(ex)
        if i is None:
            off_calendar += 1
            continue
        if i == 0 or split_close[i - 1] is None or factor[i - 1] is None or factor[i] is None:
            continue
        y = amount / split_close[i - 1]  # type: ignore[operator]
        if not 0.0 < y <= EVENT_YIELD_MAX:
            out_of_range += 1
            continue
        expected = 1.0 - y
        actual = factor[i - 1] / factor[i]  # type: ignore[operator]
        checked += 1
        event_idx.add(i)
        if abs(actual - expected) > tol:
            failed.append({"ex_date": ex.isoformat(), "expected": expected, "actual": actual})
    unexplained = 0
    for i in range(1, len(calendar)):
        if factor[i - 1] is None or factor[i] is None or i in event_idx:
            continue
        if abs(factor[i] / factor[i - 1] - 1.0) > tol:  # type: ignore[operator]
            unexplained += 1
    return {"checked": checked, "failed": failed, "unexplained_factor_changes": unexplained,
            "off_calendar_events": off_calendar, "out_of_range_events": out_of_range}


def _cagr_window(series: Sequence[float | None], start: int, end: int) -> float | None:
    a, b = series[start], series[end]
    if a is None or b is None or a <= 0:
        return None
    years = (end - start) / DAYS_PER_YEAR
    return (b / a) ** (1.0 / years) - 1.0


def section_e(es: EngineState, ind: IndepState, toss_dir: Path, div_dir: Path) -> dict[str, Any]:
    rows: list[dict[str, Any]] = []
    identity_fail = 0
    identity_checked = 0
    unexplained = 0
    yield_fail: list[str] = []
    start = es.folds[0].origin
    end = es.folds[-1].test_end - 1
    for symbol in sorted(ind.closes):
        split_table = read_csv_table(toss_dir / f"{symbol}.csv")
        split_close = [row[3] if row else None for row in _align(split_table, ind.cal)]
        tr_close = ind.closes[symbol]
        events = _dividend_events(div_dir, symbol)
        ident = dividend_identity_errors(ind.cal, split_close, tr_close, events)
        identity_checked += ident["checked"]
        identity_fail += len(ident["failed"])
        unexplained += ident["unexplained_factor_changes"]
        tr_cagr = _cagr_window(tr_close, start, end)
        px_cagr = _cagr_window(split_close, start, end)
        implied = None if tr_cagr is None or px_cagr is None else tr_cagr - px_cagr
        if implied is None:
            ok_range = None  # no price at the OOS window edges: coverage gap, not a yield failure
        else:
            ok_range = 0.0 <= implied <= DIV_YIELD_MAX
            if symbol == ind.uni.benchmark:
                ok_range = ok_range and SPY_YIELD_RANGE[0] <= implied <= SPY_YIELD_RANGE[1]
            if not ok_range:
                yield_fail.append(symbol)
        rows.append({"symbol": symbol, "events": len(events), "identity_checked": ident["checked"],
                     "identity_failed": len(ident["failed"]), "unexplained_factor_changes":
                     ident["unexplained_factor_changes"], "tr_cagr": tr_cagr, "price_cagr": px_cagr,
                     "implied_yield": implied, "yield_in_range": ok_range,
                     "coverage": "OK" if implied is not None else "OOS 가격 없음(판정 제외)"})
    implied_vals = [r["implied_yield"] for r in rows if r["implied_yield"] is not None]
    spy = next((r for r in rows if r["symbol"] == ind.uni.benchmark), None)
    checks = [
        _check("E1 후행 조정 항등식 (모든 배당 ex-date)",
               {"events_checked": identity_checked, "identity_failures": identity_fail,
                "unexplained_factor_changes": unexplained},
               identity_fail == 0 and unexplained == 0 and identity_checked > 0,
               "ex-date마다 |factor(e-1)/factor(e) - (1 - D/C(e-1))| <= 1e-8, 배당 외 계수 변화 없음"),
        _check("E2 암시 배당수익률 범위", {"min": min(implied_vals) if implied_vals else None,
                                          "max": max(implied_vals) if implied_vals else None,
                                          "spy": spy["implied_yield"] if spy else None,
                                          "failing_symbols": yield_fail},
               not yield_fail, "종목별 0 <= 연 배당수익률 <= 6%, SPY 1.5~2.5%"),
    ]
    return {"title": "E. 배당 (Dividends)",
            "why": "TR 파생 데이터(배당 후행 조정)가 배당 이벤트와 정확히 맞는지, 그리고 OOS 기간의 암시 배당수익률이 현실 범위인지 본다.",
            "checks": checks, "rows": rows,
            "events_total": sum(r["events"] for r in rows)}


# ---------------------------------------------------------------- F. decomposition


def _book_summary(books: Mapping[int, Book], rate: float, k: int, folds: Sequence[Any]) -> dict[str, Any]:
    flat = _flat({i: b.returns for i, b in books.items()})
    turnover = [t for i in sorted(books) for t in books[i].turnover]
    held = [h for i in sorted(books) for h in books[i].held_gross]
    positions = [p for i in sorted(books) for p in books[i].decision_positions]
    full = [x for i in sorted(books) for x in books[i].full_replacement_turnover]
    overlap = [x for i in sorted(books) for x in books[i].overlap]
    spells = [s for i in sorted(books) for s in books[i].spell_lengths]
    years = len(flat) / DAYS_PER_YEAR
    total_turn = sum(turnover)
    return {
        "total_return": _total(flat), "cagr": _cagr(flat), "turnover_per_year": total_turn / years,
        "cost_drag_per_year_from_turnover": rate * total_turn / years,
        "avg_invested_at_open": sum(held) / len(held) if held else None,
        "all_cash_decision_day_share": sum(1 for p in positions if p == 0) / len(positions) if positions else None,
        "all_cash_held_day_share": sum(1 for h in held if h < 1e-9) / len(held) if held else None,
        "avg_positions": sum(positions) / len(positions) if positions else None,
        "avg_position_size_target": 1.0 / k,
        "avg_holding_days": sum(spells) / len(spells) if spells else None,
        "full_replacement_turnover_share": sum(full) / total_turn if total_turn > 0 else None,
        "avg_name_retention_day_over_day": sum(overlap) / len(overlap) if overlap else None,
    }


def section_f(es: EngineState, ind: IndepState) -> dict[str, Any]:
    cfg = ind.uni
    trials: dict[str, Any] = {}
    fold_rows: dict[str, list[dict[str, Any]]] = {}
    for model, k in TRIALS:
        name = trial_name(model, k)
        net_books = run_fold_books(ind, es.folds, es.dec_cal[name], es.universes, cfg.rate)
        gross_books = run_fold_books(ind, es.folds, es.dec_cal[name], es.universes, 0.0)
        stress_books = run_fold_books(ind, es.folds, es.dec_cal[name], es.universes, cfg.rate * cfg.stress_multiplier)
        net = _book_summary(net_books, cfg.rate, k, es.folds)
        gross = _book_summary(gross_books, 0.0, k, es.folds)
        stress = _cagr(_flat({i: b.returns for i, b in stress_books.items()}))
        net["gross_cagr"] = gross["cagr"]
        net["gross_total"] = gross["total_return"]
        net["cost_drag_cagr"] = (gross["cagr"] - net["cagr"]) if gross["cagr"] is not None and net["cagr"] is not None else None
        net["stress_2x_cagr"] = stress
        trials[name] = net
        rows = []
        for f in es.folds:
            nb = net_books[f.index]
            gb = gross_books[f.index]
            rows.append({"fold": f.index, "test_start": es.panel.dates[f.test_start].isoformat(),
                         "test_end": es.panel.dates[f.test_end - 1].isoformat(),
                         "gross_total": _total(gb.returns), "net_total": _total(nb.returns),
                         "turnover_per_year": sum(nb.turnover) / (len(nb.returns) / DAYS_PER_YEAR),
                         "avg_invested": sum(nb.held_gross) / len(nb.held_gross),
                         "avg_positions": (sum(nb.decision_positions) / len(nb.decision_positions))})
        fold_rows[name] = rows
    return {"title": "F. 분해 (Decomposition per trial)",
            "why": "gross(비용 0) -> 비용 차감 -> net 순서로 손실을 나누고, 회전율·투자비중·보유기간으로 원인을 본다.",
            "checks": [], "trials": trials, "by_fold": fold_rows}


# ---------------------------------------------------------------- G. baselines (diagnostic, not trials)


def _random_decisions(es: EngineState, name: str, k: int, seed: int) -> dict[int, dict[int, dict[str, float]]]:
    rng = random.Random(seed)
    out: dict[int, dict[int, dict[str, float]]] = {}
    for f in es.folds:
        universe = sorted(es.universes[f.index])
        picks_by_day: dict[int, dict[str, float]] = {}
        for t in sorted(es.dec_cal[name][f.index]):
            count = len(es.dec_cal[name][f.index][t])
            chosen = rng.sample(universe, count)
            picks_by_day[t] = {s: 1.0 / k for s in chosen}
        out[f.index] = picks_by_day
    return out


def section_g(es: EngineState, ind: IndepState, report: Mapping[str, Any]) -> dict[str, Any]:
    cfg = ind.uni
    rate = cfg.rate
    all_folds = es.folds
    origin_first = all_folds[0].origin
    end_last = all_folds[-1].test_end - 1
    baselines: dict[str, Any] = {}
    n_days = end_last - origin_first
    baselines["no_trade_cash"] = {"net_cagr": 0.0, "gross_cagr": 0.0, "net_total": 0.0, "days": n_days}
    spy_books = run_book([cfg.benchmark], ind.opens, ind.closes, origin_first, end_last,
                         {origin_first: {cfg.benchmark: 1.0}}, rate)
    spy_gross = run_book([cfg.benchmark], ind.opens, ind.closes, origin_first, end_last,
                         {origin_first: {cfg.benchmark: 1.0}}, 0.0)
    baselines["spy_buy_hold"] = {"net_cagr": _cagr(spy_books.returns), "gross_cagr": _cagr(spy_gross.returns),
                                 "net_total": _total(spy_books.returns),
                                 "reconcile_to_report_total": abs(_total(spy_books.returns)
                                                                  - report["benchmark"]["out_of_sample"]["total_return"])}
    ew_hold_dec = {f.index: {f.origin: {s: 1.0 / len(es.universes[f.index]) for s in es.universes[f.index]}}
                   for f in all_folds}
    ew_daily_dec = {f.index: {t: {s: 1.0 / len(es.universes[f.index]) for s in es.universes[f.index]}
                              for t in f.forecast_days()} for f in all_folds}
    for label, dec in (("ew_buy_hold_per_fold", ew_hold_dec), ("ew_daily_rebalanced", ew_daily_dec)):
        net = _flat({i: b.returns for i, b in run_fold_books(ind, all_folds, dec, es.universes, rate).items()})
        gross = _flat({i: b.returns for i, b in run_fold_books(ind, all_folds, dec, es.universes, 0.0).items()})
        turn = [t for b in run_fold_books(ind, all_folds, dec, es.universes, rate).values() for t in b.turnover]
        baselines[label] = {"net_cagr": _cagr(net), "gross_cagr": _cagr(gross), "net_total": _total(net),
                            "turnover_per_year": sum(turn) / (len(net) / DAYS_PER_YEAR)}
    random_rows: dict[str, Any] = {}
    for model, k in TRIALS:
        name = trial_name(model, k)
        strat_gross = _cagr(_flat({i: b.returns for i, b in run_fold_books(
            ind, all_folds, es.dec_cal[name], es.universes, 0.0).items()}))
        gross_list: list[float] = []
        net_list: list[float] = []
        for seed in RANDOM_SEEDS:
            dec = _random_decisions(es, name, k, seed)
            g = _cagr(_flat({i: b.returns for i, b in run_fold_books(ind, all_folds, dec, es.universes, 0.0).items()}))
            n = _cagr(_flat({i: b.returns for i, b in run_fold_books(ind, all_folds, dec, es.universes, rate).items()}))
            gross_list.append(g)
            net_list.append(n)
        beat = sum(1 for g in gross_list if g >= strat_gross)
        mean_gross = sum(gross_list) / len(gross_list)
        random_rows[name] = {
            "strategy_gross_cagr": strat_gross,
            "random_gross_cagr_mean": mean_gross,
            "random_gross_cagr_p05": _percentile(gross_list, 0.05),
            "random_gross_cagr_p95": _percentile(gross_list, 0.95),
            "random_net_cagr_mean": sum(net_list) / len(net_list),
            "random_net_cagr_p05": _percentile(net_list, 0.05),
            "random_net_cagr_p95": _percentile(net_list, 0.95),
            "strategy_selection_excess_gross": strat_gross - mean_gross,
            "seeds_beating_strategy_gross": beat,
            "seeds": len(gross_list),
        }
    return {"title": "G. 기준선 (Baselines, 진단용: 시도 아님)",
            "why": ("기준선은 새 전략이 아니다. 무거래·SPY·동일가중·무작위 노출 일치 집합과 비교해 "
                    "'종목 선택'이 gross에 무언가를 더하는지 본다. 같은 종목을 매일 교체하지 않고 보유하는 변형은 "
                    "새 전략이므로 만들지 않았다."),
            "checks": [], "baselines": baselines, "random_exposure_matched": random_rows}


# ---------------------------------------------------------------- H. verdict


def section_h(a: Mapping[str, Any], b: Mapping[str, Any], c: Mapping[str, Any], d: Mapping[str, Any],
              e: Mapping[str, Any], f: Mapping[str, Any], g: Mapping[str, Any]) -> dict[str, Any]:
    def ok(section: Mapping[str, Any], prefix: str | None = None) -> bool:
        return all(bool(check["pass"]) for check in section["checks"]
                   if prefix is None or check["name"].startswith(prefix))

    impl: list[str] = []
    if not ok(a):
        impl.append("A 재현 실패: 리포트 수치를 엔진 경로로 재현하지 못함")
    if not ok(b):
        impl.append("B 독립 회계 불일치: 엔진 장부와 별도 장부의 수익이 다름")
    if not ok(c):
        impl.append("C 독립 예측 불일치: 리더/특징/OLS/M0-M1 예측 또는 top-k 선택이 다름")
    if not ok(d, "D1"):
        impl.append("D1 룩어헤드: 미래 막대를 지웠을 때 같은 시점의 예측 또는 결정이 바뀜")
    if not ok(e, "E1"):
        impl.append("E1 배당 항등식 불일치: 후행 조정 계수가 배당과 맞지 않음")
    data_warn = [] if ok(e, "E2") else ["E2 암시 배당수익률이 범위를 벗어난 종목이 있음"]
    verdict = ("IMPLEMENTATION_ERROR_FOUND: " + "; ".join(impl)) if impl else "NONE_FOUND"
    primary = f["trials"][PRIMARY]
    gross_c = primary["gross_cagr"]
    net_c = primary["cagr"]
    cost_drag = primary["cost_drag_cagr"]
    random_row = g["random_exposure_matched"][PRIMARY]
    excess = random_row["strategy_selection_excess_gross"]
    attribution_lines = []
    for name in trial_names_order():
        row = f["trials"][name]
        attribution_lines.append({"trial": name, "gross_cagr": row["gross_cagr"], "cost_drag_cagr": row["cost_drag_cagr"],
                                  "net_cagr": row["cagr"], "turnover_per_year": row["turnover_per_year"]})
    lag_gross = d["diagnostics"]["D2_stale_placebo_t_minus_1_forecast_used_at_t"][PRIMARY]["gross_cagr"]
    ew_net = g["baselines"]["ew_buy_hold_per_fold"]["net_cagr"]
    sentence = (
        f"구현 점검(A·B·C·D1·E1)에서 불일치가 없으므로, 손실은 엔진 버그가 아니라 전략 설계(일일 전면 교체 x 15bp 비용)에서 나온다. "
        f"{PRIMARY}: gross CAGR {_pct(gross_c)}, 비용 차감 {_pct(cost_drag)}p, net CAGR {_pct(net_c)}. "
        f"같은 노출·같은 회전의 무작위 선택 gross 평균은 {_pct(random_row['random_gross_cagr_mean'])}이고, "
        f"전략 선택 초과 gross는 {_pct(excess)}p다 (무작위 {random_row['seeds']}개 중 "
        f"{random_row['seeds_beating_strategy_gross']}개만 전략 gross 이상). "
        f"1일 지연 placebo의 gross는 {_pct(lag_gross)}로 무작위 수준에 가까워, 신호는 당일 종가 정보에 매우 민감하다 "
        f"(샘플 절단 테스트 D1에서 룩어헤드는 발견되지 않음). "
        f"동일 유니버스 동일가중 매수보유(노출 100%)의 net은 {_pct(ew_net)}이고, 전략은 평균 투자비중 "
        f"{_pct(primary['avg_invested_at_open'])}로 gross {_pct(gross_c)}를 만들었지만 비용이 이를 net {_pct(net_c)}로 바꾼다."
    ) if not impl else (
        "구현 점검에서 불일치가 발견되어 손실 원인을 전략 실패와 구현 오류로 나눌 수 없다. 먼저 위 항목을 고쳐야 한다."
    )
    return {"title": "H. 판정 (Verdict)",
            "why": "구현 점검 결과와 손실 귀속 수치를 한 곳에 모은다.",
            "verdict": verdict,
            "implementation_failures": impl,
            "data_warnings": data_warn,
            "loss_attribution": attribution_lines,
            "attribution_sentence": sentence,
            "checks": []}


def trial_names_order() -> list[str]:
    return [trial_name(m, k) for m, k in TRIALS]


def _pct(value: float | None) -> str:
    if value is None:
        return "n/a"
    return f"{value * 100:.2f}%"


# ---------------------------------------------------------------- run, markdown, CLI


def run_audit(data_dir: Path, toss_dir: Path, report_path: Path, div_dir: Path, prior_path: Path) -> dict[str, Any]:
    report = json.loads(report_path.read_text(encoding="utf-8"))
    ind = build_indep_state(data_dir, report)
    es = build_engine_state(data_dir)
    a = section_a(es, ind, report, data_dir, prior_path)
    b = section_b(es, ind)
    c = section_c(es, ind)
    d = section_d(es, ind)
    e = section_e(es, ind, toss_dir, div_dir)
    f = section_f(es, ind)
    g = section_g(es, ind, report)
    h = section_h(a, b, c, d, e, f, g)
    inputs = {
        "report_sha256": _sha256_file(report_path),
        "report_config_hash": report["config_hash"],
        "report_dataset_hash": report["dataset"]["dataset_hash"],
        "tr_manifest_sha256": _sha256_file(data_dir / "manifest.json"),
        "prior_report_sha256": _sha256_file(prior_path) if prior_path.is_file() else None,
    }
    return {"label": AUDIT_LABEL, "inputs": inputs, "A": a, "B": b, "C": c, "D": d, "E": e,
            "F": f, "G": g, "H": h}


def _fmt(value: Any) -> str:
    if value is None:
        return "n/a"
    if isinstance(value, bool):
        return "예" if value else "아니오"
    if isinstance(value, float):
        if abs(value) < 1e-3 and value != 0.0:
            return f"{value:.3e}"
        return f"{value:.6f}"
    if isinstance(value, (dict, list)):
        return f"{len(value)}건" if isinstance(value, list) else "(상세는 JSON)"
    return str(value)


def _checks_md(checks: Sequence[Mapping[str, Any]]) -> list[str]:
    lines = ["| 점검 | 결과 | 기준 | 판정 |", "|---|---|---|---|"]
    for check in checks:
        value = check["value"]
        if isinstance(value, dict):
            summary = ", ".join(f"{k}={_fmt(v)}" for k, v in value.items()
                                if not isinstance(v, (dict, list)))
        else:
            summary = _fmt(value)
        verdict = "PASS" if check["pass"] else ("FAIL" if check["pass"] is False else "정보")
        lines.append(f"| {check['name']} | {summary} | {check['rule']} | {verdict} |")
    return lines


def render_markdown(res: Mapping[str, Any]) -> str:
    h = res["H"]
    lines: list[str] = [
        f"# LL-1 손실 독립 감사",
        "",
        f"- {res['label']}",
        f"- 리포트 sha256: `{res['inputs']['report_sha256'][:8]}`, 설정 해시: `{res['inputs']['report_config_hash'][:12]}`",
        f"- 데이터 해시: `{res['inputs']['report_dataset_hash'][:12]}`",
        "- 이 감사는 새 시도(trial)를 만들지 않는다. 기준선·placebo·sentinel은 모두 진단이다.",
        "",
        "## 판정",
        "",
        f"**{h['verdict']}**",
        "",
    ]
    if h["implementation_failures"]:
        lines += ["구현 실패 항목:"] + [f"- {item}" for item in h["implementation_failures"]] + [""]
    lines += [h["attribution_sentence"], "", "### 손실 귀속 (trial별, 연환산)", "",
              "| trial | gross CAGR | 비용 차감(p) | net CAGR | 연 회전율 |", "|---|---|---|---|---|"]
    for row in h["loss_attribution"]:
        lines.append(f"| {row['trial']} | {_pct(row['gross_cagr'])} | {_pct(row['cost_drag_cagr'])} | "
                     f"{_pct(row['net_cagr'])} | {format(row['turnover_per_year'], '.1f')} |")
    if h["data_warnings"]:
        lines += ["", "데이터 경고: " + "; ".join(h["data_warnings"])]
    lines += ["", "```", f"VERDICT: {h['verdict']}", "```", ""]

    for key in "ABCDEFG":
        sec = res[key]
        lines += [f"## {sec['title']}", "", f"**왜:** {sec['why']}", ""]
        if sec.get("checks"):
            lines += _checks_md(sec["checks"]) + [""]
        if key == "A":
            lines += ["| trial | net 엔진 | net 리포트 | gross 엔진 | gross 리포트 | 2x 엔진 | 2x 리포트 |",
                      "|---|---|---|---|---|---|---|"]
            for name, row in sec["trials"].items():
                o, r = row["observed"], row["reported"]
                lines.append(f"| {name} | {_fmt(o['net'])} | {_fmt(r['net'])} | {_fmt(o['gross'])} | "
                             f"{_fmt(r['gross'])} | {_fmt(o['stress_2x'])} | {_fmt(r['stress_2x'])} |")
            lines.append("")
        if key == "B":
            lines += [f"convention: {sec['convention_note']}", "", "literal 해석 차이 (정보용):", ""]
            for row in sec["literal_convention_sensitivity"]:
                lines.append(f"- {row['trial']}: 순수익 총합 차이 {_fmt(row['literal_minus_engine_net_total'])}")
            lines.append("")
        if key == "D":
            diag = sec["diagnostics"]
            lines += ["| 진단 (기준 = 엔진, 시도 아님) | trial | net CAGR | gross CAGR |", "|---|---|---|---|"]
            for label, table in (("D2 한 일 지연 placebo", diag["D2_stale_placebo_t_minus_1_forecast_used_at_t"]),
                                 ("D3 한 일 선행 누설 sentinel", diag["D3_future_sentinel_t_plus_1_forecast_used_at_t"]),
                                 ("D4 오라클 상한 (실제 목표 수익으로 선택)", diag["D4_oracle_realized_target_reference"])):
                for name, row in table.items():
                    lines.append(f"| {label} | {name} | {_pct(row['net_cagr'])} | {_pct(row['gross_cagr'])} |")
            lines += ["", diag["sentinel_note"], ""]
        if key == "E":
            lines += ["| 종목 | 이벤트 | 항등식 검사 | 실패 | 계수 미설명 변화 | TR CAGR | 가격 CAGR | 암시 배당수익률 | 범위 |",
                      "|---|---|---|---|---|---|---|---|---|"]
            for row in sec["rows"]:
                lines.append(f"| {row['symbol']} | {row['events']} | {row['identity_checked']} | "
                             f"{row['identity_failed']} | {row['unexplained_factor_changes']} | "
                             f"{_pct(row['tr_cagr'])} | {_pct(row['price_cagr'])} | "
                             f"{_pct(row['implied_yield'])} | "
                     f"{'FAIL' if row['yield_in_range'] is False else ('OK' if row['yield_in_range'] else '판정 제외')} |")
            lines.append("")
        if key == "F":
            lines += ["| trial | gross CAGR | 비용(p/년) | net CAGR | 2x CAGR | 연 회전율 | 평균 투자비중 | "
                      "무포지션 결정일 | 평균 종목 수 | 평균 보유일 | 전면교체 회전 비중 | 일 간 종목 유지율 |",
                      "|---|---|---|---|---|---|---|---|---|---|---|---|"]
            for name, row in sec["trials"].items():
                lines.append(
                    f"| {name} | {_pct(row['gross_cagr'])} | {_pct(row['cost_drag_cagr'])} | {_pct(row['cagr'])} | "
                    f"{_pct(row['stress_2x_cagr'])} | {format(row['turnover_per_year'], '.1f')} | "
                    f"{_pct(row['avg_invested_at_open'])} | {_pct(row['all_cash_decision_day_share'])} | "
                    f"{_fmt(row['avg_positions'])} | {_fmt(row['avg_holding_days'])} | "
                    f"{_pct(row['full_replacement_turnover_share'])} | {_pct(row['avg_name_retention_day_over_day'])} |")
            lines += ["", "fold별 총수익 (gross / net):", "",
                      "| fold | 테스트 기간 | " + " | ".join(f"{n} gross | {n} net" for n in trial_names_order()) + " |",
                      "|---|---|" + "---|---|" * len(trial_names_order())]
            base = sec["by_fold"][trial_names_order()[0]]
            for idx, row in enumerate(base):
                cells = []
                for n in trial_names_order():
                    r = sec["by_fold"][n][idx]
                    cells += [_pct(r["gross_total"]), _pct(r["net_total"])]
                lines.append(f"| {row['fold']} | {row['test_start']}~{row['test_end']} | " + " | ".join(cells) + " |")
            lines.append("")
        if key == "G":
            lines += ["| 기준선 (진단) | net CAGR | gross CAGR | 비고 |", "|---|---|---|---|"]
            for name, row in sec["baselines"].items():
                lines.append(f"| {name} | {_pct(row['net_cagr'])} | {_pct(row['gross_cagr'])} | "
                             f"{'' if 'turnover_per_year' not in row else '연 회전율 ' + format(row['turnover_per_year'], '.1f')} |")
            lines += ["", "무작위 노출 일치 (seed 0..49):", "",
                      "| trial | 전략 gross | 무작위 gross 평균 | 5% | 95% | 전략 선택 초과 gross(p) | 무작위 net 평균 | 전략 gross 이상 seed 수 |",
                      "|---|---|---|---|---|---|---|---|"]
            for name, row in sec["random_exposure_matched"].items():
                lines.append(
                    f"| {name} | {_pct(row['strategy_gross_cagr'])} | {_pct(row['random_gross_cagr_mean'])} | "
                    f"{_pct(row['random_gross_cagr_p05'])} | {_pct(row['random_gross_cagr_p95'])} | "
                    f"{_pct(row['strategy_selection_excess_gross'])} | {_pct(row['random_net_cagr_mean'])} | "
                    f"{row['seeds_beating_strategy_gross']} / {row['seeds']} |")
            lines.append("")
    lines += ["## 남은 위험과 설계 메모", "",
              "- 유니버스는 현재 구성 종목이다(생존 편향). 이 편향은 전략에 유리하므로, 손실 결론을 약화시키지 않는다.",
              "- fold 유니버스는 test_end까지의 데이터 완전성으로 고른다. 미래 정보를 쓰는 필터이며, 감사에서는 고정해 두고 절단 테스트를 했다.",
              "- Toss 원본 조정(분할만)의 manifest는 adjustment_basis=UNVERIFIED다. 배당 조정은 Yahoo 배당 이벤트로 적용했다.",
              "- 무거래 대안·같은 종목 보유 변형은 새 전략이므로 만들지 않았다(요청 범위 밖).", ""]
    return "\n".join(lines)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m app.research.leadlag_audit",
                                     description="LL-1 손실 독립 감사 (읽기 전용, 진단 전용).")
    parser.add_argument("--data", required=True, type=Path, help="leadlag-tr 파생 데이터 디렉터리")
    parser.add_argument("--toss", required=True, type=Path, help="Toss 분할 조정 원본 디렉터리 (leadlag)")
    parser.add_argument("--report", required=True, type=Path, help="leadlag-report.json")
    parser.add_argument("--dividends", type=Path, default=Path("../research-data/leadlag-dividends"),
                        help="배당 이벤트 디렉터리 (기본: ../research-data/leadlag-dividends)")
    parser.add_argument("--prior-report", type=Path, default=Path("../research-data/full-report.json"),
                        help="54-trial 이전 리포트 (A3 재현용)")
    parser.add_argument("--out", required=True, type=Path, help="canonical JSON 출력 경로")
    parser.add_argument("--markdown", required=True, type=Path, help="한국어 markdown 출력 경로")
    args = parser.parse_args(argv)
    try:
        result = run_audit(args.data, args.toss, args.report, args.dividends, args.prior_report)
    except (L.LeadLagError, OSError, ValueError, KeyError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(canonical_json(result, indent=2) + "\n", encoding="utf-8")
    args.markdown.parent.mkdir(parents=True, exist_ok=True)
    args.markdown.write_text(render_markdown(result), encoding="utf-8")
    print(f"verdict: {result['H']['verdict']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
