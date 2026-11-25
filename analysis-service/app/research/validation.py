"""Walk-forward validation, a locked holdout and pre-declared verdicts.

Layout (realization indices into the aligned calendar):

    [0, warmup)               warm-up, never scored (max grid lookback)
    [warmup, research_end)    walk-forward: train 756 / test 252 / step 252
    [research_end, n)         LOCKED HOLDOUT, final ceil(20%) of dates

Parameter selection uses research data only. The holdout is evaluated once with
the config chosen on the whole research window. Walk-forward trials run at 1x
cost. The 2x stress re-simulates the same selected configs at double cost and
does not re-select. N for the DSR is the total grid size across all families.
"""

from __future__ import annotations

import math
import statistics
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date
from typing import Any

from app.research.backtest import BacktestResult, cost_rate_from_bps, restrict_decisions, simulate
from app.research.data import Bar, Dataset, ResearchError, align
from app.research.metrics import (
    PERIODS_PER_YEAR,
    annualize_sharpe,
    compute_metrics,
    deflated_sharpe,
    probabilistic_sharpe,
    sharpe_per_period,
)
from app.research.strategies import (
    BENCHMARK_SYMBOL,
    MOMENTUM_UNIVERSE,
    Strategy,
    Weights,
    build_strategies,
    compute_decisions,
)

TRAIN_BARS = 756
TEST_BARS = 252
STEP_BARS = 252
HOLDOUT_FRACTION = 0.2
MIN_OOS_OBSERVATIONS = 3 * PERIODS_PER_YEAR
MIN_HOLDOUT_OBSERVATIONS = PERIODS_PER_YEAR
MIN_FOLDS = 2
DSR_MINIMUM = 0.95
PSR_MINIMUM = 0.95
STRESS_COST_MULTIPLIER = 2.0

VERDICT_VALIDATED = "VALIDATED_EDGE"
VERDICT_NOT_VALIDATED = "NOT_VALIDATED"
VERDICT_INSUFFICIENT = "INSUFFICIENT_DATA"
VERDICT_HELD = "HELD_UNVERIFIED_ADJUSTMENT"
REQUIRED_ADJUSTMENT_BASIS = "SPLIT_AND_DIVIDEND"
CRITERION_DSR = "DSR_AT_LEAST_0.95"
CRITERION_OOS_VS_BENCHMARK = "OOS_SHARPE_ABOVE_BENCHMARK"
CRITERION_ACTIVE_PSR = "ACTIVE_PSR_AT_LEAST_0.95"
CRITERION_HOLDOUT = "HOLDOUT_SHARPE_POSITIVE"
CRITERION_STRESS = "COST_2X_OOS_SHARPE_POSITIVE"


@dataclass(frozen=True)
class FoldWindow:
    index: int
    train_start: int
    train_end: int
    test_start: int
    test_end: int


@dataclass(frozen=True)
class Split:
    warmup: int
    research_end: int
    holdout_start: int
    holdout_end: int
    folds: tuple[FoldWindow, ...]


@dataclass(frozen=True)
class Verdict:
    name: str
    failed_criteria: tuple[str, ...]


@dataclass
class ConfigRun:
    strategy: Strategy
    family: str
    config_id: str
    decisions: dict[int, Weights]
    path: BacktestResult
    research_sharpe: float | None = None


@dataclass(frozen=True)
class PathWindow:
    dates: tuple[date, ...]
    returns: tuple[float, ...]
    turnover: tuple[float, ...]
    held_gross: tuple[float, ...]


@dataclass(frozen=True)
class ValidationResult:
    split: Split
    trial_count: int
    trial_variance: float | None
    trial_sharpes: Mapping[str, float | None]
    universe: tuple[str, ...]
    missing_universe: tuple[str, ...]
    aligned_dates: int
    aligned_first: date
    aligned_last: date
    dropped_dates: int
    families: Mapping[str, dict[str, Any]] = field(default_factory=dict)
    benchmark: Mapping[str, Any] = field(default_factory=dict)


def build_split(n_dates: int, warmup: int) -> Split:
    """Holdout is the last ceil(20%) of dates. Folds fit fully inside the research window."""
    holdout_len = math.ceil(n_dates * HOLDOUT_FRACTION)
    research_end = n_dates - holdout_len
    folds: list[FoldWindow] = []
    start = warmup
    while start + TRAIN_BARS + TEST_BARS <= research_end:
        folds.append(
            FoldWindow(
                index=len(folds),
                train_start=start,
                train_end=start + TRAIN_BARS,
                test_start=start + TRAIN_BARS,
                test_end=start + TRAIN_BARS + TEST_BARS,
            )
        )
        start += STEP_BARS
    return Split(
        warmup=warmup,
        research_end=research_end,
        holdout_start=research_end,
        holdout_end=n_dates,
        folds=tuple(folds),
    )


def path_window(result: BacktestResult, start: int, end: int) -> PathWindow:
    """Path values for realization indices in [start, end). Index 0 is never a return."""
    lo = max(start, 1)
    hi = min(end, len(result.dates))
    if hi <= lo:
        return PathWindow((), (), (), ())
    span = slice(lo - 1, hi - 1)
    return PathWindow(
        dates=result.dates[lo:hi],
        returns=result.returns[span],
        turnover=result.turnover[span],
        held_gross=result.held_gross[span],
    )


def pick_best(runs: Sequence[ConfigRun], score: Callable[[ConfigRun], float | None]) -> ConfigRun:
    """Highest score wins. None ranks last. Ties go to the earlier grid position."""
    best: ConfigRun | None = None
    best_score: float | None = None
    for run in runs:
        value = score(run)
        if value is None:
            continue
        if best_score is None or value > best_score:
            best, best_score = run, value
    return best if best is not None else runs[0]


def decide_verdict(
    *,
    oos_observations: int,
    holdout_observations: int,
    folds: int,
    dsr: float | None,
    oos_sharpe: float | None,
    benchmark_oos_sharpe: float | None,
    active_psr: float | None,
    holdout_sharpe: float | None,
    stress_oos_sharpe: float | None,
    adjustment_basis: str,
) -> Verdict:
    """Pre-declared verdict. An undefined metric counts as a failed criterion.

    Precedence: INSUFFICIENT_DATA, then HELD when the dataset's adjustment basis is not
    REQUIRED_ADJUSTMENT_BASIS (failed criteria are still returned for information), then
    VALIDATED_EDGE or NOT_VALIDATED.
    """
    if (
        oos_observations < MIN_OOS_OBSERVATIONS
        or holdout_observations < MIN_HOLDOUT_OBSERVATIONS
        or folds < MIN_FOLDS
    ):
        return Verdict(VERDICT_INSUFFICIENT, ())
    checks = (
        (CRITERION_DSR, dsr is not None and dsr >= DSR_MINIMUM),
        (
            CRITERION_OOS_VS_BENCHMARK,
            oos_sharpe is not None and benchmark_oos_sharpe is not None and oos_sharpe > benchmark_oos_sharpe,
        ),
        (CRITERION_ACTIVE_PSR, active_psr is not None and active_psr >= PSR_MINIMUM),
        (CRITERION_HOLDOUT, holdout_sharpe is not None and holdout_sharpe > 0.0),
        (CRITERION_STRESS, stress_oos_sharpe is not None and stress_oos_sharpe > 0.0),
    )
    failed = tuple(name for name, passed in checks if not passed)
    if adjustment_basis != REQUIRED_ADJUSTMENT_BASIS:
        return Verdict(VERDICT_HELD, failed)
    return Verdict(VERDICT_NOT_VALIDATED if failed else VERDICT_VALIDATED, failed)


def _summary(window: PathWindow, benchmark: PathWindow | None) -> dict[str, Any]:
    benchmark_returns = list(benchmark.returns) if benchmark is not None else None
    metrics = compute_metrics(
        window.dates,
        window.returns,
        benchmark_returns,
        hit_rate_exposed=[gross > 0.0 for gross in window.held_gross],
    )
    years = len(window.returns) / PERIODS_PER_YEAR
    metrics["turnover_annualized"] = sum(window.turnover) / years if years > 0 else None
    metrics["average_gross_exposure"] = (
        sum(window.held_gross) / len(window.held_gross) if window.held_gross else None
    )
    return metrics


def _sharpe_annualized(values: Sequence[float]) -> float | None:
    return annualize_sharpe(sharpe_per_period(values))


def _select_folds(family_runs: Sequence[ConfigRun], split: Split) -> list[tuple[FoldWindow, ConfigRun]]:
    """Per fold, the config with the best train-window Sharpe. Uses train data only."""
    choices: list[tuple[FoldWindow, ConfigRun]] = []
    for fold in split.folds:
        chosen = pick_best(
            family_runs,
            lambda run, fold=fold: sharpe_per_period(run.path.window_returns(fold.train_start, fold.train_end)),
        )
        choices.append((fold, chosen))
    return choices


def _fold_record(fold: FoldWindow, chosen: ConfigRun, benchmark_path: BacktestResult) -> dict[str, Any]:
    test_window = path_window(chosen.path, fold.test_start, fold.test_end)
    benchmark_window = path_window(benchmark_path, fold.test_start, fold.test_end)
    return {
        "fold": fold.index,
        "train_realization_range": [fold.train_start, fold.train_end],
        "test_realization_range": [fold.test_start, fold.test_end],
        "test_start": test_window.dates[0].isoformat() if test_window.dates else None,
        "test_end": test_window.dates[-1].isoformat() if test_window.dates else None,
        "selected_config": chosen.config_id,
        "train_sharpe_per_period": sharpe_per_period(
            chosen.path.window_returns(fold.train_start, fold.train_end)
        ),
        "test_total_return": _summary(test_window, None)["total_return"] if test_window.returns else None,
        "benchmark_test_total_return": (
            _summary(benchmark_window, None)["total_return"] if benchmark_window.returns else None
        ),
    }


def _oos_range(choices: Sequence[tuple[FoldWindow, ConfigRun]]) -> tuple[int, int]:
    if not choices:
        return 0, 0
    return choices[0][0].test_start, choices[-1][0].test_end


def _latest_decision(decisions: Mapping[int, Weights], at_or_before: int) -> Weights | None:
    eligible = [t for t in decisions if t <= at_or_before]
    return decisions[max(eligible)] if eligible else None


def stitched_book(
    choices: Sequence[tuple[FoldWindow, ConfigRun]],
    bars: Mapping[str, Sequence[Bar]],
    dates: Sequence[date],
    cost_rate: float,
) -> BacktestResult:
    """One continuous book across folds.

    Inside each test window the book follows that fold's config decisions. When the
    selected config changes, or at the first fold, its latest decision at or before
    test_start-1 is placed on test_start-1. The book is then invested from the first
    OOS day. That target was decided before the window, so this stays causal. A
    monthly config would otherwise sit in cash or on a stale config until its next
    month-end. Consecutive folds with the same config keep the drifted book.
    """
    decisions: dict[int, Weights] = {}
    previous_id: str | None = None
    for fold, chosen in choices:
        decisions.update(restrict_decisions(chosen.decisions, fold.test_start, fold.test_end))
        if chosen.config_id != previous_id:
            standing = _latest_decision(chosen.decisions, fold.test_start - 1)
            if standing is not None:
                decisions[fold.test_start - 1] = standing
        previous_id = chosen.config_id
    return simulate(bars, dates, decisions, cost_rate)


def _out_of_sample_block(
    oos: PathWindow,
    oos_stress: PathWindow,
    oos_benchmark: PathWindow,
    trial_variance: float | None,
    trial_count: int,
) -> dict[str, Any]:
    benchmark_sharpe = _sharpe_annualized(oos_benchmark.returns)
    active = [s - b for s, b in zip(oos.returns, oos_benchmark.returns)]
    return {
        "period": _period(oos.dates),
        "strategy": _summary(oos, oos_benchmark),
        "strategy_2x_cost": _summary(oos_stress, None),
        "benchmark": _summary(oos_benchmark, None),
        "sharpe_annualized": _sharpe_annualized(oos.returns),
        "benchmark_sharpe_annualized": benchmark_sharpe,
        "stress_sharpe_annualized": _sharpe_annualized(oos_stress.returns),
        "deflated_sharpe": deflated_sharpe(list(oos.returns), trial_variance, trial_count),
        "active_probabilistic_sharpe_vs_zero": probabilistic_sharpe(active) if active else None,
    }


def _holdout_block(
    choice: ConfigRun,
    split: Split,
    bars: Mapping[str, Sequence[Bar]],
    dates: Sequence[date],
    benchmark_path: BacktestResult,
    cost_rate: float,
) -> dict[str, Any]:
    path = path_window(choice.path, split.holdout_start, split.holdout_end)
    stress_book = simulate(bars, dates, choice.decisions, cost_rate * STRESS_COST_MULTIPLIER)
    stress = path_window(stress_book, split.holdout_start, split.holdout_end)
    benchmark = path_window(benchmark_path, split.holdout_start, split.holdout_end)
    return {
        "period": _period(path.dates),
        "strategy": _summary(path, benchmark),
        "strategy_2x_cost": _summary(stress, None),
        "benchmark": _summary(benchmark, None),
        "sharpe_annualized": _sharpe_annualized(path.returns),
    }


def _family_result(
    family_runs: Sequence[ConfigRun],
    *,
    split: Split,
    trial_variance: float | None,
    trial_count: int,
    bars: Mapping[str, Sequence[Bar]],
    dates: Sequence[date],
    benchmark_path: BacktestResult,
    cost_rate: float,
    adjustment_basis: str,
) -> dict[str, Any]:
    choices = _select_folds(family_runs, split)
    oos_start, oos_end = _oos_range(choices)
    stitched = stitched_book(choices, bars, dates, cost_rate)
    stitched_stress = stitched_book(choices, bars, dates, cost_rate * STRESS_COST_MULTIPLIER)
    oos_block = _out_of_sample_block(
        path_window(stitched, oos_start, oos_end),
        path_window(stitched_stress, oos_start, oos_end),
        path_window(benchmark_path, oos_start, oos_end),
        trial_variance,
        trial_count,
    )
    holdout_choice = pick_best(family_runs, lambda run: run.research_sharpe)
    holdout_block = _holdout_block(holdout_choice, split, bars, dates, benchmark_path, cost_rate)
    verdict = decide_verdict(
        oos_observations=oos_block["period"]["observations"],
        holdout_observations=holdout_block["period"]["observations"],
        folds=len(split.folds),
        dsr=oos_block["deflated_sharpe"],
        oos_sharpe=oos_block["sharpe_annualized"],
        benchmark_oos_sharpe=oos_block["benchmark_sharpe_annualized"],
        active_psr=oos_block["active_probabilistic_sharpe_vs_zero"],
        holdout_sharpe=holdout_block["sharpe_annualized"],
        stress_oos_sharpe=oos_block["stress_sharpe_annualized"],
        adjustment_basis=adjustment_basis,
    )
    return {
        "folds": [_fold_record(fold, chosen, benchmark_path) for fold, chosen in choices],
        "holdout_config": holdout_choice.config_id,
        "out_of_sample": oos_block,
        "holdout": holdout_block,
        "risk_profile": {
            "strategy": _risk(oos_block["strategy"]),
            "benchmark": _risk(oos_block["benchmark"]),
            "note": "informational only, not part of the verdict",
        },
        "verdict": {"name": verdict.name, "failed_criteria": list(verdict.failed_criteria)},
    }


def _risk(metrics: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "max_drawdown": metrics.get("max_drawdown"),
        "calmar": metrics.get("calmar"),
        "annualized_volatility": metrics.get("annualized_volatility"),
    }


def _period(dates: Sequence[date]) -> dict[str, Any]:
    return {
        "observations": len(dates),
        "first": dates[0].isoformat() if dates else None,
        "last": dates[-1].isoformat() if dates else None,
    }


def _group_by_family(runs: Sequence[ConfigRun]) -> dict[str, list[ConfigRun]]:
    families: dict[str, list[ConfigRun]] = {}
    for run in runs:
        families.setdefault(run.family, []).append(run)
    return families


def run_validation(dataset: Dataset, *, commission_bps: float, slippage_bps: float) -> ValidationResult:
    if BENCHMARK_SYMBOL not in dataset.bars:
        raise ResearchError(f"{BENCHMARK_SYMBOL} is required as the benchmark")
    strategies = build_strategies(dataset.symbols)
    universe = tuple(sorted({symbol for strategy in strategies for symbol in strategy.universe}))
    required = sorted(set(universe) | {BENCHMARK_SYMBOL})
    aligned = align(dataset, required)
    if len(aligned.dates) < 2:
        raise ResearchError("aligned history has fewer than two dates")
    cost_rate = cost_rate_from_bps(commission_bps, slippage_bps)
    benchmark_path = simulate(aligned.bars, aligned.dates, {0: {BENCHMARK_SYMBOL: 1.0}}, cost_rate)

    runs: list[ConfigRun] = []
    for strategy in strategies:
        decisions = compute_decisions(strategy, aligned.bars, aligned.dates)
        path = simulate(aligned.bars, aligned.dates, decisions, cost_rate)
        runs.append(
            ConfigRun(
                strategy=strategy,
                family=strategy.family,
                config_id=strategy.config_id(),
                decisions=decisions,
                path=path,
            )
        )

    warmup = max(strategy.warmup_bars() for strategy in strategies)
    split = build_split(len(aligned.dates), warmup)
    for run in runs:
        run.research_sharpe = sharpe_per_period(
            run.path.window_returns(split.warmup, split.research_end)
        )
    research_values = [run.research_sharpe for run in runs if run.research_sharpe is not None]
    trial_variance = statistics.variance(research_values) if len(research_values) >= 2 else None

    families: dict[str, dict[str, Any]] = {}
    for family, family_runs in _group_by_family(runs).items():
        families[family] = _family_result(
            family_runs,
            split=split,
            trial_variance=trial_variance,
            trial_count=len(runs),
            bars=aligned.bars,
            dates=aligned.dates,
            benchmark_path=benchmark_path,
            cost_rate=cost_rate,
            adjustment_basis=dataset.provenance["adjustment_basis"],
        )

    full = path_window(benchmark_path, 1, len(aligned.dates))
    benchmark = {
        "name": "SPY buy-and-hold (entry at first open, initial entry cost only)",
        "full_aligned_period": _summary(full, None),
        "research_period": _summary(path_window(benchmark_path, split.warmup, split.research_end), None),
        "holdout_period": _summary(path_window(benchmark_path, split.holdout_start, split.holdout_end), None),
    }
    return ValidationResult(
        split=split,
        trial_count=len(runs),
        trial_variance=trial_variance,
        trial_sharpes={run.config_id: run.research_sharpe for run in runs},
        universe=universe,
        missing_universe=tuple(sorted(set(MOMENTUM_UNIVERSE) - set(dataset.symbols))),
        aligned_dates=len(aligned.dates),
        aligned_first=aligned.dates[0],
        aligned_last=aligned.dates[-1],
        dropped_dates=aligned.dropped_dates,
        families=families,
        benchmark=benchmark,
    )


