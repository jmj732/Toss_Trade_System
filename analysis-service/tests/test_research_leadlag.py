from __future__ import annotations

import hashlib
import json
import math
import random
from collections.abc import Mapping
from datetime import date
from functools import lru_cache
from pathlib import Path
import pytest
from research_fixtures import weekdays

from app.research import leadlag, validation
from app.research.data import Bar
from app.research.leadlag import (
    BENCHMARK,
    OUTCOME_G1_FAILED,
    SECTOR_ETFS,
    SECTOR_OF,
    SECTOR_STOCKS,
    STOCKS,
    Forecast,
    build_panel,
    clark_west_loss,
    decisions_for,
    fit_fold,
    fold_universe,
    forecasts_for_fold,
    leader_table,
    newey_west_mean_test,
    ols,
    run_experiment,
    tile_folds,
)

START = date(2010, 1, 1)
BARS = 1520  # three walk-forward folds (756 OOS bars) plus the warm-up bar
LEADER_OF = {etf: stocks[0] for etf, stocks in SECTOR_STOCKS.items()}


def make_world(count: int, seed: int, beta: float = 0.0,
               late_starts: Mapping[str, int] | None = None) -> dict[str, list[Bar]]:
    """Deterministic 43-symbol world. Leaders (first stock per sector) carry 10x dollar volume.

    beta > 0 plants a lead-lag effect: every follower's open-to-open gap at t+2 includes
    beta times the leader's close-to-close return on day t.
    """
    rng = random.Random(seed)
    days = weekdays(START, count)
    market = [rng.gauss(0.0, 0.009) for _ in range(count)]
    sectors = {etf: [rng.gauss(0.0, 0.007) for _ in range(count)] for etf in SECTOR_ETFS}
    world: dict[str, list[Bar]] = {}

    def path(symbol: str, intra_factor: list[float], gap_extra: list[float] | None, volume: float) -> list[float]:
        opens, closes = [], []
        previous = 100.0
        for t in range(count):
            gap = rng.gauss(0.0, 0.003) + (gap_extra[t] if gap_extra is not None else 0.0)
            open_ = previous * (1.0 + gap)
            close = open_ * (1.0 + intra_factor[t] + rng.gauss(0.0, 0.005))
            opens.append(open_)
            closes.append(close)
            previous = close
        vols = [volume * (1.0 + 0.1 * rng.random()) for _ in range(count)]
        world[symbol] = [Bar(days[t], opens[t], max(opens[t], closes[t]), min(opens[t], closes[t]), closes[t],
                             vols[t]) for t in range(count)]
        return closes

    path(BENCHMARK, market, None, 5e7)
    for etf in SECTOR_ETFS:
        path(etf, [0.5 * m + s for m, s in zip(market, sectors[etf])], None, 3e7)
    for etf, stocks in SECTOR_STOCKS.items():
        leader = stocks[0]
        leader_closes = path(leader, [0.4 * m + s for m, s in zip(market, sectors[etf])], None, 1e7 * 10)
        leader_returns = [0.0] + [leader_closes[t] / leader_closes[t - 1] - 1.0 for t in range(1, count)]
        gap_extra = [beta * leader_returns[t - 2] if t >= 2 else 0.0 for t in range(count)]
        for stock in stocks[1:]:
            path(stock, [0.4 * m + s for m, s in zip(market, sectors[etf])], gap_extra, 1e6)
    for symbol, start in (late_starts or {}).items():
        world[symbol] = world[symbol][start:]
    return world


@lru_cache(maxsize=4)
def cached_world(count: int, seed: int, beta: float) -> dict[str, list[Bar]]:
    return make_world(count, seed, beta)


def panel_of(world: dict[str, list[Bar]]):
    return build_panel(world, world)


# ---------------------------------------------------------------- statistics


def test_clark_west_loss_hand_computed() -> None:
    # e0 = 1 - 0.5 = 0.5, e1 = 1 - 0.8 = 0.2, (yhat1 - yhat0)^2 = 0.09
    # f = 0.25 - (0.04 - 0.09) = 0.30
    assert clark_west_loss(1.0, 0.5, 0.8) == pytest.approx(0.30)


def test_clark_west_loss_is_zero_when_models_agree() -> None:
    assert clark_west_loss(0.7, 0.2, 0.2) == 0.0


def test_newey_west_variance_matches_hand_computed_lag_one() -> None:
    # Alternating +-1 around mean 0: gamma_0 = 1, gamma_1 = -5/6. V = 1 + 2(1/2)(-5/6) = 1/6.
    # se = sqrt(V / T) = sqrt((1/6) / 6) = 1/6.
    result = newey_west_mean_test([1.0, -1.0, 1.0, -1.0, 1.0, -1.0], lag=1)
    assert result is not None
    assert result["nw_standard_error"] == pytest.approx(1 / 6)


def test_newey_west_mean_shift_gives_expected_t_and_one_sided_p() -> None:
    # Same alternating deviations shifted by mean 1: t = 1 / (1/6) = 6.
    result = newey_west_mean_test([2.0, 0.0, 2.0, 0.0, 2.0, 0.0], lag=1)
    assert result is not None
    assert result["t"] == pytest.approx(6.0)
    assert result["p_one_sided"] == pytest.approx(0.5 * math.erfc(6.0 / math.sqrt(2.0)))


def test_newey_west_lag_two_uses_bartlett_weights() -> None:
    # gamma_0 = 1, gamma_1 = -5/6, gamma_2 = 2/3. Bartlett weights for lag 2: w1 = 2/3, w2 = 1/3.
    # V = 1 + 2 * (2/3) * (-5/6) + 2 * (1/3) * (2/3) = 1/3, so se = sqrt((1/3) / 6) = sqrt(1/18).
    result = newey_west_mean_test([1.0, -1.0, 1.0, -1.0, 1.0, -1.0], lag=2)
    assert result is not None
    assert result["nw_standard_error"] == pytest.approx(math.sqrt(1 / 18))


def test_newey_west_needs_enough_observations() -> None:
    assert newey_west_mean_test([1.0, 2.0, 3.0], lag=5) is None


def test_ols_recovers_exact_coefficients() -> None:
    design = [[1.0, x, x * x] for x in (-2.0, -1.0, 0.0, 1.0, 2.0, 3.0)]
    target = [1.0 + 2.0 * x + 0.5 * x * x for x in (-2.0, -1.0, 0.0, 1.0, 2.0, 3.0)]
    coefficients = ols(design, target)
    assert coefficients is not None
    assert coefficients == pytest.approx([1.0, 2.0, 0.5], abs=1e-9)


def test_ols_singular_design_returns_none() -> None:
    design = [[1.0, x, 2.0 * x] for x in range(10)]
    assert ols(design, [float(x) for x in range(10)]) is None


# ---------------------------------------------------------------- panel, leader and folds


def test_build_panel_rejects_raw_dates_that_differ_from_adjusted() -> None:
    world = make_world(60, 1)
    raw = {symbol: bars[:-1] for symbol, bars in world.items()}
    with pytest.raises(leadlag.LeadLagError):
        build_panel(world, raw)


def test_leader_is_point_in_time_future_volume_does_not_change_past_leaders() -> None:
    world = cached_world(BARS, 21, 0.0)
    base = leader_table(panel_of(world))
    cut = 800
    perturbed = {symbol: list(bars) for symbol, bars in world.items()}
    rng = random.Random(5)
    for symbol in list(perturbed):
        for t in range(cut, BARS):
            bar = perturbed[symbol][t]
            perturbed[symbol][t] = Bar(bar.date, bar.open, bar.high, bar.low, bar.close,
                                       bar.volume * rng.uniform(0.01, 100.0))
    changed = leader_table(panel_of(perturbed))
    for etf in SECTOR_ETFS:
        assert changed[etf][:cut] == base[etf][:cut]


def test_leader_ignores_dividend_adjusted_prices_and_uses_raw_dollar_volume() -> None:
    # Adjusted closes carry future dividend factors; the ranking must use raw close x raw volume only.
    world = cached_world(BARS, 22, 0.0)
    base = leader_table(build_panel(world, world))
    cut = 700
    adjusted_future = {}
    for symbol, bars in world.items():
        adjusted_future[symbol] = [
            Bar(b.date, b.open * 0.5, b.high * 0.5, b.low * 0.5, b.close * 0.5, b.volume) if i >= cut else b
            for i, b in enumerate(bars)
        ]
    changed = leader_table(build_panel(adjusted_future, world))
    for etf in SECTOR_ETFS:
        assert changed[etf][:cut] == base[etf][:cut]


def test_leader_switches_to_the_higher_trailing_dollar_volume_stock_only_after_it_is_higher() -> None:
    world = make_world(120, 3)
    etf = "XLK"
    leader, challenger = SECTOR_STOCKS[etf][0], SECTOR_STOCKS[etf][1]
    shifted = dict(world)
    shifted[challenger] = [Bar(b.date, b.open, b.high, b.low, b.close, b.volume * 200.0) for b in world[challenger]]
    table = leader_table(panel_of(shifted))
    # The first 62 days lack a complete 63-bar window, so no leader is defined there.
    assert table[etf][61] is None
    assert table[etf][62] is not None and table[etf][62][0] == challenger
    assert leader != challenger


def test_tile_folds_has_no_holdout_and_uses_756_252_252() -> None:
    folds = tile_folds(BARS)
    assert [(f.train_start, f.train_end, f.test_start, f.test_end) for f in folds] == [
        (1, 757, 757, 1009), (253, 1009, 1009, 1261), (505, 1261, 1261, 1513),
    ]


def test_fold_universe_excludes_a_late_listed_symbol_whose_windows_are_incomplete() -> None:
    world = make_world(BARS, 7, late_starts={"AAPL": 900})
    panel = panel_of(world)
    folds = tile_folds(BARS)
    # Every fold window starts before bar 900, so AAPL lacks complete data in all three folds.
    for fold in folds:
        assert "AAPL" not in fold_universe(panel, fold)
        assert "MSFT" in fold_universe(panel, fold)
    # With full history the same symbol is a member of every fold.
    complete = panel_of(make_world(BARS, 7))
    assert all("AAPL" in fold_universe(complete, fold) for fold in folds)


def test_training_is_causal_and_ignores_bars_from_the_train_end_onward() -> None:
    world = cached_world(BARS, 31, 0.0)
    fold = tile_folds(BARS)[0]
    stock = "MSFT"
    base_fit = fit_fold(panel_of(world), fold, stock, _rets(panel_of(world)), leader_table(panel_of(world)))

    changed = {symbol: list(bars) for symbol, bars in world.items()}
    rng = random.Random(99)
    for symbol in changed:
        for t in range(fold.train_end, BARS):
            bar = changed[symbol][t]
            factor = rng.uniform(0.5, 1.5)
            changed[symbol][t] = Bar(bar.date, bar.open * factor, bar.high * factor, bar.low * factor,
                                     bar.close * factor, bar.volume)
    changed_panel = panel_of(changed)
    changed_fit = fit_fold(changed_panel, fold, stock, _rets(changed_panel), leader_table(changed_panel))
    assert changed_fit.m0 == pytest.approx(base_fit.m0, rel=1e-12)
    assert changed_fit.m1 == pytest.approx(base_fit.m1, rel=1e-12)

    t_decision = fold.test_start - 1
    base_forecast, _ = forecasts_for_fold(panel_of(world), fold, [stock], _rets(panel_of(world)),
                                          leader_table(panel_of(world)), [t_decision])
    changed_forecast, _ = forecasts_for_fold(changed_panel, fold, [stock], _rets(changed_panel),
                                             leader_table(changed_panel), [t_decision])
    assert changed_forecast[(stock, t_decision)].m0 == pytest.approx(base_forecast[(stock, t_decision)].m0)


def _rets(panel):
    return {symbol: leadlag._returns(panel.close[symbol]) for symbol in panel.close}


def test_self_leader_m1_forecast_equals_m0() -> None:
    world = cached_world(BARS, 41, 0.0)
    panel = panel_of(world)
    fold = tile_folds(BARS)[0]
    leader = LEADER_OF["XLK"]
    rets = _rets(panel)
    leaders = leader_table(panel)
    universe = fold_universe(panel, fold)
    days = [fold.test_start + 5]
    forecasts, _ = forecasts_for_fold(panel, fold, universe, rets, leaders, days)
    own = forecasts[(leader, days[0])]
    assert own.leader_self is True
    assert own.m1 == own.m0


def test_decisions_pick_top_k_above_threshold_with_equal_weights_and_name_tie_break() -> None:
    forecasts = {
        ("AAA", 10): Forecast(0.0050, 0.0050, False),
        ("BBB", 10): Forecast(0.0050, 0.0050, False),
        ("CCC", 10): Forecast(0.0100, 0.0100, False),
        ("DDD", 10): Forecast(0.0020, 0.0020, False),
        ("EEE", 10): Forecast(0.0300, 0.0300, False),
        ("AAA", 11): Forecast(0.0010, 0.0010, False),
    }
    decisions = decisions_for(forecasts, "M0", 3, ["AAA", "BBB", "CCC", "DDD", "EEE"], [10, 11], origin=10)
    assert decisions[0] == pytest.approx({"EEE": 1 / 3, "CCC": 1 / 3, "AAA": 1 / 3})
    assert decisions[1] == {}


def test_decisions_tie_break_is_by_symbol_name() -> None:
    forecasts = {("ZZZ", 5): Forecast(0.01, 0.01, False), ("AAA", 5): Forecast(0.01, 0.01, False)}
    decisions = decisions_for(forecasts, "M1", 1, ["ZZZ", "AAA"], [5], origin=5)
    assert decisions[0] == {"AAA": 1.0}


# ---------------------------------------------------------------- experiment


@lru_cache(maxsize=2)
def _experiment(beta: float, seed: int, prior_variance: float | None):
    world = cached_world(BARS, seed, beta)
    panel = panel_of(world)
    return run_experiment(panel, adjustment_basis="SPLIT_AND_DIVIDEND", prior_variance=prior_variance,
                          prior_trial_count=54, dataset_hash="synthetic")


def test_pure_noise_world_does_not_pass_g1() -> None:
    report = _experiment(0.0, 51, 0.000128)
    assert report["gates"]["G1_pass"] is False
    assert report["outcome"]["status"] in {OUTCOME_G1_FAILED, "INSUFFICIENT_DATA"}
    assert report["tests"]["T1_pass"] is False


def test_planted_lead_lag_signal_is_detected_by_clark_west() -> None:
    report = _experiment(0.5, 52, 0.000128)
    t1 = report["tests"]["T1_clark_west_target"]
    assert report["tests"]["T1_pass"] is True
    assert t1["mean"] > 0.0
    assert t1["p_one_sided"] < 0.05


def test_trial_variance_is_the_larger_of_new_and_prior() -> None:
    report = _experiment(0.0, 53, 1.0)
    trials = report["trials"]
    assert trials["used_variance_of_sharpe_per_period"] == pytest.approx(1.0)
    assert trials["count"] == 58
    assert report["prior_trials"]["count"] == 54


def test_experiment_reports_spy_benchmark_and_four_trials() -> None:
    report = _experiment(0.0, 54, 0.000128)
    assert sorted(report["models"]) == ["M0_k3", "M0_k5", "M1_k3", "M1_k5"]
    assert report["benchmark"]["symbol"] == BENCHMARK
    assert report["benchmark"]["out_of_sample"]["observations"] == report["dataset"]["oos_observations"]
    assert report["config"]["gates"]["total_trials"] == 58


def test_experiment_refuses_a_world_without_spy() -> None:
    world = dict(cached_world(BARS, 55, 0.0))
    del world[BENCHMARK]
    with pytest.raises(leadlag.LeadLagError):
        run_experiment(panel_of(world), adjustment_basis="SPLIT_AND_DIVIDEND", prior_variance=None,
                       prior_trial_count=54)


def test_markdown_renders_the_stop_rule_recommendation() -> None:
    report = _experiment(0.0, 56, 0.000128)
    report = dict(report)
    report.update({"config_hash": "x", "prereg": {"id": "LL-1", "commit": "84fe065"},
                   "dataset": dict(report["dataset"], dataset_hash="synthetic")})
    text = leadlag.render_markdown(report)
    assert report["outcome"]["recommendation"] in text


def test_validation_constants_are_reused_not_copied() -> None:
    assert leadlag.validation.TRAIN_BARS == validation.TRAIN_BARS == 756
    assert leadlag.validation.TEST_BARS == validation.TEST_BARS == 252
    assert leadlag.validation.STEP_BARS == validation.STEP_BARS == 252
    assert leadlag.validation.DSR_MINIMUM == 0.95


def test_required_symbol_set_matches_prereg_table() -> None:
    assert len(STOCKS) == 35
    assert len(SECTOR_ETFS) == 7
    assert SECTOR_OF["JPM"] == "XLF"
    assert SECTOR_OF["AMZN"] == "XLY"


def write_derived(root: Path, world: dict[str, list[Bar]], *, drop: str | None = None) -> None:
    """Write a derived-style directory: adjusted CSVs at top level, raw CSVs under unadjusted/."""
    (root / "unadjusted").mkdir(parents=True, exist_ok=True)
    symbols: dict[str, dict] = {}
    raw_entries: dict[str, dict] = {}
    for symbol, bars in world.items():
        for folder, store, relative in ((root, symbols, f"{symbol}.csv"),
                                        (root, raw_entries, f"unadjusted/{symbol}.csv")):
            path = root / relative
            lines = ["date,open,high,low,close,volume"]
            lines += [f"{b.date.isoformat()},{b.open!r},{b.high!r},{b.low!r},{b.close!r},{int(b.volume)}"
                      for b in bars]
            path.write_text("\n".join(lines) + "\n", encoding="utf-8")
            store[symbol] = {"file": relative, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                             "rows": len(bars), "first_date": bars[0].date.isoformat(),
                             "last_date": bars[-1].date.isoformat(),
                             "pages": [{"before": None, "observed_at": "2026-01-02T00:00:00Z"}]}
    manifest = {"provider": "TOSS", "endpoint": "/api/v1/candles", "interval": "1d", "adjusted": True,
                "adjustment_basis": "SPLIT_AND_DIVIDEND", "collected_at": "2026-01-02T00:00:00Z",
                "symbols": symbols, "unadjusted": raw_entries}
    (root / "manifest.json").write_text(json.dumps(manifest, sort_keys=True), encoding="utf-8")


def write_prior(path: Path, count: int) -> Path:
    path.write_text(json.dumps({"config_hash": "abc", "trials": {
        "count": count, "variance_of_research_sharpe_per_period": 0.000128}}), encoding="utf-8")
    return path


def test_cli_writes_identical_json_and_markdown_for_identical_inputs(tmp_path: Path) -> None:
    derived = tmp_path / "derived"
    write_derived(derived, cached_world(BARS, 61, 0.0))
    prior = write_prior(tmp_path / "prior.json", 54)
    out_a, out_b, markdown = tmp_path / "a.json", tmp_path / "b.json", tmp_path / "report.md"
    assert leadlag.main(["--data", str(derived), "--prior-report", str(prior), "--out", str(out_a),
                         "--markdown", str(markdown)]) == 0
    assert leadlag.main(["--data", str(derived), "--prior-report", str(prior), "--out", str(out_b)]) == 0
    assert out_a.read_bytes() == out_b.read_bytes()
    report = json.loads(out_a.read_text(encoding="utf-8"))
    assert report["prereg"]["commit"] == "84fe065"
    assert report["prior_report"]["trial_count"] == 54
    assert report["dataset"]["dataset_hash"]
    assert report["dataset"]["symbols_missing"] == []
    assert "LL-1" in markdown.read_text(encoding="utf-8")


def test_cli_refuses_a_prior_report_that_is_not_the_54_trial_report(tmp_path: Path) -> None:
    derived = tmp_path / "derived"
    write_derived(derived, cached_world(BARS, 62, 0.0))
    with pytest.raises(leadlag.LeadLagError):
        leadlag.run_from_directory(derived, write_prior(tmp_path / "prior.json", 53))


def test_cli_reports_missing_symbols_without_fabricating_them(tmp_path: Path) -> None:
    world = dict(cached_world(BARS, 63, 0.0))
    del world["CSCO"]
    derived = tmp_path / "derived"
    write_derived(derived, world)
    report = leadlag.run_from_directory(derived, write_prior(tmp_path / "prior.json", 54))
    assert "CSCO" in report["dataset"]["symbols_missing"]
    assert "CSCO" not in report["dataset"]["stocks_loaded"]
    assert all("CSCO" not in fold["universe"] for fold in report["folds"])


def test_cli_stops_when_a_sector_factor_is_missing(tmp_path: Path) -> None:
    world = dict(cached_world(BARS, 64, 0.0))
    del world["XLK"]
    derived = tmp_path / "derived"
    write_derived(derived, world)
    with pytest.raises(leadlag.LeadLagError, match="XLK"):
        leadlag.run_from_directory(derived, write_prior(tmp_path / "prior.json", 54))


def test_folds_start_only_after_every_sector_etf_has_its_first_bar() -> None:
    late_etfs = {etf: 300 for etf in SECTOR_ETFS}
    world = make_world(1900, 71, 0.0, late_starts=late_etfs)
    report = leadlag.run_experiment(build_panel(world, world, calendar=[b.date for b in world[BENCHMARK]]),
                                    adjustment_basis="SPLIT_AND_DIVIDEND", prior_variance=0.000128,
                                    prior_trial_count=54)
    assert report["dataset"]["first_train_index"] == 301
    assert report["folds"][0]["train_realization_range"][0] == 301
    assert report["folds"][0]["test_realization_range"][0] == 1057
    assert report["dataset"]["oos_observations"] == 756
    assert report["outcome"]["status"] in {"G1_FAILED", "G1_PASSED_G2_FAILED", "G1_G2_PASSED"}
    # Every trial trades (or holds cash) on a fully defined return path: no undefined Sharpe.
    assert all(info["summary"]["sharpe_per_period"] is not None for info in report["models"].values())


def test_late_listed_stock_is_absent_from_early_fold_universes_and_present_in_later_ones() -> None:
    world = make_world(1900, 72, 0.0, late_starts={**{etf: 300 for etf in SECTOR_ETFS}, "AAPL": 400})
    report = leadlag.run_experiment(build_panel(world, world, calendar=[b.date for b in world[BENCHMARK]]),
                                    adjustment_basis="SPLIT_AND_DIVIDEND", prior_variance=0.000128,
                                    prior_trial_count=54)
    assert "AAPL" not in report["folds"][0]["universe"]
    assert "AAPL" in report["folds"][1]["universe"]
    assert "MSFT" in report["folds"][0]["universe"]


def test_stray_off_calendar_bars_are_counted_and_ignored() -> None:
    world = make_world(60, 73)
    calendar = [b.date for b in world[BENCHMARK]]
    stray = Bar(date(2030, 1, 1), 1.0, 1.0, 1.0, 1.0, 1.0)
    world["MSFT"] = list(world["MSFT"]) + [stray]
    panel = build_panel(world, world, calendar=calendar)
    assert panel.off_calendar["MSFT"] == 1
    assert panel.off_calendar["AAPL"] == 0
    assert len(panel.dates) == len(calendar)
