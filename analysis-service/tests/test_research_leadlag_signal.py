from __future__ import annotations

import json
import random
from datetime import date
from pathlib import Path

import pytest
from research_fixtures import weekdays
from test_research_leadlag import make_world, write_derived, write_prior

from app.research import leadlag as L
from app.research import leadlag_signal as S
from app.research.data import Bar
from app.research.leadlag import Fold, Forecast, build_panel

BARS = 1520
PIT = L.RULE_POINT_IN_TIME


def _fold(test_start: int, test_end: int) -> Fold:
    return Fold(index=0, train_start=0, train_end=test_start - 1, test_start=test_start, test_end=test_end)


def _panel_from_opens(opens: dict[str, list[float]], closes: dict[str, list[float]] | None = None):
    days = weekdays(date(2010, 1, 4), len(next(iter(opens.values()))))
    bars = {}
    for symbol, series in opens.items():
        closing = closes[symbol] if closes else series
        bars[symbol] = [Bar(day, o, max(o, c), min(o, c), c, 1.0) for day, o, c in zip(days, series, closing)]
    return build_panel(bars, calendar=days)


# ---------------------------------------------------------------- statistics


def test_spearman_has_the_sign_of_a_planted_relation() -> None:
    rng = random.Random(3)
    xs = [rng.gauss(0.0, 1.0) for _ in range(200)]
    assert S.spearman(xs, [x + rng.gauss(0.0, 0.1) for x in xs]) > 0.9
    assert S.spearman(xs, [-x + rng.gauss(0.0, 0.1) for x in xs]) < -0.9


def test_spearman_handles_ties_with_average_ranks() -> None:
    assert S.spearman([1.0, 1.0, 2.0, 3.0], [1.0, 2.0, 3.0, 4.0]) == pytest.approx(0.9486832980505138)
    assert S.spearman([1.0, 2.0, 3.0], [5.0, 5.0, 5.0]) is None


def test_hac_ols_recovers_planted_coefficients_and_a_significant_alpha() -> None:
    rng = random.Random(11)
    spy = [rng.gauss(0.0, 0.01) for _ in range(3000)]
    ew = [rng.gauss(0.0, 0.01) for _ in range(3000)]
    y = [0.002 + 0.5 * s + 0.3 * e + rng.gauss(0.0, 0.002) for s, e in zip(spy, ew)]
    fit = S.hac_ols([[1.0, s, e] for s, e in zip(spy, ew)], y)
    assert fit["coef"][0] == pytest.approx(0.002, abs=0.0003)
    assert fit["coef"][1] == pytest.approx(0.5, abs=0.02)
    assert fit["coef"][2] == pytest.approx(0.3, abs=0.02)
    assert fit["t"][0] > 2.0
    # Expected R^2 = (0.5^2 + 0.3^2) s^2 / ((0.5^2 + 0.3^2) s^2 + 0.002^2) with s = 0.01, about 0.895.
    assert fit["r2"] == pytest.approx(0.895, abs=0.02)


def test_hac_ols_alpha_is_not_significant_without_a_planted_alpha() -> None:
    rng = random.Random(12)
    spy = [rng.gauss(0.0, 0.01) for _ in range(3000)]
    y = [0.5 * s + rng.gauss(0.0, 0.002) for s in spy]
    fit = S.hac_ols([[1.0, s] for s in spy], y)
    assert abs(fit["t"][0]) < 3.0


def test_hac_ols_rejects_a_singular_design() -> None:
    with pytest.raises(S.ResearchError):
        S.hac_ols([[1.0, 2.0, 2.0]] * 20, [0.1 * i for i in range(20)])


def test_alpha_regression_annualizes_the_daily_intercept() -> None:
    rng = random.Random(13)
    rows = [[1.0, rng.gauss(0.0, 0.01), rng.gauss(0.0, 0.01)] for _ in range(2000)]
    target = [0.001 + 0.5 * r[1] + 0.3 * r[2] + rng.gauss(0.0, 0.001) for r in rows]
    result = S.alpha_regression(target, rows)
    assert result["alpha_annualized"] == pytest.approx(0.001 * 252, abs=0.02)
    assert result["coef_names"] == ["const", "SPY", "EW"]


# ---------------------------------------------------------------- a. IC


def _planted_ic_state(sign: float):
    rng = random.Random(21)
    names = ["A", "B", "C", "D", "E", "F"]
    count = 60
    opens = {name: [100.0 * (1.0 + rng.gauss(0.0, 0.01)) for _ in range(count)] for name in names}
    days = weekdays(date(2010, 1, 4), count)
    bars = {name: [Bar(day, opens[name][i], opens[name][i], opens[name][i], opens[name][i], 1.0)
                   for i, day in enumerate(days)] for name in names}
    panel = build_panel(bars, calendar=days)
    fold = _fold(1, count)
    forecasts = {}
    for t in range(1, count - 2):
        for name in names:
            planted = opens[name][t + 2] / opens[name][t + 1] - 1.0 + rng.gauss(0.0, 1e-5)
            forecasts[(name, t)] = Forecast(sign * planted, sign * planted, False)
    fold_of_day = {t: fold for t in range(1, count - 2)}
    return panel, list(range(1, count - 2)), fold_of_day, {0: names}, forecasts


def test_daily_ic_has_the_sign_of_a_planted_forecast() -> None:
    panel, days, fold_of_day, universes, forecasts = _planted_ic_state(sign=1.0)
    series = S.daily_ic(panel, days, fold_of_day, universes, forecasts, "M0", "open_fwd", 1)
    assert len(series) == len(days)
    assert all(value > 0.95 for value in series)


def test_daily_ic_is_negative_for_an_inverted_forecast() -> None:
    panel, days, fold_of_day, universes, forecasts = _planted_ic_state(sign=-1.0)
    series = S.daily_ic(panel, days, fold_of_day, universes, forecasts, "M0", "open_fwd", 1)
    assert all(value < -0.95 for value in series)


def test_daily_ic_outcomes_use_only_bars_after_the_forecast_day() -> None:
    panel, days, fold_of_day, universes, forecasts = _planted_ic_state(sign=1.0)
    t = days[0]
    stock = universes[0][0]
    assert S._open_to_open(panel, stock, t, 1) == pytest.approx(panel.open[stock][t + 2] / panel.open[stock][t + 1] - 1)
    assert S._gap(panel, stock, t) == pytest.approx(panel.open[stock][t + 1] / panel.close[stock][t] - 1)


# ---------------------------------------------------------------- c. event study


def test_event_study_excess_matches_a_hand_computed_case() -> None:
    panel = _panel_from_opens({
        "A": [100.0, 100.0, 110.0, 121.0],
        "B": [100.0, 100.0, 100.0, 100.0],
        "C": [100.0, 100.0, 90.0, 99.0],
    })
    fold = _fold(1, 4)
    forecasts = {("A", 0): Forecast(0.5, 0.5, False), ("B", 0): Forecast(0.001, 0.001, False),
                 ("C", 0): Forecast(-0.2, -0.2, False)}
    study = S.event_study(panel, [0], {0: fold}, {0: ["A", "B", "C"]}, forecasts, "M0", 2, horizons=(1, 2))
    assert [(r.t, r.stock) for r in study.records] == [(0, "A")]
    # h=1: open 100 -> 110, EW of (+10%, 0%, -10%) = 0, excess = +10%
    assert study.records[0].excess[1] == pytest.approx(0.10)
    # h=2: open 100 -> 121 for A (+21%); EW = (0.21 + 0 + (-0.01)) / 3
    assert study.records[0].excess[2] == pytest.approx(0.21 - 0.20 / 3)
    assert study.daily[1] == [(0, pytest.approx(0.10))]


def test_event_study_drops_a_pick_whose_outcome_bar_is_missing_for_that_horizon() -> None:
    panel = _panel_from_opens({"A": [100.0, 100.0, 110.0, 121.0], "B": [100.0, 100.0, 100.0, 100.0]})
    panel.open["A"][3] = None  # the h=2 exit bar is missing for A only
    fold = _fold(1, 4)
    forecasts = {("A", 0): Forecast(0.5, 0.5, False), ("B", 0): Forecast(0.0, 0.0, False)}
    study = S.event_study(panel, [0], {0: fold}, {0: ["A", "B"]}, forecasts, "M0", 1, horizons=(1, 2))
    assert 1 in study.records[0].excess and 2 not in study.records[0].excess


def test_cumulative_table_flags_the_30bp_and_60bp_thresholds_with_t_above_two() -> None:
    rng = random.Random(31)
    steady = [(t, 0.004 + rng.gauss(0.0, 0.0003)) for t in range(200)]
    big = [(t, 0.007 + rng.gauss(0.0, 0.0003)) for t in range(200)]
    study = S.EventStudy(model="M1", k=5, records=[], daily={1: steady, 2: big})
    rows = {row["h"]: row for row in S.cumulative_table(study, horizons=(1, 2))}
    assert rows[1]["above_30bp_t2"] is True
    assert rows[1]["above_60bp_t2"] is False
    assert rows[2]["above_30bp_t2"] is True and rows[2]["above_60bp_t2"] is True
    assert S.first_horizon(list(rows.values()), "above_30bp_t2") == 1
    assert S.first_horizon(list(rows.values()), "above_60bp_t2") == 2


def test_cumulative_table_does_not_flag_a_mean_above_cost_with_insufficient_t() -> None:
    noisy = [(t, 0.01 if t % 2 else -0.0085) for t in range(40)]  # mean 0.00075, below the 30bp cost
    study = S.EventStudy(model="M1", k=5, records=[], daily={1: noisy})
    rows = S.cumulative_table(study, horizons=(1,))
    assert rows[0]["above_30bp_t2"] is False


# ---------------------------------------------------------------- e. buckets and decades


def test_survivorship_bucket_boundaries() -> None:
    returns = [-0.06, -0.05, -0.03, -0.02, 0.0, 0.01]
    records = [S.EventRecord(t=1, stock=f"S{i}", own_return=r, excess={1: 0.001}) for i, r in enumerate(returns)]
    study = S.EventStudy(model="M0", k=5, records=records, daily={1: []})
    counts = [row["count"] for row in S.survivorship_buckets(study, None)]
    assert counts == [1, 2, 2, 1]


def test_survivorship_share_of_total_excess_sums_to_one() -> None:
    records = [S.EventRecord(t=1, stock="A", own_return=-0.06, excess={1: 0.02}),
               S.EventRecord(t=1, stock="B", own_return=0.01, excess={1: 0.01}),
               S.EventRecord(t=2, stock="C", own_return=0.02, excess={1: 0.01})]
    study = S.EventStudy(model="M0", k=5, records=records, daily={1: []})
    rows = S.survivorship_buckets(study, None)
    assert sum(row["share_of_total_excess"] for row in rows) == pytest.approx(1.0)


# ---------------------------------------------------------------- f. verdict


def _row(h: int, passes: bool) -> dict:
    return {"h": h, "above_30bp_t2": passes, "above_60bp_t2": False}


def test_verdict_is_convertible_only_when_cumulative_and_alpha_both_pass() -> None:
    result = S.verdict([_row(5, True)], alpha_t=2.5)
    assert result["status"] == "CONVERTIBLE"
    assert result["qualifying_horizons"] == [5]
    assert result["reasons"] == []


def test_verdict_fails_without_a_cumulative_horizon_above_cost() -> None:
    result = S.verdict([_row(h, False) for h in range(1, 21)], alpha_t=3.0)
    assert result["status"] == "NOT_CONVERTIBLE"
    assert any("30bp" in reason for reason in result["reasons"])


def test_verdict_fails_when_alpha_t_is_not_above_two() -> None:
    assert S.verdict([_row(11, True)], alpha_t=1.99)["status"] == "NOT_CONVERTIBLE"
    assert S.verdict([_row(11, True)], alpha_t=None)["status"] == "NOT_CONVERTIBLE"
    assert S.verdict([_row(11, True)], alpha_t=2.0)["status"] == "NOT_CONVERTIBLE"  # strictly greater than 2


# ---------------------------------------------------------------- end to end


def _world_dir(tmp_path: Path, seed: int, beta: float) -> tuple[Path, Path]:
    derived = tmp_path / "derived"
    write_derived(derived, make_world(BARS, seed, beta))
    prior = write_prior(tmp_path / "prior.json", 54)
    return derived, prior


def test_end_to_end_engine_reconciles_with_the_ll1_run_and_reports_every_section(tmp_path: Path) -> None:
    derived, prior = _world_dir(tmp_path, 101, 0.5)
    reference = L.run_from_directory(derived, prior, universe_rule=PIT)
    report = S.run_diagnostics(derived, PIT, reference=reference)
    for name, info in report["c_engine_efficiency"].items():
        assert info["reference_diff"] < 1e-9, name
    for section in ("a_predictive_power", "b_persistence", "c_event_study", "c_engine_efficiency",
                    "d_exposure", "e_survivorship", "f_verdict"):
        assert section in report
    assert report["f_verdict"]["status"] in {"CONVERTIBLE", "NOT_CONVERTIBLE"}


def test_planted_lead_lag_gives_positive_m1_ic_at_the_tradable_horizon(tmp_path: Path) -> None:
    derived, _ = _world_dir(tmp_path, 102, 0.5)
    state = S.build_state(derived, PIT)
    series = S.daily_ic(state.panel, state.days, state.fold_of_day, state.universes, state.forecasts,
                        "M1", "open_fwd", 1)
    mean = sum(series) / len(series)
    assert mean > 0.0


def test_cli_writes_json_and_korean_markdown_with_the_verdict(tmp_path: Path) -> None:
    derived, prior = _world_dir(tmp_path, 103, 0.0)
    out, markdown = tmp_path / "signal.json", tmp_path / "signal.md"
    assert S.main(["--data", str(derived), "--universe-rule", PIT, "--out", str(out),
                   "--markdown", str(markdown)]) == 0
    report = json.loads(out.read_text(encoding="utf-8"))
    text = markdown.read_text(encoding="utf-8")
    assert report["f_verdict"]["status"] in text
    assert "## a. 예측력" in text and "## f. 판정" in text
    assert text.index("해석 규칙(사전 고정)") < text.index("| 모델 | 구간 |")
