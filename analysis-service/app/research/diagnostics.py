"""Read-only failure diagnostics for an existing research run.

Explains why the momentum, trend and mean_reversion families failed on a dataset.
It re-derives the research pipeline with the same calls as ``validation.run_validation``
and reconciles the result with ``report.build_report`` (any mismatch raises).

Nothing here creates a strategy, a config, a parameter recommendation or a candidate.
All outputs are descriptive. Stdlib only. No network, no order placement.

CLI: ``python -m app.research.diagnostics --data <dir> --out <json> --markdown <md>``
"""

from __future__ import annotations

import argparse
import itertools
import math
import statistics
import sys
from collections.abc import Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any

from app.research import validation
from app.research.backtest import BacktestResult, cost_rate_from_bps, restrict_decisions, simulate
from app.research.data import Bar, Dataset, ResearchError, align, canonical_json, load_dataset
from app.research.metrics import (
    annualize_sharpe,
    beta_alpha,
    cagr,
    monthly_returns,
    sharpe_per_period,
)
from app.research.report import build_report
from app.research.strategies import (
    BENCHMARK_SYMBOL,
    SMA_LONG_WINDOW,
    Weights,
    build_strategies,
    compute_decisions,
)
from app.research.validation import ConfigRun, FoldWindow, PathWindow, Split, path_window

DIAGNOSTICS_LABEL = (
    "Read-only failure diagnostics of an existing research run. Descriptive only: "
    "not a candidate, not a parameter recommendation, and not evidence for any strategy."
)
GENERATED_BY = "app.research.diagnostics 1.0.0"
FAMILIES: tuple[str, ...] = ("momentum", "trend", "mean_reversion")
EPSILON = 1e-12
MATCHED_TOLERANCE = 0.01
CSCV_BLOCKS = 16
PBO_THRESHOLD = 0.5
FORWARD_HORIZONS: tuple[int, ...] = (1, 3, 5)
ETF_FORWARD_SESSIONS = 21
TOP_TAIL_FRACTION = 0.01
BREAKEVEN_UPPER_COST_RATE = 0.05
BISECTION_STEPS = 60
CRISIS_WINDOWS: tuple[tuple[str, str, str], ...] = (
    ("GFC_REBOUND_2009", "2009-03-09", "2009-12-31"),
    ("COVID_REBOUND_2020", "2020-03-23", "2020-12-31"),
)
HINDSIGHT_LABEL = "LOOK-AHEAD, NOT A CANDIDATE"

PRE_DECLARED: tuple[tuple[str, str, str], ...] = (
    (
        "1. Exposure-matched benchmark (most important)",
        "Whether the strategy beats a passive SPY+cash mix held at the family's own average gross "
        "exposure over the same window; CAPM beta and alpha vs SPY (annualized).",
        "strategy < matched mix: timing destroyed value. Within +/-1pp CAGR of matched mix: failure is "
        "under-exposure in a rising market, no timing skill. Above matched mix but below SPY: some "
        "timing value, insufficient to offset lower exposure. More than 1pp above matched mix and at or "
        "above SPY: no shortfall vs SPY at this window.",
    ),
    (
        "2. Gross vs net",
        "Whether transaction costs cause the gap: gross = net + cost_rate x turnover per day.",
        "Gross also below matched mix: costs are not the cause. Breakeven = cost per unit traded at which "
        "OOS CAGR equals matched mix or SPY (engine-exact cost model).",
    ),
    (
        "3. Selection vs hypothesis",
        "Per-fold train-to-test Sharpe degradation, config switches across folds, PBO via CSCV over the "
        "research window (holdout excluded), and the hindsight-best config per family.",
        "Hindsight-best (LOOK-AHEAD, NOT A CANDIDATE) below SPY Sharpe: the family hypothesis fails "
        "regardless of selection. PBO above 0.5: selection overfit.",
    ),
    (
        "4. Regime / capture",
        "Monthly upside and downside capture vs SPY, calendar-year returns, bull/bear split by SPY close "
        "vs SMA200 known at t-1, missed top-1% SPY days, and crisis-rebound returns.",
        "Low upside capture and many missed top-1% days in OOS: lagging rallies. Crisis-window returns "
        "below SPY: defensive value absent in rebounds.",
    ),
    (
        "5. Mean-reversion event study",
        "Forward SPY returns at +1/+3/+5 sessions after entry signals vs unconditional, split into "
        "overnight and intraday legs, with per-trade hit rate and gross/net averages.",
        "Signals at or below unconditional forward returns: no short-horizon edge. The leg split locates "
        "where any edge sits. Any close-fill implication is a new hypothesis, not tested.",
    ),
    (
        "6. Momentum attribution",
        "Per-ETF share of OOS days held and approximate return contribution; share of OOS days in which "
        "the held set excluded the better of SPY/QQQ over the next 21 sessions.",
        "High exclusion share: the selection lagged the leader. Contribution concentrated in few ETFs "
        "or days: the result depends on a narrow set of holdings.",
    ),
    (
        "7. Data bias",
        "Missing dividends and T-bill interest in the dataset.",
        "Bounds are stated as given in the diagnostic brief and are not recomputed here. Exact "
        "quantification needs external dividend and T-bill data, which requires approval.",
    ),
)

CAVEATS: tuple[str, ...] = (
    "The 2022-26 holdout has now been viewed in this report and in these diagnostics. Future hypotheses "
    "cannot treat it as untouched.",
    "Any future claim needs N > 54 cumulative trials counted across every run, pre-registration before "
    "the data is viewed, and fresh test windows (forward paper trading or data dated after today).",
    "Findings are the most consistent explanation of the observed results, not proven causes. These "
    "diagnostics are themselves multiple comparisons: read the individual numbers as descriptive.",
    "All results come from one dataset with a fixed ETF universe chosen today; survivorship is not controlled.",
    "Any close-fill implication from the mean-reversion event study is a new hypothesis, not tested.",
)

DATA_BIAS_TEXT: tuple[str, ...] = (
    "Dividends and T-bill interest are missing from this dataset. No dividend or yield series is "
    "fabricated here.",
    "Bounds as stated in the diagnostic brief (not recomputed): the momentum gap of about 9.5pp could "
    "shrink by at most about 3pp; the trend and mean_reversion gaps widen; cash interest of about "
    "4%/yr in the 2022-26 holdout is not enough for mean_reversion.",
    "Exact quantification requires external dividend and T-bill data, which needs approval before use.",
)


class DiagnosticsError(ResearchError):
    """A diagnostic invariant failed, such as a mismatch with the report."""


# ---------------------------------------------------------------------------
# Small numeric helpers
# ---------------------------------------------------------------------------


def _diff(a: float | None, b: float | None) -> float | None:
    if a is None or b is None:
        return None
    return a - b


def _mean(values: Sequence[float]) -> float | None:
    return statistics.fmean(values) if values else None


def _sharpe_annualized(values: Sequence[float]) -> float | None:
    return annualize_sharpe(sharpe_per_period(list(values)))


def _period(dates: Sequence[date]) -> dict[str, Any]:
    return {
        "observations": len(dates),
        "first": dates[0].isoformat() if dates else None,
        "last": dates[-1].isoformat() if dates else None,
    }


def _day_range(start: int, end: int, n_dates: int) -> range:
    """Realization indices scored by a path window (index 0 is never a return)."""
    return range(max(start, 1), min(end, n_dates))


def matched_mix_returns(spy_bars: Sequence[Bar], start: int, end: int, gross_exposure: float) -> list[float]:
    """Passive daily-rebalanced SPY + cash(0%) at constant gross exposure, frictionless.

    Return on day k is gross_exposure x (close_k / close_{k-1} - 1). The cash leg earns 0.
    Costs are excluded by design: this is the passive reference, not a trading simulation.
    """
    return [
        gross_exposure * (spy_bars[k].close / spy_bars[k - 1].close - 1.0)
        for k in _day_range(start, end, len(spy_bars))
    ]


def classify_exposure(strategy: float | None, matched: float | None, spy: float | None) -> str:
    if strategy is None or matched is None or spy is None:
        return "UNDEFINED: a CAGR is not defined for this window"
    if abs(strategy - matched) <= MATCHED_TOLERANCE:
        return "APPROXIMATELY_MATCHED: failure is under-exposure in a rising market, no timing skill"
    if strategy < matched:
        return "BELOW_MATCHED: timing destroyed value"
    if strategy < spy:
        return "ABOVE_MATCHED_BELOW_SPY: some timing value, insufficient to offset lower exposure"
    return "ABOVE_MATCHED_AND_SPY: no shortfall vs SPY at this window"


# ---------------------------------------------------------------------------
# Pipeline re-derivation (mirrors validation.run_validation step by step)
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class _Pipeline:
    dataset: Dataset
    dates: tuple[date, ...]
    bars: Mapping[str, Sequence[Bar]]
    split: Split
    benchmark: BacktestResult
    runs: Mapping[str, list[ConfigRun]]
    cost_rate: float


def _build_pipeline(dataset: Dataset, commission_bps: float, slippage_bps: float) -> _Pipeline:
    if BENCHMARK_SYMBOL not in dataset.bars:
        raise ResearchError(f"{BENCHMARK_SYMBOL} is required as the benchmark")
    strategies = build_strategies(dataset.symbols)
    universe = tuple(sorted({symbol for strategy in strategies for symbol in strategy.universe}))
    aligned = align(dataset, sorted(set(universe) | {BENCHMARK_SYMBOL}))
    if len(aligned.dates) < 2:
        raise ResearchError("aligned history has fewer than two dates")
    cost_rate = cost_rate_from_bps(commission_bps, slippage_bps)
    benchmark = simulate(aligned.bars, aligned.dates, {0: {BENCHMARK_SYMBOL: 1.0}}, cost_rate)
    runs: list[ConfigRun] = []
    for strategy in strategies:
        decisions = compute_decisions(strategy, aligned.bars, aligned.dates)
        runs.append(
            ConfigRun(
                strategy=strategy,
                family=strategy.family,
                config_id=strategy.config_id(),
                decisions=decisions,
                path=simulate(aligned.bars, aligned.dates, decisions, cost_rate),
            )
        )
    warmup = max(strategy.warmup_bars() for strategy in strategies)
    split = validation.build_split(len(aligned.dates), warmup)
    for run in runs:
        run.research_sharpe = sharpe_per_period(run.path.window_returns(split.warmup, split.research_end))
    grouped: dict[str, list[ConfigRun]] = {}
    for run in runs:
        grouped.setdefault(run.family, []).append(run)
    return _Pipeline(
        dataset=dataset,
        dates=tuple(aligned.dates),
        bars=aligned.bars,
        split=split,
        benchmark=benchmark,
        runs=grouped,
        cost_rate=cost_rate,
    )


def _stitched_decisions(choices: Sequence[tuple[FoldWindow, ConfigRun]]) -> dict[int, Weights]:
    """Decisions that ``validation.stitched_book`` simulates, returned instead of only the path."""
    decisions: dict[int, Weights] = {}
    previous_id: str | None = None
    for fold, chosen in choices:
        decisions.update(restrict_decisions(chosen.decisions, fold.test_start, fold.test_end))
        if chosen.config_id != previous_id:
            standing = validation._latest_decision(chosen.decisions, fold.test_start - 1)
            if standing is not None:
                decisions[fold.test_start - 1] = standing
        previous_id = chosen.config_id
    return decisions


def _positions(decisions: Mapping[int, Weights], n_dates: int) -> list[Weights]:
    """Target weights held during each realized day k (decision at k-1, executed at open k).

    Forward-filled once in O(n). Day 0 is never realized and stays empty.
    """
    positions: list[Weights] = [{} for _ in range(n_dates)]
    current: Weights = {}
    for k in range(1, n_dates):
        if k - 1 in decisions:
            current = dict(decisions[k - 1])
        positions[k] = current
    return positions


def _in_market(positions: Sequence[Weights]) -> list[bool]:
    return [sum(weights.values()) > EPSILON for weights in positions]


@dataclass(frozen=True)
class _Holdout:
    run: ConfigRun
    positions: list[Weights]


@dataclass(frozen=True)
class _FamilyView:
    name: str
    runs: list[ConfigRun]
    choices: list[tuple[FoldWindow, ConfigRun]]
    oos_start: int
    oos_end: int
    oos_path: BacktestResult
    oos_positions: list[Weights]
    holdout: _Holdout


def _family_view(pipeline: _Pipeline, name: str) -> _FamilyView:
    runs = pipeline.runs[name]
    choices = validation._select_folds(runs, pipeline.split)
    if not choices:
        raise DiagnosticsError("no walk-forward folds: diagnostics need at least one fold")
    oos_start, oos_end = validation._oos_range(choices)
    stitched = _stitched_decisions(choices)
    n = len(pipeline.dates)
    holdout_run = validation.pick_best(runs, lambda run: run.research_sharpe)
    return _FamilyView(
        name=name,
        runs=runs,
        choices=choices,
        oos_start=oos_start,
        oos_end=oos_end,
        oos_path=validation.stitched_book(choices, pipeline.bars, pipeline.dates, pipeline.cost_rate),
        oos_positions=_positions(stitched, n),
        holdout=_Holdout(holdout_run, _positions(holdout_run.decisions, n)),
    )


@dataclass(frozen=True)
class _Window:
    name: str
    start: int
    end: int
    strategy: PathWindow
    spy: PathWindow
    positions: Sequence[Weights]


def _windows(pipeline: _Pipeline, view: _FamilyView) -> tuple[_Window, _Window]:
    split = pipeline.split
    oos = _Window(
        name="OUT_OF_SAMPLE",
        start=view.oos_start,
        end=view.oos_end,
        strategy=path_window(view.oos_path, view.oos_start, view.oos_end),
        spy=path_window(pipeline.benchmark, view.oos_start, view.oos_end),
        positions=view.oos_positions,
    )
    holdout = _Window(
        name="HOLDOUT",
        start=split.holdout_start,
        end=split.holdout_end,
        strategy=path_window(view.holdout.run.path, split.holdout_start, split.holdout_end),
        spy=path_window(pipeline.benchmark, split.holdout_start, split.holdout_end),
        positions=view.holdout.positions,
    )
    return oos, holdout


def _reconcile(pipeline: _Pipeline, views: Mapping[str, _FamilyView], report: Mapping[str, Any]) -> dict[str, Any]:
    checked: dict[str, Any] = {}
    for name, view in views.items():
        oos, holdout = _windows(pipeline, view)
        ours_oos = validation._summary(oos.strategy, oos.spy)["total_return"]
        ours_holdout = validation._summary(holdout.strategy, holdout.spy)["total_return"]
        family = report["strategies"][name]
        theirs_oos = family["out_of_sample"]["strategy"]["total_return"]
        theirs_holdout = family["holdout"]["strategy"]["total_return"]
        if ours_oos != theirs_oos or ours_holdout != theirs_holdout:
            raise DiagnosticsError(f"{name}: re-derived totals disagree with the report")
        if [config.config_id for _, config in view.choices] != [
            fold["selected_config"] for fold in family["folds"]
        ]:
            raise DiagnosticsError(f"{name}: fold selections disagree with the report")
        if view.holdout.run.config_id != family["holdout_config"]:
            raise DiagnosticsError(f"{name}: holdout config disagrees with the report")
        checked[name] = {"oos_total_return": ours_oos, "holdout_total_return": ours_holdout}
    return {"matched": True, "checked": checked}


# ---------------------------------------------------------------------------
# Diagnostic 1: exposure-matched benchmark and CAPM
# ---------------------------------------------------------------------------


def _exposure_block(pipeline: _Pipeline, window: _Window) -> tuple[dict[str, Any], float | None, float | None]:
    """Returns (block, matched-mix CAGR, SPY CAGR) for the window."""
    strategy_returns = list(window.strategy.returns)
    spy_returns = list(window.spy.returns)
    gross = _mean(window.strategy.held_gross)
    matched = (
        [] if gross is None else matched_mix_returns(pipeline.bars[BENCHMARK_SYMBOL], window.start, window.end, gross)
    )
    strategy_cagr = cagr(strategy_returns)
    matched_cagr = cagr(matched)
    spy_cagr = cagr(spy_returns)
    beta, alpha = beta_alpha(strategy_returns, spy_returns)
    block = {
        "window": window.name,
        "period": _period(window.strategy.dates),
        "average_gross_exposure": gross,
        "strategy_cagr": strategy_cagr,
        "matched_mix_cagr": matched_cagr,
        "spy_cagr": spy_cagr,
        "strategy_minus_matched": _diff(strategy_cagr, matched_cagr),
        "strategy_minus_spy": _diff(strategy_cagr, spy_cagr),
        "beta_vs_spy": beta,
        "alpha_vs_spy_annualized": alpha,
        "classification": classify_exposure(strategy_cagr, matched_cagr, spy_cagr),
    }
    return block, matched_cagr, spy_cagr


# ---------------------------------------------------------------------------
# Diagnostic 2: gross vs net and breakeven cost
# ---------------------------------------------------------------------------


def rebased_returns(
    returns: Sequence[float], turnover: Sequence[float], base_cost: float, cost: float
) -> list[float]:
    """Exact engine re-cost with fixed decisions.

    The engine applies cost as the factor (1 - cost_rate x turnover) and nothing else depends
    on cost, so 1 + r(c) = (1 + r_base) x (1 - c x T) / (1 - c_base x T) is exact.
    """
    return [(1.0 + r) * (1.0 - cost * t) / (1.0 - base_cost * t) - 1.0 for r, t in zip(returns, turnover)]


def _cagr_at_cost(returns: Sequence[float], turnover: Sequence[float], base_cost: float, cost: float) -> float | None:
    return cagr(rebased_returns(returns, turnover, base_cost, cost))


def solve_breakeven_cost(
    cagr_at: Callable[[float], float | None], target: float | None, upper: float
) -> tuple[str, float | None]:
    """Largest cost rate at which CAGR(c) >= target, by bisection on a decreasing function."""
    if target is None:
        return "TARGET_UNDEFINED", None

    def value(cost: float) -> float:
        result = cagr_at(cost)
        return -math.inf if result is None else result

    if value(0.0) < target:
        return "ZERO_COST_BELOW_TARGET", None
    if value(upper) >= target:
        return "ABOVE_TARGET_AT_UPPER_BOUND", None
    lo, hi = 0.0, upper
    for _ in range(BISECTION_STEPS):
        mid = (lo + hi) / 2.0
        if value(mid) >= target:
            lo = mid
        else:
            hi = mid
    return "SOLVED", (lo + hi) / 2.0


def _gross_block(pipeline: _Pipeline, window: _Window, matched_cagr: float | None, spy_cagr: float | None) -> dict[str, Any]:
    returns = list(window.strategy.returns)
    turnover = list(window.strategy.turnover)
    base = pipeline.cost_rate
    gross = [r + base * t for r, t in zip(returns, turnover)]
    gross_cagr = cagr(gross)
    block: dict[str, Any] = {
        "window": window.name,
        "period": _period(window.strategy.dates),
        "net_cagr": cagr(returns),
        "gross_cagr_additive": gross_cagr,
        "net_sharpe_annualized": _sharpe_annualized(returns),
        "gross_sharpe_annualized": _sharpe_annualized(gross),
        "matched_mix_cagr": matched_cagr,
        "gross_minus_matched": _diff(gross_cagr, matched_cagr),
        "costs_rule": (
            "GROSS_BELOW_MATCHED: costs are not the cause"
            if gross_cagr is not None and matched_cagr is not None and gross_cagr < matched_cagr
            else "GROSS_AT_OR_ABOVE_MATCHED: costs may explain part of the shortfall"
        ),
    }
    if window.name == "OUT_OF_SAMPLE":
        def cagr_at(cost: float) -> float | None:
            return _cagr_at_cost(returns, turnover, base, cost)

        for key, target in (("breakeven_vs_matched", matched_cagr), ("breakeven_vs_spy", spy_cagr)):
            status, cost = solve_breakeven_cost(cagr_at, target, BREAKEVEN_UPPER_COST_RATE)
            block[key] = {
                "status": status,
                "cost_rate_per_unit_traded": cost,
                "bp_per_unit_traded": None if cost is None else cost * 10_000.0,
                "target_cagr": target,
            }
        block["zero_cost_cagr_exact"] = cagr_at(0.0)
        block["base_bp_per_unit_traded"] = base * 10_000.0
    return block


# ---------------------------------------------------------------------------
# Diagnostic 3: selection vs hypothesis, CSCV PBO, hindsight
# ---------------------------------------------------------------------------


def _sharpe_from_sums(count: int, total: float, total_sq: float) -> float | None:
    """Per-period Sharpe from pooled sums. A (near-)zero pooled variance is undefined."""
    if count < 2:
        return None
    mean = total / count
    mean_square = total_sq / count
    variance = (total_sq - count * mean * mean) / (count - 1)
    if variance <= 1e-14 * mean_square or variance <= 0.0:
        return None
    return mean / math.sqrt(variance)


def cscv_pbo(returns_by_config: Mapping[str, Sequence[float]], blocks: int = CSCV_BLOCKS) -> dict[str, Any]:
    """Probability of backtest overfitting (Bailey, Borwein, Lopez de Prado, Zhu 2017), CSCV.

    The T x N return matrix is cut into ``blocks`` contiguous row blocks. For every choice of
    blocks/2 as in-sample, the best in-sample config (per-period Sharpe; undefined ranks last;
    ties go to the earlier config) is ranked among all configs out of sample. lambda =
    logit(rank / (N + 1)) with mid-ranks for ties. PBO = share of combinations with lambda <= 0.
    Undefined in-sample combinations are skipped and counted.
    """
    names = list(returns_by_config)
    if not names:
        raise ResearchError("CSCV needs at least one config")
    series = [list(returns_by_config[name]) for name in names]
    length = len(series[0])
    if any(len(values) != length for values in series):
        raise ResearchError("CSCV configs must share one return length")
    if blocks < 2 or blocks % 2 or length < blocks:
        raise ResearchError("CSCV needs an even block count not larger than the series length")
    base, extra = divmod(length, blocks)
    bounds: list[tuple[int, int]] = []
    start = 0
    for index in range(blocks):
        size = base + (1 if index < extra else 0)
        bounds.append((start, start + size))
        start += size
    block_n = [hi - lo for lo, hi in bounds]
    block_s1 = [[math.fsum(values[lo:hi]) for lo, hi in bounds] for values in series]
    block_s2 = [[math.fsum(x * x for x in values[lo:hi]) for lo, hi in bounds] for values in series]
    n_configs = len(names)
    all_blocks = range(blocks)

    combinations = 0
    undefined = 0
    pbo_count = 0
    tied_or_undefined = 0
    logits: list[float] = []
    for in_sample in itertools.combinations(all_blocks, blocks // 2):
        out_sample = [b for b in all_blocks if b not in in_sample]
        n_in = sum(block_n[b] for b in in_sample)
        n_out = length - n_in
        is_sharpe: list[float | None] = []
        oos_sharpe: list[float | None] = []
        for c in range(n_configs):
            is_sharpe.append(
                _sharpe_from_sums(
                    n_in,
                    sum(block_s1[c][b] for b in in_sample),
                    sum(block_s2[c][b] for b in in_sample),
                )
            )
            oos_sharpe.append(
                _sharpe_from_sums(
                    n_out,
                    sum(block_s1[c][b] for b in out_sample),
                    sum(block_s2[c][b] for b in out_sample),
                )
            )
        best: int | None = None
        best_value: float | None = None
        for index, value in enumerate(is_sharpe):
            if value is None:
                continue
            if best is None or best_value is None or value > best_value:
                best, best_value = index, value
        combinations += 1
        if best is None:
            undefined += 1
            continue
        keys = [-math.inf if value is None else value for value in oos_sharpe]
        chosen_key = keys[best]
        less = sum(1 for key in keys if key < chosen_key)
        equal = sum(1 for key in keys if key == chosen_key)
        rank = less + (equal + 1) / 2.0
        omega = rank / (n_configs + 1)
        logit = math.log(omega / (1.0 - omega))
        logits.append(logit)
        if logit <= 0.0:
            pbo_count += 1
        if oos_sharpe[best] is None or equal > 1:
            tied_or_undefined += 1
    evaluated = len(logits)
    return {
        "configs": n_configs,
        "block_count": blocks,
        "combinations_total": combinations,
        "combinations_undefined_in_sample": undefined,
        "pbo": pbo_count / evaluated if evaluated else None,
        "pbo_above_threshold": (pbo_count / evaluated > PBO_THRESHOLD) if evaluated else None,
        "median_logit": statistics.median(logits) if logits else None,
        "selected_oos_tied_or_undefined_fraction": tied_or_undefined / evaluated if evaluated else None,
        "convention": (
            "lambda <= 0 counts as overfit; undefined Sharpe ranks last; ties use mid-rank; "
            "in-sample ties go to the earlier grid position"
        ),
    }


def _selection_block(view: _FamilyView) -> dict[str, Any]:
    folds: list[dict[str, Any]] = []
    switches = 0
    previous: str | None = None
    for fold, chosen in view.choices:
        train = sharpe_per_period(chosen.path.window_returns(fold.train_start, fold.train_end))
        test = sharpe_per_period(list(path_window(chosen.path, fold.test_start, fold.test_end).returns))
        switched = previous is not None and chosen.config_id != previous
        switches += int(switched)
        previous = chosen.config_id
        folds.append(
            {
                "fold": fold.index,
                "selected_config": chosen.config_id,
                "train_sharpe_annualized": annualize_sharpe(train),
                "test_sharpe_annualized": annualize_sharpe(test),
                "degradation_ratio_test_over_train": (
                    test / train if train is not None and test is not None and train > 0.0 else None
                ),
                "switched_from_previous_fold": switched,
            }
        )
    return {"folds": folds, "config_switches": switches, "fold_transitions": max(len(folds) - 1, 0)}


def _hindsight_block(pipeline: _Pipeline, runs: Sequence[ConfigRun]) -> dict[str, Any]:
    start, end = pipeline.split.warmup, len(pipeline.dates)

    def annual(run: ConfigRun) -> float | None:
        return _sharpe_annualized(path_window(run.path, start, end).returns)

    best = validation.pick_best(list(runs), lambda run: sharpe_per_period(path_window(run.path, start, end).returns))
    spy_sharpe = _sharpe_annualized(path_window(pipeline.benchmark, start, end).returns)
    best_sharpe = annual(best)
    return {
        "label": HINDSIGHT_LABEL,
        "window": _period(path_window(best.path, start, end).dates),
        "window_definition": "realized days from the first post-warm-up day to the end of the aligned data (includes holdout)",
        "best_config": best.config_id,
        "best_sharpe_annualized": best_sharpe,
        "spy_sharpe_annualized": spy_sharpe,
        "best_below_spy": (best_sharpe < spy_sharpe) if best_sharpe is not None and spy_sharpe is not None else None,
    }


# ---------------------------------------------------------------------------
# Diagnostic 4: regime and capture
# ---------------------------------------------------------------------------


def sma_regime(bars: Sequence[Bar], window: int = SMA_LONG_WINDOW) -> list[bool | None]:
    """Bull flag for realized day k: close_{k-1} > SMA(window) of closes through k-1.

    Uses only information available at the close of k-1, so labels for day k never read
    closes at or after day k. None before enough history exists.
    """
    closes = [bar.close for bar in bars]
    labels: list[bool | None] = [None] * len(closes)
    for k in range(window, len(closes)):
        average = sum(closes[k - window : k]) / window
        labels[k] = closes[k - 1] > average
    return labels


def _capture(strategy_monthly: Mapping[str, float], spy_monthly: Mapping[str, float]) -> dict[str, float | None]:
    keys = sorted(set(strategy_monthly) & set(spy_monthly))
    up = [key for key in keys if spy_monthly[key] > 0.0]
    down = [key for key in keys if spy_monthly[key] < 0.0]

    def ratio(selected: list[str]) -> float | None:
        if not selected:
            return None
        spy_mean = statistics.fmean(spy_monthly[key] for key in selected)
        if spy_mean == 0.0:
            return None
        return statistics.fmean(strategy_monthly[key] for key in selected) / spy_mean

    return {"upside_capture": ratio(up), "downside_capture": ratio(down), "up_months": len(up), "down_months": len(down)}


def _regime_stats(window: _Window, labels: Sequence[bool | None], n_dates: int) -> dict[str, Any]:
    rows = list(_day_range(window.start, window.end, n_dates))
    base = max(window.start, 1)
    buckets: dict[str, list[tuple[float, float, bool]]] = {"BULL": [], "BEAR": []}
    for k in rows:
        label = labels[k]
        if label is None:
            continue
        j = k - base
        key = "BULL" if label else "BEAR"
        buckets[key].append(
            (window.strategy.returns[j], window.spy.returns[j], sum(window.positions[k].values()) > EPSILON)
        )
    stats: dict[str, Any] = {}
    for key, items in buckets.items():
        if not items:
            stats[key] = {"days": 0}
            continue
        stats[key] = {
            "days": len(items),
            "strategy_annualized_mean": statistics.fmean(s for s, _, _ in items) * 252,
            "spy_annualized_mean": statistics.fmean(p for _, p, _ in items) * 252,
            "in_market_fraction": sum(1 for _, _, held in items if held) / len(items),
        }
    return stats


def _top_spy_days_out_of_market(window: _Window, n_dates: int) -> dict[str, Any]:
    """Share of the window's top-1% SPY return days on which the book held nothing."""
    days = list(_day_range(window.start, window.end, n_dates))
    if not days:
        return {"top_days": 0, "out_of_market_fraction": None}
    base = max(window.start, 1)
    spy = [window.spy.returns[k - base] for k in days]
    held = [sum(window.positions[k].values()) > EPSILON for k in days]
    top_n = max(1, math.ceil(len(days) * TOP_TAIL_FRACTION))
    order = sorted(range(len(days)), key=lambda j: (-spy[j], days[j]))[:top_n]
    out = sum(1 for j in order if not held[j])
    return {"top_days": top_n, "out_of_market_fraction": out / top_n}


def _compound(values: Iterable[float]) -> float:
    total = 1.0
    for value in values:
        total *= 1.0 + value
    return total - 1.0


def _crisis_block(window: _Window) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for label, start_text, end_text in CRISIS_WINDOWS:
        start, end = date.fromisoformat(start_text), date.fromisoformat(end_text)
        picks = [j for j, day in enumerate(window.strategy.dates) if start <= day <= end]
        if not picks:
            out[label] = {"days": 0}
            continue
        out[label] = {
            "window": f"{start_text}..{end_text}",
            "days": len(picks),
            "strategy_return": _compound(window.strategy.returns[j] for j in picks),
            "spy_return": _compound(window.spy.returns[j] for j in picks),
        }
    return out


def _yearly(pairs: Iterable[tuple[date, float]]) -> dict[int, tuple[float, int]]:
    buckets: dict[int, list[float]] = {}
    for day, value in pairs:
        buckets.setdefault(day.year, []).append(value)
    return {year: (_compound(values), len(values)) for year, values in buckets.items()}


def _regime_block(pipeline: _Pipeline, view: _FamilyView, oos: _Window, holdout: _Window) -> dict[str, Any]:
    labels = sma_regime(pipeline.bars[BENCHMARK_SYMBOL])
    n = len(pipeline.dates)
    out: dict[str, Any] = {}
    for window in (oos, holdout):
        monthly_strategy = monthly_returns(window.strategy.dates, window.strategy.returns)
        monthly_spy = monthly_returns(window.spy.dates, window.spy.returns)
        out[window.name] = {
            "capture": _capture(monthly_strategy, monthly_spy),
            "bull_bear": _regime_stats(window, labels, n),
            "top_one_percent_spy_days": _top_spy_days_out_of_market(window, n),
            "crisis_windows": _crisis_block(window),
        }
    yearly_strategy = _yearly(
        list(zip(oos.strategy.dates, oos.strategy.returns)) + list(zip(holdout.strategy.dates, holdout.strategy.returns))
    )
    yearly_spy = _yearly(list(zip(oos.spy.dates, oos.spy.returns)) + list(zip(holdout.spy.dates, holdout.spy.returns)))
    out["calendar_years"] = [
        {
            "year": year,
            "days": yearly_strategy[year][1],
            "strategy": yearly_strategy[year][0],
            "spy": yearly_spy[year][0],
        }
        for year in sorted(yearly_strategy)
    ]
    return out


# ---------------------------------------------------------------------------
# Diagnostic 5: mean-reversion event study
# ---------------------------------------------------------------------------


def _new_raw() -> dict[str, list[float]]:
    keys = ["base_on", "base_id", "sig_on", "sig_id", "trade_net", "trade_gross"]
    keys += [f"base_fwd_{h}" for h in FORWARD_HORIZONS] + [f"sig_fwd_{h}" for h in FORWARD_HORIZONS]
    return {key: [] for key in keys}


def _event_raw(
    spy: Sequence[Bar],
    path: BacktestResult,
    positions: Sequence[Weights],
    window: tuple[int, int],
    cost_rate: float,
    raw: dict[str, list[float]],
    truncated: list[int],
) -> None:
    n = len(spy)
    in_market = _in_market(positions)
    close = [bar.close for bar in spy]
    open_ = [bar.open for bar in spy]
    lo = max(window[0], 0)
    for t in range(lo, min(window[1], n - 1)):
        for h in FORWARD_HORIZONS:
            if t + h <= n - 1:
                raw[f"base_fwd_{h}"].append(close[t + h] / close[t] - 1.0)
        overnight = open_[t + 1] / close[t] - 1.0
        intraday = close[t + 1] / open_[t + 1] - 1.0
        raw["base_on"].append(overnight)
        raw["base_id"].append(intraday)
        if not (in_market[t + 1] and not in_market[t]):
            continue
        for h in FORWARD_HORIZONS:
            if t + h <= n - 1:
                raw[f"sig_fwd_{h}"].append(close[t + h] / close[t] - 1.0)
        raw["sig_on"].append(overnight)
        raw["sig_id"].append(intraday)
        exit_day = None
        for k in range(t + 2, n):
            if not in_market[k]:
                exit_day = k
                break
        last = (exit_day if exit_day is not None else n - 1)
        if exit_day is None:
            truncated[0] += 1
        days = range(t + 1, last + 1)
        net = _compound(path.returns[k - 1] for k in days)
        gross = _compound(path.returns[k - 1] + cost_rate * path.turnover[k - 1] for k in days)
        raw["trade_net"].append(net)
        raw["trade_gross"].append(gross)


def _event_summary(raw: Mapping[str, list[float]], truncated: int) -> dict[str, Any]:
    forward: dict[str, Any] = {}
    for h in FORWARD_HORIZONS:
        signal = _mean(raw[f"sig_fwd_{h}"])
        base = _mean(raw[f"base_fwd_{h}"])
        forward[f"+{h}d"] = {
            "signal_mean": signal,
            "unconditional_mean": base,
            "signal_minus_unconditional": _diff(signal, base),
            "signal_observations": len(raw[f"sig_fwd_{h}"]),
        }
    nets = raw["trade_net"]
    grosses = raw["trade_gross"]
    return {
        "signals": len(raw["sig_on"]),
        "unconditional_observations": len(raw["base_on"]),
        "forward_spy_returns": forward,
        "overnight_close_to_open": {
            "signal_mean": _mean(raw["sig_on"]),
            "unconditional_mean": _mean(raw["base_on"]),
        },
        "intraday_open_to_close": {
            "signal_mean": _mean(raw["sig_id"]),
            "unconditional_mean": _mean(raw["base_id"]),
        },
        "trades": {
            "count": len(nets),
            "hit_rate": (sum(1 for value in nets if value > 0.0) / len(nets)) if nets else None,
            "average_gross": _mean(grosses),
            "average_net": _mean(nets),
            "open_at_data_end": truncated,
        },
    }


def _event_block(pipeline: _Pipeline, run: ConfigRun, window: tuple[int, int], positions: Sequence[Weights]) -> dict[str, Any]:
    raw = _new_raw()
    truncated = [0]
    _event_raw(pipeline.bars[BENCHMARK_SYMBOL], run.path, positions, window, pipeline.cost_rate, raw, truncated)
    return {"config": run.config_id, "window": list(window), "raw": raw, "truncated": truncated[0]}


def _mean_reversion_block(pipeline: _Pipeline, view: _FamilyView) -> dict[str, Any]:
    n = len(pipeline.dates)
    holdout_run = view.holdout.run
    holdout_block = _event_block(
        pipeline, holdout_run, (pipeline.split.holdout_start, pipeline.split.holdout_end), view.holdout.positions
    )
    fold_blocks: list[dict[str, Any]] = []
    pooled = _new_raw()
    pooled_truncated = 0
    for fold, chosen in view.choices:
        positions = _positions(chosen.decisions, n)
        block = _event_block(pipeline, chosen, (fold.test_start, fold.test_end), positions)
        for key, values in block["raw"].items():
            pooled[key].extend(values)
        pooled_truncated += block["truncated"]
        fold_blocks.append(
            {
                "fold": fold.index,
                "test_window": _period(pipeline.dates[fold.test_start : fold.test_end]),
                "config": chosen.config_id,
                "summary": _event_summary(block["raw"], block["truncated"]),
            }
        )
    return {
        "holdout": {
            "config": holdout_run.config_id,
            "window": _period(pipeline.dates[pipeline.split.holdout_start : pipeline.split.holdout_end]),
            "summary": _event_summary(holdout_block["raw"], holdout_block["truncated"]),
        },
        "folds": fold_blocks,
        "pooled_over_fold_test_windows": _event_summary(pooled, pooled_truncated),
        "note": (
            "Forward returns use SPY closes. Overnight is close(t) to open(t+1); intraday is open(t+1) to close(t+1). "
            "Trades compound realized daily returns from the entry day through the exit day. "
            "Any close-fill implication is a new hypothesis, not tested."
        ),
    }


# ---------------------------------------------------------------------------
# Diagnostic 6: momentum attribution
# ---------------------------------------------------------------------------


def _momentum_block(pipeline: _Pipeline, view: _FamilyView) -> dict[str, Any]:
    symbols = list(view.runs[0].strategy.universe)
    n = len(pipeline.dates)
    days = list(_day_range(view.oos_start, view.oos_end, n))
    if not days:
        raise DiagnosticsError("momentum out-of-sample window is empty")
    per_symbol: dict[str, dict[str, Any]] = {}
    contributions = {symbol: 0.0 for symbol in symbols}
    held_days = {symbol: 0 for symbol in symbols}
    book_symbols = sorted(pipeline.bars)
    path = view.oos_path
    for k in days:
        for symbol in symbols:
            if view.oos_positions[k].get(symbol, 0.0) > EPSILON:
                held_days[symbol] += 1
        # Exact engine decomposition, first order in the overnight move. Post-trade weights are
        # recovered from the holdings identity h_i = new_i x close / open / (1 + intraday).
        now, before = path.weights[k], path.weights[k - 1]
        open_close = {s: pipeline.bars[s][k].open / pipeline.bars[s][k].close for s in book_symbols}
        intraday_factor = sum(now.get(s, 0.0) * (1.0 - open_close[s]) for s in book_symbols)
        intraday_return = intraday_factor / (1.0 - intraday_factor)
        for symbol in symbols:
            overnight = before.get(symbol, 0.0) * (
                pipeline.bars[symbol][k].open / pipeline.bars[symbol][k - 1].close - 1.0
            )
            intraday = now.get(symbol, 0.0) * (1.0 + intraday_return) * (1.0 - open_close[symbol])
            contributions[symbol] += overnight + intraday
    for symbol in symbols:
        per_symbol[symbol] = {
            "fraction_of_oos_days_held": held_days[symbol] / len(days),
            "approx_return_contribution": contributions[symbol],
        }
    eligible = 0
    excluded = 0
    if "QQQ" not in pipeline.bars:
        raise DiagnosticsError("QQQ is required for the momentum best-of SPY/QQQ attribution")
    spy_close = [bar.close for bar in pipeline.bars[BENCHMARK_SYMBOL]]
    qqq_close = [bar.close for bar in pipeline.bars["QQQ"]]
    for k in days:
        t = k - 1  # decision close; the held set on day k was chosen here
        if t + ETF_FORWARD_SESSIONS > n - 1:
            continue
        forward_spy = spy_close[t + ETF_FORWARD_SESSIONS] / spy_close[t] - 1.0
        forward_qqq = qqq_close[t + ETF_FORWARD_SESSIONS] / qqq_close[t] - 1.0
        best = BENCHMARK_SYMBOL if forward_spy >= forward_qqq else "QQQ"
        eligible += 1
        if view.oos_positions[k].get(best, 0.0) <= EPSILON:
            excluded += 1
    oos_window = path_window(view.oos_path, view.oos_start, view.oos_end)
    net_sum = math.fsum(oos_window.returns)
    cost_sum = pipeline.cost_rate * math.fsum(oos_window.turnover)
    contribution_sum = sum(item["approx_return_contribution"] for item in per_symbol.values())
    return {
        "window": _period(pipeline.dates[view.oos_start : view.oos_end]),
        "per_symbol": per_symbol,
        "sum_of_contributions": contribution_sum,
        "sum_of_trading_costs": cost_sum,
        "sum_of_strategy_daily_net_returns": net_sum,
        "residual_contributions_minus_costs_minus_net": contribution_sum - cost_sum - net_sum,
        "contribution_note": (
            "contribution = overnight (prior-close book weight x open/close gap) + intraday (post-trade weight x "
            "open-to-close move), summed arithmetically; ignores compounding and the cross term. Trading costs "
            "are reported as a separate arithmetic total"
        ),
        "best_of_spy_qqq_exclusion": {
            "eligible_days": eligible,
            "excluded_days": excluded,
            "excluded_fraction": excluded / eligible if eligible else None,
            "rule": f"best = higher {ETF_FORWARD_SESSIONS}-session forward return from the decision close; excluded if its target weight is 0",
        },
    }


# ---------------------------------------------------------------------------
# Assembly
# ---------------------------------------------------------------------------


def _family_result(pipeline: _Pipeline, view: _FamilyView) -> dict[str, Any]:
    oos, holdout = _windows(pipeline, view)
    oos_exposure, oos_matched, oos_spy = _exposure_block(pipeline, oos)
    holdout_exposure, holdout_matched, holdout_spy = _exposure_block(pipeline, holdout)
    result: dict[str, Any] = {
        "exposure_matched": {"out_of_sample": oos_exposure, "holdout": holdout_exposure},
        "gross_vs_net": {
            "out_of_sample": _gross_block(pipeline, oos, oos_matched, oos_spy),
            "holdout": _gross_block(pipeline, holdout, holdout_matched, holdout_spy),
        },
        "selection": {
            **_selection_block(view),
            "holdout_config": view.holdout.run.config_id,
        },
        "regime_capture": _regime_block(pipeline, view, oos, holdout),
    }
    if view.name == "mean_reversion":
        result["mean_reversion_events"] = _mean_reversion_block(pipeline, view)
    if view.name == "momentum":
        result["momentum_attribution"] = _momentum_block(pipeline, view)
    return result


def _selection_pbo(pipeline: _Pipeline) -> dict[str, Any]:
    research = {}
    for family in FAMILIES:
        for run in pipeline.runs[family]:
            research[run.config_id] = list(
                run.path.window_returns(pipeline.split.warmup, pipeline.split.research_end)
            )
    per_family = {
        family: cscv_pbo(
            {run.config_id: research[run.config_id] for run in pipeline.runs[family]}
        )
        for family in FAMILIES
    }
    per_family["all_configs"] = cscv_pbo({key: research[key] for key in research})
    return per_family


def run_diagnostics(
    data_dir: str | Path,
    *,
    commission_bps: float = 10.0,
    slippage_bps: float = 5.0,
) -> dict[str, Any]:
    dataset = load_dataset(data_dir)
    result = validation.run_validation(dataset, commission_bps=commission_bps, slippage_bps=slippage_bps)
    report = build_report(dataset, result, commission_bps=commission_bps, slippage_bps=slippage_bps)
    pipeline = _build_pipeline(dataset, commission_bps, slippage_bps)
    views = {family: _family_view(pipeline, family) for family in FAMILIES}
    reconciliation = _reconcile(pipeline, views, report)

    families: dict[str, Any] = {}
    for family in FAMILIES:
        families[family] = _family_result(pipeline, views[family])
    cscv = _selection_pbo(pipeline)
    for family in FAMILIES:
        families[family]["selection"]["hindsight"] = _hindsight_block(pipeline, pipeline.runs[family])
    return {
        "label": DIAGNOSTICS_LABEL,
        "generated_by": GENERATED_BY,
        "dataset_hash": report["dataset"]["dataset_hash"],
        "config_hash": report["config_hash"],
        "costs": {
            "commission_bps": commission_bps,
            "slippage_bps": slippage_bps,
            "cost_rate_per_unit_traded": pipeline.cost_rate,
        },
        "adjustment_basis": dataset.provenance["adjustment_basis"],
        "split": {
            "warmup_bars": pipeline.split.warmup,
            "research_realization_range": [pipeline.split.warmup, pipeline.split.research_end],
            "holdout_realization_range": [pipeline.split.holdout_start, pipeline.split.holdout_end],
            "folds": len(pipeline.split.folds),
            "aligned_dates": len(pipeline.dates),
        },
        "reconciliation": reconciliation,
        "pre_declared": [
            {"diagnostic": row[0], "cause_tested": row[1], "interpretation_rule": row[2]} for row in PRE_DECLARED
        ],
        "families": families,
        "selection_cscv": cscv,
        "diagnostic_7_data_bias": {
            "dividends_and_tbill_interest_missing": True,
            "text": list(DATA_BIAS_TEXT),
        },
        "caveats": list(CAVEATS),
    }


# ---------------------------------------------------------------------------
# Markdown rendering
# ---------------------------------------------------------------------------


def _pct(value: Any) -> str:
    if value is None:
        return "n/a"
    return f"{value * 100:.2f}%"


def _pp(value: Any) -> str:
    if value is None:
        return "n/a"
    return f"{value * 100:+.2f}pp"


def _num(value: Any) -> str:
    if value is None:
        return "n/a"
    if isinstance(value, float):
        return f"{value:.4f}"
    return str(value)


def _table(headers: Sequence[str], rows: Sequence[Sequence[str]]) -> list[str]:
    lines = ["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)]
    lines.extend("| " + " | ".join(row) + " |" for row in rows)
    return lines


def render_markdown(diag: Mapping[str, Any]) -> str:
    families = diag["families"]
    split = diag["split"]
    lines: list[str] = [
        "# Failure diagnostics",
        "",
        f"_{diag['label']}_",
        "",
        f"- dataset_hash: `{diag['dataset_hash']}`",
        f"- config_hash: `{diag['config_hash']}`",
        f"- adjustment_basis: `{diag['adjustment_basis']}`",
        f"- costs: commission {diag['costs']['commission_bps']} bps, slippage {diag['costs']['slippage_bps']} bps "
        f"(cost rate {diag['costs']['cost_rate_per_unit_traded']} per unit traded)",
        f"- research realization range: {split['research_realization_range'][0]} to {split['research_realization_range'][1]}; "
        f"holdout: {split['holdout_realization_range'][0]} to {split['holdout_realization_range'][1]}; "
        f"folds: {split['folds']}",
        f"- reconciliation with report.build_report: {'matched' if diag['reconciliation']['matched'] else 'MISMATCH'}",
        "",
        "## Pre-declared diagnostics",
        "",
    ]
    lines.extend(_table(["Diagnostic", "Cause tested", "Interpretation rule"], [
        [row["diagnostic"], row["cause_tested"], row["interpretation_rule"]] for row in diag["pre_declared"]
    ]))
    lines.extend(["", "## Results", "", "### 1. Exposure-matched benchmark and CAPM", ""])
    rows = []
    for family in FAMILIES:
        for window_key in ("out_of_sample", "holdout"):
            block = families[family]["exposure_matched"][window_key]
            rows.append([
                family, window_key, _pct(block["strategy_cagr"]), _pct(block["matched_mix_cagr"]),
                _pct(block["spy_cagr"]), _pct(block["average_gross_exposure"]),
                _pp(block["strategy_minus_matched"]), _pp(block["strategy_minus_spy"]),
                _num(block["beta_vs_spy"]), _pct(block["alpha_vs_spy_annualized"]), block["classification"],
            ])
    lines.extend(_table(
        ["Family", "Window", "Strategy CAGR", "Matched mix CAGR", "SPY CAGR", "Avg gross",
         "Strategy - matched", "Strategy - SPY", "Beta", "Alpha (ann.)", "Classification"],
        rows,
    ))
    lines.extend(["", "### 2. Gross vs net and breakeven cost", ""])
    rows = []
    for family in FAMILIES:
        for window_key in ("out_of_sample", "holdout"):
            block = families[family]["gross_vs_net"][window_key]
            be_m = block.get("breakeven_vs_matched", {})
            be_s = block.get("breakeven_vs_spy", {})
            rows.append([
                family, window_key, _pct(block["net_cagr"]), _pct(block["gross_cagr_additive"]),
                _num(block["net_sharpe_annualized"]), _num(block["gross_sharpe_annualized"]),
                block["costs_rule"],
                _breakeven_cell(be_m), _breakeven_cell(be_s),
            ])
    lines.extend(_table(
        ["Family", "Window", "Net CAGR", "Gross CAGR (additive)", "Net Sharpe", "Gross Sharpe",
         "Gross vs matched rule", "Breakeven vs matched (bp/unit)", "Breakeven vs SPY (bp/unit)"],
        rows,
    ))
    lines.append("")
    lines.append(
        f"Base cost: {diag['costs']['commission_bps'] + diag['costs']['slippage_bps']:.1f} bp per unit traded. "
        "Breakeven solves the engine-exact cost model with fixed decisions; the gross column uses the additive "
        "identity gross = net + cost x turnover from the brief."
    )
    lines.append(
        "ZERO_COST_BELOW_TARGET: the exact zero-cost CAGR is already below the target, so no cost level can close "
        "the gap. ABOVE_TARGET_AT_UPPER_BOUND: the target is still beaten at 500 bp per unit traded. "
        "Breakevens are computed for OOS only; holdout is shown for gross and net."
    )
    lines.extend(["", "### 3. Selection vs hypothesis", ""])
    for family in FAMILIES:
        selection = families[family]["selection"]
        lines.extend([f"#### {family}", ""])
        fold_rows = [
            [str(fold["fold"]), fold["selected_config"], _num(fold["train_sharpe_annualized"]),
             _num(fold["test_sharpe_annualized"]), _num(fold["degradation_ratio_test_over_train"]),
             "yes" if fold["switched_from_previous_fold"] else "no"]
            for fold in selection["folds"]
        ]
        lines.extend(_table(
            ["Fold", "Selected config", "Train Sharpe (ann.)", "Test Sharpe (ann.)", "Test / train", "Switched"],
            fold_rows,
        ))
        lines.append("")
        lines.append(
            f"Config switches: {selection['config_switches']} of {selection['fold_transitions']} fold transitions. "
            f"Holdout config (chosen on whole research window): `{selection['holdout_config']}`."
        )
        lines.append("")
    cscv_rows = []
    for key in (*FAMILIES, "all_configs"):
        cscv = diag["selection_cscv"][key]
        cscv_rows.append([
            key, str(cscv["configs"]), str(cscv["combinations_total"]), _num(cscv["pbo"]),
            _num(cscv["median_logit"]), _pct(cscv["selected_oos_tied_or_undefined_fraction"]),
            str(cscv["combinations_undefined_in_sample"]),
        ])
    lines.extend(["#### CSCV probability of backtest overfitting (research window, holdout excluded)", ""])
    lines.extend(_table(
        ["Set", "N configs", "Combinations", "PBO", "Median logit", "Selected OOS tied/undefined", "Undefined IS"],
        cscv_rows,
    ))
    lines.append("")
    lines.append(f"Convention: {diag['selection_cscv']['all_configs']['convention']}.")
    lines.extend(["", "#### Hindsight best per family (LOOK-AHEAD, NOT A CANDIDATE)", ""])
    hind_rows = []
    for family in FAMILIES:
        block = families[family]["selection"]["hindsight"]
        hind_rows.append([
            family, f"`{block['best_config']}`", _num(block["best_sharpe_annualized"]),
            _num(block["spy_sharpe_annualized"]), "yes" if block["best_below_spy"] else "no",
        ])
    lines.extend(_table(
        ["Family", "Best config", "Best Sharpe (ann.)", "SPY Sharpe (ann.)", "Below SPY"],
        hind_rows,
    ))
    lines.append("")
    lines.append(f"Window: {families['momentum']['selection']['hindsight']['window_definition']}.")
    lines.extend(["", "### 4. Regime and capture", ""])
    capture_rows = []
    for family in FAMILIES:
        regime = families[family]["regime_capture"]
        for window_key in ("OUT_OF_SAMPLE", "HOLDOUT"):
            block = regime[window_key]
            capture_rows.append([
                family, window_key, _num(block["capture"]["upside_capture"]),
                _num(block["capture"]["downside_capture"]),
                _pct(block["top_one_percent_spy_days"]["out_of_market_fraction"]),
                _pct(block["bull_bear"]["BULL"].get("strategy_annualized_mean")),
                _pct(block["bull_bear"]["BULL"].get("spy_annualized_mean")),
                _pct(block["bull_bear"]["BEAR"].get("strategy_annualized_mean")),
                _pct(block["bull_bear"]["BEAR"].get("spy_annualized_mean")),
            ])
    lines.extend(_table(
        ["Family", "Window", "Upside capture", "Downside capture", "Top-1% days out of market",
         "Bull strat mean (ann.)", "Bull SPY mean (ann.)", "Bear strat mean (ann.)", "Bear SPY mean (ann.)"],
        capture_rows,
    ))
    lines.append("")
    crisis_rows = []
    for family in FAMILIES:
        block = families[family]["regime_capture"]["OUT_OF_SAMPLE"]["crisis_windows"]
        for label, _, _ in CRISIS_WINDOWS:
            item = block[label]
            crisis_rows.append([
                family, label, str(item.get("days", 0)), _pct(item.get("strategy_return")), _pct(item.get("spy_return")),
            ])
    lines.extend(_table(["Family", "Window", "OOS days", "Strategy return", "SPY return"], crisis_rows))
    lines.extend(["", "Calendar-year returns (OOS and holdout days only; each family uses its own stitched OOS book and holdout config):", ""])
    years = families["momentum"]["regime_capture"]["calendar_years"]
    year_rows = []
    for item in years:
        row = [str(item["year"]), str(item["days"]), _pct(item["spy"])]
        for family in FAMILIES:
            match = next(x for x in families[family]["regime_capture"]["calendar_years"] if x["year"] == item["year"])
            row.append(_pct(match["strategy"]))
        year_rows.append(row)
    lines.extend(_table(["Year", "Scored days", "SPY", "Momentum", "Trend", "Mean reversion"], year_rows))
    lines.extend(["", "### 5. Mean-reversion event study", ""])
    mr = families["mean_reversion"]["mean_reversion_events"]
    event_rows = []
    for label, summary in (
        (f"holdout `{mr['holdout']['config']}`", mr["holdout"]["summary"]),
        ("pooled fold configs (fold test windows)", mr["pooled_over_fold_test_windows"]),
    ):
        event_rows.append(_event_row(label, summary))
    for fold in mr["folds"]:
        event_rows.append(_event_row(f"fold {fold['fold']} `{fold['config']}`", fold["summary"]))
    lines.extend(_table(
        ["Set", "Signals", "+1d signal vs uncond.", "+3d signal vs uncond.", "+5d signal vs uncond.",
         "Overnight signal / uncond.", "Intraday signal / uncond.", "Hit rate", "Avg gross", "Avg net"],
        event_rows,
    ))
    lines.extend(["", mr["note"], ""])
    lines.extend(["### 6. Momentum attribution (OOS)", ""])
    momentum = families["momentum"]["momentum_attribution"]
    attrib_rows = [
        [symbol, _pct(item["fraction_of_oos_days_held"]), _pct(item["approx_return_contribution"])]
        for symbol, item in momentum["per_symbol"].items()
    ]
    lines.extend(_table(["ETF", "Fraction of OOS days held", "Approx. contribution (arithmetic)"], attrib_rows))
    exclusion = momentum["best_of_spy_qqq_exclusion"]
    lines.extend([
        "",
        f"Sum of contributions: {_pct(momentum['sum_of_contributions'])}; sum of trading costs: "
        f"{_pct(momentum['sum_of_trading_costs'])}; sum of daily net returns: "
        f"{_pct(momentum['sum_of_strategy_daily_net_returns'])}; residual (cross terms): "
        f"{_pct(momentum['residual_contributions_minus_costs_minus_net'])}. {momentum['contribution_note']}.",
        "",
        f"Held set excluded the better of SPY/QQQ over the next {ETF_FORWARD_SESSIONS} sessions on "
        f"{exclusion['excluded_days']} of {exclusion['eligible_days']} eligible OOS days "
        f"({_pct(exclusion['excluded_fraction'])}).",
        "",
        "### 7. Data bias",
        "",
    ])
    lines.extend(f"- {item}" for item in diag["diagnostic_7_data_bias"]["text"])
    lines.extend(["", "## Caveats", ""])
    lines.extend(f"- {item}" for item in diag["caveats"])
    lines.append("")
    return "\n".join(lines)


def _breakeven_cell(block: Mapping[str, Any]) -> str:
    if not block:
        return "n/a"
    status = block["status"]
    if status == "SOLVED":
        return f"{block['bp_per_unit_traded']:.1f}"
    return status


def _event_row(label: str, summary: Mapping[str, Any]) -> list[str]:
    forward = summary["forward_spy_returns"]

    def pair(h: int) -> str:
        item = forward[f"+{h}d"]
        return f"{_pct(item['signal_mean'])} / {_pct(item['unconditional_mean'])}"

    on = summary["overnight_close_to_open"]
    idy = summary["intraday_open_to_close"]
    trades = summary["trades"]
    return [
        label,
        str(summary["signals"]),
        pair(1),
        pair(3),
        pair(5),
        f"{_pct(on['signal_mean'])} / {_pct(on['unconditional_mean'])}",
        f"{_pct(idy['signal_mean'])} / {_pct(idy['unconditional_mean'])}",
        _pct(trades["hit_rate"]),
        _pct(trades["average_gross"]),
        _pct(trades["average_net"]),
    ]


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m app.research.diagnostics",
        description="Read-only failure diagnostics of an existing research run. Descriptive only; no candidates.",
    )
    parser.add_argument("--data", required=True, type=Path, help="dataset directory with manifest.json")
    parser.add_argument("--out", required=True, type=Path, help="canonical JSON diagnostics path")
    parser.add_argument("--markdown", required=True, type=Path, help="markdown diagnostics path")
    parser.add_argument("--commission-bps", type=float, default=10.0, help="per-trade commission (bps)")
    parser.add_argument("--slippage-bps", type=float, default=5.0, help="per-trade slippage (bps)")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        diag = run_diagnostics(args.data, commission_bps=args.commission_bps, slippage_bps=args.slippage_bps)
    except ResearchError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(canonical_json(diag, indent=2) + "\n", encoding="utf-8")
    args.markdown.parent.mkdir(parents=True, exist_ok=True)
    args.markdown.write_text(render_markdown(diag), encoding="utf-8")
    print(f"wrote {args.out} and {args.markdown}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
