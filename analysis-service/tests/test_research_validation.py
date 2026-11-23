from __future__ import annotations

from pathlib import Path

from research_fixtures import Row, universe_series, write_dataset

import pytest

from app.research.backtest import simulate
from app.research.data import Bar, load_dataset
from app.research.strategies import MOMENTUM_UNIVERSE, MomentumStrategy, compute_decisions
from app.research.validation import (
    CRITERION_ACTIVE_PSR,
    CRITERION_DSR,
    CRITERION_HOLDOUT,
    CRITERION_OOS_VS_BENCHMARK,
    CRITERION_STRESS,
    DSR_MINIMUM,
    PSR_MINIMUM,
    VERDICT_INSUFFICIENT,
    VERDICT_NOT_VALIDATED,
    VERDICT_VALIDATED,
    ConfigRun,
    build_split,
    decide_verdict,
    pick_best,
    run_validation,
    stitched_book,
)

COUNT = 2000
FULL_COUNT = 2250  # research_end 1800 -> three folds, OOS 756 bars, holdout 450 bars


def _run(tmp_path: Path, series: dict[str, list[Row]], name: str):
    root = write_dataset(tmp_path / name, series)
    return run_validation(load_dataset(root), commission_bps=10.0, slippage_bps=5.0)


def test_split_has_contiguous_non_overlapping_test_windows_and_holdout_last() -> None:
    split = build_split(2000, 274)
    assert split.research_end == 1600
    assert split.holdout_start == 1600 and split.holdout_end == 2000
    assert len(split.folds) == 2
    previous_end = 0
    for fold in split.folds:
        assert fold.train_start >= split.warmup
        assert fold.train_end == fold.test_start
        assert fold.test_end - fold.test_start == 252
        assert fold.test_end - fold.train_start == 756 + 252
        assert fold.test_start >= previous_end
        assert fold.test_end <= split.research_end
        previous_end = fold.test_end
    starts = [fold.test_start for fold in split.folds]
    assert starts == sorted(starts) and len(set(starts)) == len(starts)


def test_holdout_data_never_influences_selection_or_research_results(tmp_path: Path) -> None:
    series = universe_series(COUNT)
    baseline = _run(tmp_path, series, "base")

    corrupted = {}
    for symbol, rows in series.items():
        corrupted[symbol] = [
            row
            if index < baseline.split.holdout_start
            else (row[0], row[1] * 5.0, row[2] * 5.0, row[3] * 5.0, row[4] * 5.0, row[5])
            for index, row in enumerate(rows)
        ]
    adversarial = _run(tmp_path, corrupted, "adversarial")

    assert adversarial.trial_sharpes == baseline.trial_sharpes
    assert adversarial.trial_variance == baseline.trial_variance
    for family in baseline.families:
        before = baseline.families[family]
        after = adversarial.families[family]
        assert [fold["selected_config"] for fold in after["folds"]] == [
            fold["selected_config"] for fold in before["folds"]
        ]
        assert after["holdout_config"] == before["holdout_config"]
        assert after["out_of_sample"]["strategy"]["total_return"] == before["out_of_sample"]["strategy"]["total_return"]
    # Sanity: the corruption really changed the holdout data the benchmark sees.
    assert adversarial.benchmark["holdout_period"]["total_return"] != (
        baseline.benchmark["holdout_period"]["total_return"]
    )


def test_run_validation_marks_short_history_as_insufficient(tmp_path: Path) -> None:
    result = _run(tmp_path, universe_series(300), "short")
    assert result.trial_count == 54
    for family in result.families.values():
        assert family["verdict"]["name"] == VERDICT_INSUFFICIENT
        assert family["folds"] == []


def test_trial_count_and_variance_come_from_all_families(tmp_path: Path) -> None:
    result = _run(tmp_path, universe_series(COUNT), "full")
    assert result.trial_count == 54
    assert len(result.trial_sharpes) == 54
    values = [value for value in result.trial_sharpes.values() if value is not None]
    assert result.trial_variance is not None and result.trial_variance > 0.0
    assert len(values) >= 2


def test_folds_select_inside_training_windows_only(tmp_path: Path) -> None:
    result = _run(tmp_path, universe_series(COUNT), "folds")
    for family in result.families.values():
        assert len(family["folds"]) == 2
        for fold in family["folds"]:
            assert fold["train_realization_range"][1] <= fold["test_realization_range"][0]
            assert fold["test_realization_range"][1] <= result.split.research_end
        assert family["out_of_sample"]["period"]["observations"] == 504
        assert family["holdout"]["period"]["observations"] == 400


def test_pick_best_prefers_highest_score_then_grid_order_and_ranks_none_last() -> None:
    def run(config_id: str) -> ConfigRun:
        return ConfigRun(strategy=None, family="f", config_id=config_id, decisions={}, path=None)  # type: ignore[arg-type]

    runs = [run("a"), run("b"), run("c"), run("d")]
    scores = {"a": None, "b": 0.5, "c": 0.5, "d": 0.2}
    assert pick_best(runs, lambda r: scores[r.config_id]).config_id == "b"
    assert pick_best(runs, lambda r: None).config_id == "a"


def test_verdict_insufficient_when_oos_is_short() -> None:
    verdict = decide_verdict(
        oos_observations=500,
        holdout_observations=400,
        folds=2,
        dsr=0.99,
        oos_sharpe=2.0,
        benchmark_oos_sharpe=0.1,
        active_psr=0.99,
        holdout_sharpe=1.0,
        stress_oos_sharpe=1.0,
    )
    assert verdict.name == VERDICT_INSUFFICIENT
    assert verdict.failed_criteria == ()


def test_verdict_validated_only_when_every_criterion_passes() -> None:
    verdict = decide_verdict(
        oos_observations=1000,
        holdout_observations=300,
        folds=3,
        dsr=0.97,
        oos_sharpe=1.2,
        benchmark_oos_sharpe=0.8,
        active_psr=0.96,
        holdout_sharpe=0.5,
        stress_oos_sharpe=0.3,
    )
    assert verdict.name == VERDICT_VALIDATED
    assert verdict.failed_criteria == ()


def test_verdict_lists_each_failed_criterion_by_name() -> None:
    verdict = decide_verdict(
        oos_observations=1000,
        holdout_observations=300,
        folds=3,
        dsr=0.5,
        oos_sharpe=0.5,
        benchmark_oos_sharpe=0.8,
        active_psr=0.96,
        holdout_sharpe=-0.1,
        stress_oos_sharpe=0.3,
    )
    assert verdict.name == VERDICT_NOT_VALIDATED
    assert verdict.failed_criteria == (CRITERION_DSR, CRITERION_OOS_VS_BENCHMARK, CRITERION_HOLDOUT)


def test_undefined_metric_counts_as_failed_criterion() -> None:
    verdict = decide_verdict(
        oos_observations=1000,
        holdout_observations=300,
        folds=3,
        dsr=None,
        oos_sharpe=1.0,
        benchmark_oos_sharpe=0.5,
        active_psr=None,
        holdout_sharpe=0.2,
        stress_oos_sharpe=0.1,
    )
    assert verdict.name == VERDICT_NOT_VALIDATED
    assert CRITERION_DSR in verdict.failed_criteria
    assert CRITERION_ACTIVE_PSR in verdict.failed_criteria
    assert CRITERION_STRESS not in verdict.failed_criteria



def _bars_for(rows_by_symbol: dict) -> dict[str, list[Bar]]:
    return {symbol: [Bar(*row) for row in rows] for symbol, rows in rows_by_symbol.items()}


def _monthly_run(bars: dict[str, list[Bar]], dates: list, cost: float) -> ConfigRun:
    strategy = MomentumStrategy(126, 0, 1, False, universe=MOMENTUM_UNIVERSE)
    decisions = compute_decisions(strategy, bars, dates)
    return ConfigRun(
        strategy=strategy,
        family=strategy.family,
        config_id=strategy.config_id(),
        decisions=decisions,
        path=simulate(bars, dates, decisions, cost),
    )


def test_stitched_monthly_config_is_invested_from_first_oos_day_and_matches_own_path() -> None:
    bars = _bars_for(universe_series(FULL_COUNT))
    dates = [bar.date for bar in bars["SPY"]]
    cost = 0.0015
    split = build_split(len(dates), warmup=274)
    run = _monthly_run(bars, dates, cost)
    stitched = stitched_book([(fold, run) for fold in split.folds], bars, dates, cost)

    oos_start = split.folds[0].test_start
    oos_end = split.folds[-1].test_end
    # Without the standing-target rule the book would be in cash until the first month-end.
    assert sum(stitched.weights[oos_start].values()) > 0.0
    # After the first in-window rebalance both books hold the same target, so returns coincide.
    first_rebalance = min(t + 1 for t in run.decisions if oos_start <= t + 1 < oos_end)
    assert first_rebalance > oos_start
    for day in range(first_rebalance + 1, oos_end):
        assert stitched.returns[day - 1] == pytest.approx(run.path.returns[day - 1], abs=1e-12)


def test_stitched_book_charges_a_switch_when_the_selected_config_changes() -> None:
    bars = _bars_for(universe_series(FULL_COUNT))
    dates = [bar.date for bar in bars["SPY"]]
    split = build_split(len(dates), warmup=274)
    first = _monthly_run(bars, dates, 0.0)
    switch_day = split.folds[1].test_start
    before_switch = stitched_book([(split.folds[0], first)], bars, dates, 0.0)
    held = before_switch.weights[switch_day - 1]
    new_symbol = next(symbol for symbol in MOMENTUM_UNIVERSE if held.get(symbol, 0.0) < 1e-12)
    other = ConfigRun(
        strategy=first.strategy,
        family=first.family,
        config_id="other",
        decisions={t: {new_symbol: 1.0} for t in range(len(dates) - 1)},
        path=first.path,
    )
    stitched = stitched_book(
        [(split.folds[0], first), (split.folds[1], other), (split.folds[2], first)], bars, dates, 0.0
    )
    switch_trades = [trade for trade in stitched.trades if trade.date == dates[switch_day]]
    assert any(trade.symbol == new_symbol and trade.to_weight == 1.0 for trade in switch_trades)
    assert stitched.weights[switch_day][new_symbol] == pytest.approx(1.0, abs=1e-9)


@pytest.fixture(scope="module")
def full_result(tmp_path_factory: pytest.TempPathFactory):
    root = write_dataset(tmp_path_factory.mktemp("full") / "ds", universe_series(FULL_COUNT))
    return run_validation(load_dataset(root), commission_bps=10.0, slippage_bps=5.0)


def test_full_sample_reaches_the_verdict_path(full_result) -> None:
    assert full_result.split.research_end == 1800
    assert len(full_result.split.folds) == 3
    for family in full_result.families.values():
        assert family["out_of_sample"]["period"]["observations"] == 756
        assert family["holdout"]["period"]["observations"] == 450
        assert family["verdict"]["name"] != VERDICT_INSUFFICIENT


def test_each_failed_criterion_matches_the_reported_numbers(full_result) -> None:
    for family in full_result.families.values():
        oos = family["out_of_sample"]
        holdout = family["holdout"]
        failed = set(family["verdict"]["failed_criteria"])
        assert oos["sharpe_annualized"] == oos["strategy"]["sharpe_annualized"]
        assert oos["benchmark_sharpe_annualized"] == oos["benchmark"]["sharpe_annualized"]
        assert oos["stress_sharpe_annualized"] == oos["strategy_2x_cost"]["sharpe_annualized"]
        assert holdout["sharpe_annualized"] == holdout["strategy"]["sharpe_annualized"]

        holdout_sharpe = holdout["sharpe_annualized"]
        assert (CRITERION_HOLDOUT in failed) == (holdout_sharpe is None or holdout_sharpe <= 0.0)

        oos_sharpe = oos["sharpe_annualized"]
        bench_sharpe = oos["benchmark_sharpe_annualized"]
        expected_fail = oos_sharpe is None or bench_sharpe is None or oos_sharpe <= bench_sharpe
        assert (CRITERION_OOS_VS_BENCHMARK in failed) == expected_fail

        stress = oos["stress_sharpe_annualized"]
        assert (CRITERION_STRESS in failed) == (stress is None or stress <= 0.0)

        dsr = oos["deflated_sharpe"]
        assert (CRITERION_DSR in failed) == (dsr is None or dsr < DSR_MINIMUM)

        psr = oos["active_probabilistic_sharpe_vs_zero"]
        assert (CRITERION_ACTIVE_PSR in failed) == (psr is None or psr < PSR_MINIMUM)

        if not failed:
            assert family["verdict"]["name"] == VERDICT_VALIDATED
        else:
            assert family["verdict"]["name"] == VERDICT_NOT_VALIDATED
