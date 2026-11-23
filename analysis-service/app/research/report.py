"""Canonical JSON report and a human-readable markdown summary.

The hashed body contains no wall-clock timestamps, so identical inputs produce
byte-identical output.
"""

from __future__ import annotations

from typing import Any

from app.research import validation
from app.research.backtest import cost_rate_from_bps
from app.research.data import Dataset, canonical_json, sha256_hex
from app.research.metrics import PERIODS_PER_YEAR, RISK_FREE_RATE
from app.research.strategies import (
    BENCHMARK_SYMBOL,
    MEAN_REVERSION_GRID,
    MOMENTUM_GRID,
    MOMENTUM_UNIVERSE,
    TREND_DONCHIAN_GRID,
    TREND_SMA_GRID,
)

REPORT_LABEL = (
    "Backtest results are hypothetical simulations on historical data; "
    "they are not forecasts of future returns."
)
GENERATED_BY = "app.research 1.0.0"
SURVIVORSHIP_NOTE = "LIMITED_NOT_CONTROLLED — fixed ETF universe chosen today"
COMMISSION_NOTE = (
    "Commission is an assumption: Toss US-equity commission is configurable via "
    "--commission-bps (default 10 bps). Slippage is --slippage-bps (default 5 bps)."
)


def build_config(commission_bps: float, slippage_bps: float) -> dict[str, Any]:
    """Everything that defines the trial universe and the decision rules. Hashed into config_hash."""
    return {
        "costs": {
            "commission_bps": commission_bps,
            "slippage_bps": slippage_bps,
            "cost_rate_per_unit_traded": cost_rate_from_bps(commission_bps, slippage_bps),
            "stress_multiplier": validation.STRESS_COST_MULTIPLIER,
        },
        "grids": {
            "momentum": {key: list(values) for key, values in MOMENTUM_GRID.items()},
            "momentum_universe": list(MOMENTUM_UNIVERSE),
            "trend_sma": [dict(params) for params in TREND_SMA_GRID],
            "trend_donchian": [dict(params) for params in TREND_DONCHIAN_GRID],
            "mean_reversion": {key: list(values) for key, values in MEAN_REVERSION_GRID.items()},
        },
        "walk_forward": {
            "train_bars": validation.TRAIN_BARS,
            "test_bars": validation.TEST_BARS,
            "step_bars": validation.STEP_BARS,
            "holdout_fraction": validation.HOLDOUT_FRACTION,
        },
        "verdict_thresholds": {
            "min_oos_observations": validation.MIN_OOS_OBSERVATIONS,
            "min_holdout_observations": validation.MIN_HOLDOUT_OBSERVATIONS,
            "min_folds": validation.MIN_FOLDS,
            "dsr_minimum": validation.DSR_MINIMUM,
            "psr_minimum": validation.PSR_MINIMUM,
        },
        "periods_per_year": PERIODS_PER_YEAR,
        "risk_free_rate": RISK_FREE_RATE,
        "benchmark": BENCHMARK_SYMBOL,
    }


def _bias_controls(dataset: Dataset) -> dict[str, Any]:
    basis = dataset.provenance["adjustment_basis"]
    warnings: list[str] = []
    if basis != "SPLIT_AND_DIVIDEND":
        warnings.append(
            f"adjustment_basis is {basis}: benchmark and strategy dividend treatment may be biased"
        )
    return {
        "look_ahead": (
            "decision at close t, execution at open t+1; strategies see only bars <= t "
            "via bounded prefix views; monthly schedule uses the trading calendar (dates only)"
        ),
        "survivorship": SURVIVORSHIP_NOTE,
        "adjustment_basis": basis,
        "warnings": warnings,
        "risk_free_rate": RISK_FREE_RATE,
        "cost_assumption": COMMISSION_NOTE,
        "return_convention": (
            "open-fill; overnight multiplicative, intraday and cost additive at open NAV; "
            "first-order identical to the additive spec formula"
        ),
        "walk_forward_switching": (
            "stitched OOS path is one continuous book; switch trades at fold boundaries are charged"
        ),
        "stress_test": "same selected configs and decision paths re-run at 2x cost; selection not repeated",
    }


def build_report(
    dataset: Dataset,
    result: validation.ValidationResult,
    *,
    commission_bps: float,
    slippage_bps: float,
) -> dict[str, Any]:
    config = build_config(commission_bps, slippage_bps)
    split = result.split
    return {
        "label": REPORT_LABEL,
        "generated_by": GENERATED_BY,
        "config": config,
        "config_hash": sha256_hex(canonical_json(config).encode("utf-8")),
        "dataset": {
            "dataset_hash": dataset.dataset_hash,
            "provenance": dict(dataset.provenance),
            "warnings": list(dataset.warnings),
            "symbols_in_dataset": list(dataset.symbols),
            "universe_used": list(result.universe),
            "missing_momentum_universe": list(result.missing_universe),
            "aligned_dates": result.aligned_dates,
            "aligned_first": result.aligned_first.isoformat(),
            "aligned_last": result.aligned_last.isoformat(),
            "dropped_dates": result.dropped_dates,
        },
        "bias_controls": _bias_controls(dataset),
        "split": {
            "warmup_bars": split.warmup,
            "research_realization_range": [split.warmup, split.research_end],
            "holdout_realization_range": [split.holdout_start, split.holdout_end],
            "folds": [
                {
                    "fold": fold.index,
                    "train_realization_range": [fold.train_start, fold.train_end],
                    "test_realization_range": [fold.test_start, fold.test_end],
                }
                for fold in split.folds
            ],
        },
        "trials": {
            "count": result.trial_count,
            "variance_of_research_sharpe_per_period": result.trial_variance,
            "research_sharpe_per_period": dict(result.trial_sharpes),
        },
        "benchmark": dict(result.benchmark),
        "strategies": dict(result.families),
    }


def render_canonical(report: dict[str, Any]) -> str:
    return canonical_json(report, indent=2) + "\n"


def _fmt(value: Any) -> str:
    if value is None:
        return "n/a"
    if isinstance(value, float):
        return f"{value:.4f}"
    return str(value)


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Quant research summary",
        "",
        f"_{report['label']}_",
        "",
        f"- dataset_hash: `{report['dataset']['dataset_hash']}`",
        f"- config_hash: `{report['config_hash']}`",
        f"- trials (N): {report['trials']['count']}",
        f"- aligned dates: {report['dataset']['aligned_dates']} "
        f"({report['dataset']['aligned_first']} to {report['dataset']['aligned_last']})",
        "",
        "| Strategy | Verdict | OOS Sharpe | Benchmark OOS Sharpe | DSR | Active PSR | "
        "Holdout Sharpe | 2x-cost OOS Sharpe | Failed criteria |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for family, data in report["strategies"].items():
        oos = data["out_of_sample"]
        verdict = data["verdict"]
        failed = ", ".join(verdict["failed_criteria"]) or "-"
        lines.append(
            "| "
            + " | ".join(
                [
                    family,
                    verdict["name"],
                    _fmt(oos["sharpe_annualized"]),
                    _fmt(oos["benchmark_sharpe_annualized"]),
                    _fmt(oos["deflated_sharpe"]),
                    _fmt(oos["active_probabilistic_sharpe_vs_zero"]),
                    _fmt(data["holdout"]["sharpe_annualized"]),
                    _fmt(oos["strategy_2x_cost"]["sharpe_annualized"]),
                    failed,
                ]
            )
            + " |"
        )
    warnings = report["dataset"]["warnings"] + report["bias_controls"]["warnings"]
    missing = report["dataset"]["missing_momentum_universe"]
    if missing:
        warnings = warnings + [f"momentum universe reduced; missing symbols: {', '.join(missing)}"]
    lines.extend(["", "## Warnings", ""])
    if warnings:
        lines.extend(f"- {warning}" for warning in warnings)
    else:
        lines.append("- none")
    lines.append("")
    return "\n".join(lines)
