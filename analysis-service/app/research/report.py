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
            "required_adjustment_basis": validation.REQUIRED_ADJUSTMENT_BASIS,
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


def _pct(value: Any) -> str:
    """Fraction in, percent out (0.1234 -> 12.34%)."""
    if value is None:
        return "n/a"
    return f"{value * 100:.2f}%"


MAIN_TABLE_HEADER = (
    "| Strategy | Verdict | OOS Sharpe | Benchmark OOS Sharpe | DSR | Active PSR | "
    "Holdout Sharpe | 2x-cost OOS Sharpe | OOS CAGR | SPY OOS CAGR | OOS MaxDD | SPY OOS MaxDD | "
    "OOS turnover/yr | est. cost drag/yr | Holdout CAGR | SPY holdout CAGR | Failed criteria |"
)
FOLD_TABLE_HEADER = "| Fold | Test window | Selected config | Test total return | SPY test total return |"


def _cost_drag(turnover_annualized: float | None, cost_rate: float) -> float | None:
    return None if turnover_annualized is None else turnover_annualized * cost_rate


def _main_table_row(family: str, data: dict[str, Any], cost_rate: float) -> str:
    oos = data["out_of_sample"]
    holdout = data["holdout"]
    verdict = data["verdict"]
    failed = ", ".join(verdict["failed_criteria"]) or "-"
    strategy = oos["strategy"]
    benchmark = oos["benchmark"]
    cells = [
        family,
        verdict["name"],
        _fmt(oos["sharpe_annualized"]),
        _fmt(oos["benchmark_sharpe_annualized"]),
        _fmt(oos["deflated_sharpe"]),
        _fmt(oos["active_probabilistic_sharpe_vs_zero"]),
        _fmt(holdout["sharpe_annualized"]),
        _fmt(oos["strategy_2x_cost"]["sharpe_annualized"]),
        _pct(strategy["cagr"]),
        _pct(benchmark["cagr"]),
        _pct(strategy["max_drawdown"]),
        _pct(benchmark["max_drawdown"]),
        _pct(strategy["turnover_annualized"]),
        _pct(_cost_drag(strategy["turnover_annualized"], cost_rate)),
        _pct(holdout["strategy"]["cagr"]),
        _pct(holdout["benchmark"]["cagr"]),
        failed,
    ]
    return "| " + " | ".join(cells) + " |"


def _fold_rows(folds: list[dict[str, Any]]) -> list[str]:
    if not folds:
        return ["- no folds (insufficient data)"]
    rows = [FOLD_TABLE_HEADER, "|---|---|---|---|---|"]
    for fold in folds:
        window = (
            f"{fold['test_start']} to {fold['test_end']}"
            if fold["test_start"] is not None
            else "n/a"
        )
        rows.append(
            "| "
            + " | ".join(
                [
                    str(fold["fold"]),
                    window,
                    fold["selected_config"],
                    _pct(fold["test_total_return"]),
                    _pct(fold["benchmark_test_total_return"]),
                ]
            )
            + " |"
        )
    return rows


def render_markdown(report: dict[str, Any]) -> str:
    basis = report["dataset"]["provenance"]["adjustment_basis"]
    cost_rate = report["config"]["costs"]["cost_rate_per_unit_traded"]
    required = report["config"]["verdict_thresholds"]["required_adjustment_basis"]
    held = any(data["verdict"]["name"] == validation.VERDICT_HELD for data in report["strategies"].values())
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
        f"- adjustment_basis: `{basis}` (verdicts require `{required}`)",
    ]
    if held:
        lines.append(
            "- Verdicts withheld (HELD_UNVERIFIED_ADJUSTMENT): dividend/split adjustment is unconfirmed. "
            "Failed criteria below are informational only."
        )
    lines.extend(
        [
            "",
            MAIN_TABLE_HEADER,
            "|" + "---|" * 17,
        ]
    )
    for family, data in report["strategies"].items():
        lines.append(_main_table_row(family, data, cost_rate))
    lines.extend(["", "## Walk-forward folds"])
    for family, data in report["strategies"].items():
        lines.extend(["", f"### {family}", ""])
        lines.extend(_fold_rows(data["folds"]))
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
