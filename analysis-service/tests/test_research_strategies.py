from __future__ import annotations

from collections import Counter
from datetime import date
from typing import Any

import pytest
from research_fixtures import ALL_UNIVERSE_SYMBOLS, universe_series, weekdays

from app.research.data import Bar
from app.research.strategies import (
    HistoryView,
    MeanReversionStrategy,
    MomentumStrategy,
    PrefixMap,
    TrendStrategy,
    WilderRsi,
    build_strategies,
    compute_decisions,
    decision_schedule,
)


def bars_from_closes(closes: list[float], start: date = date(2021, 1, 4)) -> list[Bar]:
    return [Bar(day, close, close, close, close, 1_000.0) for day, close in zip(weekdays(start, len(closes)), closes)]


def _as_bars(series: dict[str, list[Any]]) -> dict[str, list[Bar]]:
    return {symbol: [Bar(*row) for row in rows] for symbol, rows in series.items()}


def test_grid_declares_54_trials_in_three_families() -> None:
    strategies = build_strategies(ALL_UNIVERSE_SYMBOLS)
    assert len(strategies) == 54
    assert Counter(strategy.family for strategy in strategies) == {
        "momentum": 24,
        "trend": 6,
        "mean_reversion": 24,
    }
    ids = [strategy.config_id() for strategy in strategies]
    assert len(set(ids)) == 54


def test_prefix_view_refuses_access_past_day_t() -> None:
    bars = bars_from_closes([1.0, 2.0, 3.0, 4.0, 5.0])
    view = HistoryView(bars, visible=3)
    assert len(view) == 3
    assert view[2].close == 3.0
    assert view[-1].close == 3.0
    assert [bar.close for bar in view[1:]] == [2.0, 3.0]
    assert len(view[0:10]) == 3
    with pytest.raises(IndexError):
        _ = view[3]
    with pytest.raises(IndexError):
        _ = view[-4]
    prefix = PrefixMap({"SPY": bars}, last_index=2)
    assert len(prefix["SPY"]) == 3


def test_every_strategy_is_unchanged_by_mutating_bars_after_t() -> None:
    count = 700
    cut = 420
    base = universe_series(count)
    mutated = {
        symbol: [
            row
            if index <= cut
            else (row[0], row[1] * 2.5, row[2] * 2.5, row[3] * 2.5, row[4] * 2.5, row[5])
            for index, row in enumerate(rows)
        ]
        for symbol, rows in base.items()
    }
    dates = [row[0] for row in base["SPY"]]
    for strategy in build_strategies(ALL_UNIVERSE_SYMBOLS):
        original = compute_decisions(strategy, _as_bars(base), dates)
        changed = compute_decisions(strategy, _as_bars(mutated), dates)
        through_cut = {t: w for t, w in original.items() if t <= cut}
        assert through_cut == {t: w for t, w in changed.items() if t <= cut}, strategy.config_id()
        assert any(t > cut for t in original), strategy.config_id()


def test_momentum_picks_highest_lookback_and_applies_absolute_filter() -> None:
    bars = {
        "AAA": bars_from_closes([100.0, 101.0, 102.0, 103.0, 104.0]),
        "BBB": bars_from_closes([100.0, 100.0, 100.0, 100.0, 100.0]),
        "CCC": bars_from_closes([100.0, 99.0, 98.0, 97.0, 96.0]),
    }
    view = PrefixMap(bars, last_index=4)
    filtered = MomentumStrategy(2, 0, 2, True, universe=("AAA", "BBB", "CCC"))
    unfiltered = MomentumStrategy(2, 0, 2, False, universe=("AAA", "BBB", "CCC"))
    weights_filtered, _ = filtered.step(view, None, True)
    weights_unfiltered, _ = unfiltered.step(view, None, True)
    assert weights_filtered == {"AAA": 0.5}
    assert weights_unfiltered == {"AAA": 0.5, "BBB": 0.5}


def test_momentum_skip_window_ignores_the_most_recent_day() -> None:
    # Lookback 2 with skip 1 measures close[t-3] -> close[t-1]. The final-day crash is skipped.
    bars = {
        "AAA": bars_from_closes([100.0, 100.0, 100.0, 150.0, 50.0]),
        "BBB": bars_from_closes([100.0, 100.0, 100.0, 110.0, 110.0]),
    }
    view = PrefixMap(bars, last_index=4)
    skip_one = MomentumStrategy(2, 1, 1, False, universe=("AAA", "BBB"))
    no_skip = MomentumStrategy(2, 0, 1, False, universe=("AAA", "BBB"))
    assert skip_one.step(view, None, True)[0] == {"AAA": 1.0}
    assert no_skip.step(view, None, True)[0] == {"BBB": 1.0}


def test_momentum_only_decides_on_month_end_schedule() -> None:
    dates = weekdays(date(2024, 1, 2), 60)
    schedule = decision_schedule("monthly", dates)
    assert schedule
    for t in schedule:
        assert dates[t].month != dates[t + 1].month
    assert max(schedule) < len(dates) - 1


def test_trend_sma_is_long_above_average_and_cash_below() -> None:
    rising = bars_from_closes([1.0, 2.0, 3.0, 4.0, 5.0, 6.0])
    falling = bars_from_closes([6.0, 5.0, 4.0, 3.0, 2.0, 1.0])
    strategy = TrendStrategy("sma", 3)
    assert strategy.step({"SPY": HistoryView(rising, 6)}, False, True)[0] == {"SPY": 1.0}
    assert strategy.step({"SPY": HistoryView(falling, 6)}, False, True)[0] == {}


def test_donchian_breakout_and_exit_sequence() -> None:
    closes = [1.0, 2.0, 3.0, 4.0, 3.5, 2.5, 2.6]
    bars = {"SPY": bars_from_closes(closes)}
    dates = [bar.date for bar in bars["SPY"]]
    decisions = compute_decisions(TrendStrategy("donchian", 3, 2), bars, dates)
    assert decisions == {
        0: {},
        1: {},
        2: {},
        3: {"SPY": 1.0},
        4: {"SPY": 1.0},
        5: {},
    }


def test_wilder_rsi_matches_hand_computation() -> None:
    rsi = WilderRsi(period=2)
    values = [rsi.update(close) for close in (10.0, 9.0, 8.0, 9.0, 7.0)]
    assert values[0] is None
    assert values[1] is None
    assert values[2] == pytest.approx(0.0)
    assert values[3] == pytest.approx(50.0)
    assert values[4] == pytest.approx(100.0 - 100.0 / 1.2)


def test_mean_reversion_enters_on_oversold_and_exits_after_max_hold() -> None:
    bars = {"SPY": bars_from_closes([10.0, 9.0, 8.0, 9.0, 7.0, 7.0])}
    dates = [bar.date for bar in bars["SPY"]]
    strategy = MeanReversionStrategy(rsi_n=2, threshold=20.0, max_hold=2, regime_filter=False)
    decisions = compute_decisions(strategy, bars, dates)
    assert decisions == {0: {}, 1: {}, 2: {"SPY": 1.0}, 3: {"SPY": 1.0}, 4: {}}


def test_mean_reversion_exits_when_close_clears_five_day_average() -> None:
    bars = {"SPY": bars_from_closes([10.0, 9.0, 8.0, 9.0, 7.0, 20.0, 20.0])}
    dates = [bar.date for bar in bars["SPY"]]
    strategy = MeanReversionStrategy(rsi_n=2, threshold=20.0, max_hold=10, regime_filter=False)
    decisions = compute_decisions(strategy, bars, dates)
    assert decisions[2] == {"SPY": 1.0}
    assert decisions[4] == {"SPY": 1.0}
    assert decisions[5] == {}


def test_mean_reversion_regime_filter_blocks_entry_without_long_average() -> None:
    bars = {"SPY": bars_from_closes([10.0, 9.0, 8.0, 9.0, 7.0, 7.0])}
    dates = [bar.date for bar in bars["SPY"]]
    strategy = MeanReversionStrategy(rsi_n=2, threshold=20.0, max_hold=2, regime_filter=True)
    decisions = compute_decisions(strategy, bars, dates)
    assert all(weights == {} for weights in decisions.values())
