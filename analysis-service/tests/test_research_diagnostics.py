from __future__ import annotations

import ast
import json
import os
import random
import statistics
import subprocess
import sys
from datetime import date, timedelta
from pathlib import Path

import pytest
from research_fixtures import universe_series, write_dataset

from app.research import diagnostics as diag_mod
from app.research.backtest import simulate
from app.research.data import Bar, load_dataset
from app.research.diagnostics import (
    FORWARD_HORIZONS,
    _build_pipeline,
    _family_view,
    _positions,
    _stitched_decisions,
    cscv_pbo,
    matched_mix_returns,
    render_markdown,
    rebased_returns,
    run_diagnostics,
    sma_regime,
    solve_breakeven_cost,
)
from app.research.report import build_report
from app.research.validation import path_window, run_validation
from app.research.validation import stitched_book

ANALYSIS_ROOT = Path(__file__).resolve().parent.parent
DIAG_SOURCE = ANALYSIS_ROOT / "app" / "research" / "diagnostics.py"
FORBIDDEN_MODULES = ("fastapi", "httpx", "urllib", "socket", "requests", "app.main", "app.monitoring")
FULL_COUNT = 2250  # three walk-forward folds, 450-bar holdout (see test_research_report.py)
PRE_DECLARED_NAMES = (
    "Exposure-matched benchmark",
    "Gross vs net",
    "Selection vs hypothesis",
    "Regime / capture",
    "Mean-reversion event study",
    "Momentum attribution",
    "Data bias",
)


def _bar(day: date, close: float) -> Bar:
    return Bar(day, close, max(close, close), min(close, close), close, 1_000_000.0)


@pytest.fixture(scope="module")
def dataset_dir(tmp_path_factory: pytest.TempPathFactory) -> Path:
    return write_dataset(tmp_path_factory.mktemp("diagnostics") / "data", universe_series(FULL_COUNT))


@pytest.fixture(scope="module")
def pipeline(dataset_dir: Path):
    dataset = load_dataset(dataset_dir)
    return _build_pipeline(dataset, 10.0, 5.0)


@pytest.fixture(scope="module")
def diag(dataset_dir: Path) -> dict:
    return run_diagnostics(dataset_dir, commission_bps=10.0, slippage_bps=5.0)


# ---------------------------------------------------------------------------
# Diagnostic 1: matched mix and breakeven arithmetic
# ---------------------------------------------------------------------------


def test_matched_mix_is_hand_checkable() -> None:
    bars = [_bar(date(2024, 1, 2) + timedelta(days=i), close) for i, close in enumerate([100.0, 110.0, 99.0])]
    assert matched_mix_returns(bars, 1, 3, 0.5) == pytest.approx([0.05, -0.05])
    assert matched_mix_returns(bars, 1, 3, 1.0) == pytest.approx([0.10, -0.10])
    assert matched_mix_returns(bars, 2, 3, 0.5) == pytest.approx([-0.05])
    assert matched_mix_returns(bars, 1, 3, 0.0) == pytest.approx([0.0, 0.0])


def test_breakeven_solver_finds_crossing_and_reports_edges() -> None:
    def cagr_at(cost: float) -> float:
        return 0.10 - 10.0 * cost  # decreasing in cost

    status, cost = solve_breakeven_cost(cagr_at, 0.05, 0.05)
    assert status == "SOLVED"
    assert cost == pytest.approx(0.005, abs=1e-9)
    assert solve_breakeven_cost(cagr_at, 0.20, 0.05) == ("ZERO_COST_BELOW_TARGET", None)
    assert solve_breakeven_cost(cagr_at, -1.0, 0.05) == ("ABOVE_TARGET_AT_UPPER_BOUND", None)
    assert solve_breakeven_cost(cagr_at, None, 0.05) == ("TARGET_UNDEFINED", None)


def test_closed_form_recost_matches_engine_resimulation(pipeline) -> None:
    """Advisor point 1: fixed decisions, cost enters only as (1 - c x T); closed form must equal re-simulation."""
    view = _family_view(pipeline, "trend")
    lo, hi = view.oos_start, view.oos_end
    base = pipeline.cost_rate
    base_window = path_window(view.oos_path, lo, hi)
    engine = path_window(
        stitched_book(view.choices, pipeline.bars, pipeline.dates, 2.0 * base), lo, hi
    ).returns
    closed = rebased_returns(list(base_window.returns), list(base_window.turnover), base, 2.0 * base)
    assert list(engine) == pytest.approx(closed, rel=0, abs=1e-12)


def test_stitched_decisions_reproduce_stitched_book(pipeline) -> None:
    view = _family_view(pipeline, "mean_reversion")
    rebuilt = simulate(
        pipeline.bars, pipeline.dates, _stitched_decisions(view.choices), pipeline.cost_rate
    )
    reference = stitched_book(view.choices, pipeline.bars, pipeline.dates, pipeline.cost_rate)
    assert list(rebuilt.returns) == list(reference.returns)
    assert list(rebuilt.turnover) == list(reference.turnover)


def test_gross_equals_net_plus_cost_identity(pipeline) -> None:
    """Gross = net + cost x turnover is the brief's additive identity; compare to the zero-cost path."""
    view = _family_view(pipeline, "trend")
    lo, hi = view.oos_start, view.oos_end
    cost = pipeline.cost_rate
    net = path_window(view.oos_path, lo, hi)
    zero_cost = path_window(stitched_book(view.choices, pipeline.bars, pipeline.dates, 0.0), lo, hi)
    gross = [r + cost * t for r, t in zip(net.returns, net.turnover)]
    assert all(
        abs(g - z) < 2e-4 for g, z in zip(gross, zero_cost.returns)
    ), "additive gross deviates from the zero-cost path by more than second-order terms"


def test_momentum_attribution_reconciles_with_engine_returns(pipeline) -> None:
    """Overnight + intraday contributions, less costs, must reproduce the arithmetic net sum up to cross terms."""
    view = _family_view(pipeline, "momentum")
    attribution = diag_mod._momentum_block(pipeline, view)
    assert abs(attribution["residual_contributions_minus_costs_minus_net"]) < 0.02
    assert attribution["best_of_spy_qqq_exclusion"]["eligible_days"] > 0


def test_positions_are_known_at_prior_close() -> None:
    decisions = {0: {"SPY": 1.0}, 3: {}}
    positions = _positions(decisions, 6)
    assert positions[0] == {}
    assert positions[1] == {"SPY": 1.0}
    assert positions[3] == {"SPY": 1.0}
    assert positions[4] == {}
    assert positions[5] == {}


# ---------------------------------------------------------------------------
# Diagnostic 3: CSCV
# ---------------------------------------------------------------------------


def test_cscv_dominant_config_gives_zero_pbo() -> None:
    rng = random.Random(1)
    series = {"dominant": [0.002 + rng.gauss(0.0, 0.001) for _ in range(320)]}
    for index in range(9):
        series[f"noise_{index}"] = [rng.gauss(0.0, 0.01) for _ in range(320)]
    result = cscv_pbo(series)
    assert result["configs"] == 10
    assert result["combinations_total"] == 12870
    assert result["pbo"] == pytest.approx(0.0, abs=0.01)


def test_cscv_pure_noise_pbo_averages_near_one_half() -> None:
    # A single noise draw is highly variable (PBO ranged 0.27 to 0.94 across seeds), so average over seeds.
    values = []
    for seed in range(6):
        rng = random.Random(seed)
        series = {f"noise_{index}": [rng.gauss(0.0, 0.01) for _ in range(320)] for index in range(10)}
        values.append(cscv_pbo(series)["pbo"])
    assert 0.35 < statistics.fmean(values) < 0.65


def test_cscv_rejects_mismatched_lengths() -> None:
    with pytest.raises(ValueError):
        cscv_pbo({"a": [0.1] * 32, "b": [0.1] * 31})


# ---------------------------------------------------------------------------
# Diagnostic 4: regime uses t-1 information only
# ---------------------------------------------------------------------------


def test_sma_regime_uses_only_past_closes() -> None:
    rng = random.Random(3)
    day = date(2010, 1, 4)
    closes = [100.0]
    for _ in range(299):
        closes.append(closes[-1] * (1.0 + rng.gauss(0.0, 0.01)))
    bars = [_bar(day + timedelta(days=i), close) for i, close in enumerate(closes)]
    labels = sma_regime(bars)
    cut = 250
    mutated_closes = closes[:cut] + [1.0 + 5.0 * index for index in range(len(closes) - cut)]
    mutated = [_bar(day + timedelta(days=i), close) for i, close in enumerate(mutated_closes)]
    assert sma_regime(mutated)[: cut + 1] == labels[: cut + 1]
    assert labels[200] is not None and labels[199] is None


# ---------------------------------------------------------------------------
# Assembly, reconciliation, determinism, isolation
# ---------------------------------------------------------------------------


def test_hashes_match_build_report(dataset_dir: Path, diag: dict) -> None:
    dataset = load_dataset(dataset_dir)
    result = run_validation(dataset, commission_bps=10.0, slippage_bps=5.0)
    report = build_report(dataset, result, commission_bps=10.0, slippage_bps=5.0)
    assert diag["dataset_hash"] == report["dataset"]["dataset_hash"]
    assert diag["config_hash"] == report["config_hash"]


def test_reconciliation_with_report_is_recorded(diag: dict) -> None:
    assert diag["reconciliation"]["matched"] is True
    assert set(diag["reconciliation"]["checked"]) == {"momentum", "trend", "mean_reversion"}


def test_pre_declared_table_precedes_results(diag: dict) -> None:
    markdown = render_markdown(diag)
    assert markdown.index("## Pre-declared diagnostics") < markdown.index("## Results")
    for name in PRE_DECLARED_NAMES:
        assert name in markdown[: markdown.index("## Results")]
    assert "LOOK-AHEAD, NOT A CANDIDATE" in markdown
    assert "## Caveats" in markdown
    assert len(FORWARD_HORIZONS) == 3


def _run_cli(dataset: Path, out: Path, markdown: Path, hash_seed: str) -> subprocess.CompletedProcess[str]:
    env = dict(os.environ)
    env["PYTHONHASHSEED"] = hash_seed
    return subprocess.run(
        [
            sys.executable,
            "-m",
            "app.research.diagnostics",
            "--data",
            str(dataset),
            "--out",
            str(out),
            "--markdown",
            str(markdown),
        ],
        cwd=ANALYSIS_ROOT,
        capture_output=True,
        text=True,
        check=False,
        env=env,
    )


def test_cli_output_is_byte_identical_across_processes(dataset_dir: Path, tmp_path: Path) -> None:
    first_json, first_md = tmp_path / "a" / "d.json", tmp_path / "a" / "d.md"
    second_json, second_md = tmp_path / "b" / "d.json", tmp_path / "b" / "d.md"
    first = _run_cli(dataset_dir, first_json, first_md, "1")
    second = _run_cli(dataset_dir, second_json, second_md, "2")
    assert first.returncode == 0, first.stderr
    assert second.returncode == 0, second.stderr
    assert first_json.read_bytes() == second_json.read_bytes()
    assert first_md.read_bytes() == second_md.read_bytes()


def test_diagnostics_source_has_no_forbidden_imports() -> None:
    tree = ast.parse(DIAG_SOURCE.read_text(encoding="utf-8"), filename=str(DIAG_SOURCE))
    modules: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            modules.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module and node.level == 0:
            modules.add(node.module)
    for module in modules:
        for forbidden in FORBIDDEN_MODULES:
            assert not (module == forbidden or module.startswith(forbidden + ".")), module


def test_importing_diagnostics_loads_no_http_or_app_modules() -> None:
    script = (
        "import sys\n"
        f"sys.path.insert(0, {str(ANALYSIS_ROOT)!r})\n"
        "import app.research.diagnostics\n"
        "bad = [m for m in ('fastapi', 'httpx', 'urllib.request', 'socket', 'requests', 'app.main', 'app.monitoring') "
        "if m in sys.modules]\n"
        "print(','.join(bad))\n"
    )
    completed = subprocess.run([sys.executable, "-I", "-c", script], capture_output=True, text=True, check=False)
    assert completed.returncode == 0, completed.stderr
    assert completed.stdout.strip() == ""

