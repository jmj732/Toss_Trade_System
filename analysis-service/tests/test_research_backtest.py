from __future__ import annotations

from datetime import date

import pytest
from research_fixtures import weekdays

from app.research.backtest import cost_rate_from_bps, restrict_decisions, simulate
from app.research.data import Bar, ResearchError


def bars_from_prices(symbol_prices: dict[str, list[tuple[float, float]]]) -> dict[str, list[Bar]]:
    """Each entry is (open, close) per day. High and low are set to the bar extremes."""
    count = len(next(iter(symbol_prices.values())))
    days = weekdays(date(2021, 1, 4), count)
    result: dict[str, list[Bar]] = {}
    for symbol, prices in symbol_prices.items():
        result[symbol] = [
            Bar(day, o, max(o, c), min(o, c), c, 1_000.0) for day, (o, c) in zip(days, prices)
        ]
    return result


def _dates(bars: dict[str, list[Bar]]) -> list[date]:
    return [bar.date for bar in next(iter(bars.values()))]


def test_open_fill_entry_and_exit_with_cost_matches_hand_calculation() -> None:
    # Day 0 close 100 -> decision to hold 100% SPY, executed at day 1 open 100.
    # Day 1 close 110. Day 2 flat at 110. Decision at day 2 close: exit, executed at day 3 open 110.
    bars = bars_from_prices({"SPY": [(100.0, 100.0), (100.0, 110.0), (110.0, 110.0), (110.0, 121.0)]})
    cost = 0.002
    result = simulate(bars, _dates(bars), {0: {"SPY": 1.0}, 2: {}}, cost)

    # Realization day 1: entry from cash at open 100 -> (1 - cost) * close/open - 1.
    assert result.returns[0] == pytest.approx((1.0 - cost) * 1.10 - 1.0, abs=1e-15)
    assert result.returns[0] == pytest.approx(0.0978, abs=1e-15)
    # Realization day 2: fully invested, no trade, flat price -> zero return and no cost.
    assert result.returns[1] == pytest.approx(0.0, abs=1e-15)
    # Realization day 3: exit at open 110 with cost on 100% notional, flat intraday.
    assert result.returns[2] == pytest.approx(-cost, abs=1e-15)
    assert result.equity[-1] == pytest.approx(1.1 * (1.0 - cost) ** 2, abs=1e-14)

    assert result.turnover == (1.0, 0.0, 1.0)
    assert [(t.symbol, round(t.from_weight, 12), round(t.to_weight, 12)) for t in result.trades] == [
        ("SPY", 0.0, 1.0),
        ("SPY", 1.0, 0.0),
    ]
    assert result.trades[0].date == _dates(bars)[1]


def test_decision_fills_at_next_open_not_at_decision_close() -> None:
    bars = bars_from_prices({"SPY": [(100.0, 50.0), (80.0, 90.0), (90.0, 90.0)]})
    result = simulate(bars, _dates(bars), {0: {"SPY": 1.0}}, 0.0)
    # Filled at open 80 (not close 50): return from open to close 90.
    assert result.returns[0] == pytest.approx(90.0 / 80.0 - 1.0, abs=1e-15)


def test_two_asset_drift_without_trades_matches_closed_form() -> None:
    bars = bars_from_prices(
        {
            "AAA": [(100.0, 100.0), (101.0, 102.0), (102.0, 106.0), (100.0, 98.0)],
            "BBB": [(100.0, 100.0), (99.0, 99.0), (98.0, 96.0), (97.0, 103.0)],
        }
    )
    result = simulate(bars, _dates(bars), {0: {"AAA": 0.5, "BBB": 0.5}}, 0.0)
    value_aaa = 0.5 * bars["AAA"][3].close / bars["AAA"][1].open
    value_bbb = 0.5 * bars["BBB"][3].close / bars["BBB"][1].open
    assert result.equity[-1] == pytest.approx(value_aaa + value_bbb, abs=1e-12)
    final = result.weights[-1]
    assert final["AAA"] == pytest.approx(value_aaa / (value_aaa + value_bbb), abs=1e-12)
    assert final["BBB"] == pytest.approx(value_bbb / (value_aaa + value_bbb), abs=1e-12)
    assert sum(final.values()) == pytest.approx(1.0, abs=1e-12)
    assert len(result.trades) == 2


def test_rebalance_trades_from_drifted_weights_not_from_target() -> None:
    bars = bars_from_prices(
        {
            "AAA": [(100.0, 100.0), (100.0, 120.0), (120.0, 120.0)],
            "BBB": [(100.0, 100.0), (100.0, 100.0), (100.0, 100.0)],
        }
    )
    result = simulate(bars, _dates(bars), {0: {"AAA": 0.5, "BBB": 0.5}, 1: {"AAA": 0.5, "BBB": 0.5}}, 0.0)
    rebalance = [trade for trade in result.trades if trade.date == _dates(bars)[2]]
    drifted_aaa = 0.5 * 1.2 / 1.1
    assert {trade.symbol for trade in rebalance} == {"AAA", "BBB"}
    aaa = next(trade for trade in rebalance if trade.symbol == "AAA")
    assert aaa.from_weight == pytest.approx(drifted_aaa, abs=1e-12)
    assert aaa.to_weight == pytest.approx(0.5, abs=1e-12)
    assert result.turnover[1] == pytest.approx(abs(0.5 - drifted_aaa) + abs(0.5 - (1.0 - drifted_aaa)), abs=1e-12)


def test_full_investment_buy_and_hold_reconciles_to_price_ratio_without_cost() -> None:
    prices = [(100.0, 100.0), (101.0, 103.0), (104.0, 98.0), (97.0, 110.0), (111.0, 105.0)]
    bars = bars_from_prices({"SPY": prices})
    result = simulate(bars, _dates(bars), {0: {"SPY": 1.0}}, 0.0)
    assert result.equity[-1] == pytest.approx(prices[-1][1] / prices[1][0], abs=1e-12)


def test_entry_cost_is_the_only_cost_for_buy_and_hold() -> None:
    prices = [(100.0, 100.0), (101.0, 103.0), (104.0, 98.0), (97.0, 110.0)]
    bars = bars_from_prices({"SPY": prices})
    cost = cost_rate_from_bps(10.0, 5.0)
    result = simulate(bars, _dates(bars), {0: {"SPY": 1.0}}, cost)
    assert result.equity[-1] == pytest.approx((1.0 - cost) * prices[-1][1] / prices[1][0], abs=1e-12)
    assert len(result.trades) == 1


def test_weights_never_exceed_full_investment_and_cash_is_non_negative() -> None:
    bars = bars_from_prices({"SPY": [(100.0 + day, 101.0 + day) for day in range(40)]})
    decisions = {t: {"SPY": 1.0} for t in range(39)}
    result = simulate(bars, _dates(bars), decisions, cost_rate_from_bps(10.0, 5.0))
    for weights in result.weights:
        assert sum(weights.values()) <= 1.0 + 1e-12
        assert all(value >= -1e-12 for value in weights.values())


def test_targets_above_full_investment_are_rejected() -> None:
    bars = bars_from_prices({"AAA": [(10.0, 10.0)] * 3, "BBB": [(10.0, 10.0)] * 3})
    with pytest.raises(ResearchError, match="sums above 1"):
        simulate(bars, _dates(bars), {0: {"AAA": 0.7, "BBB": 0.6}}, 0.0)


def test_decision_on_final_day_is_rejected() -> None:
    bars = bars_from_prices({"SPY": [(10.0, 10.0)] * 3})
    with pytest.raises(ResearchError, match="no execution day"):
        simulate(bars, _dates(bars), {2: {"SPY": 1.0}}, 0.0)


def test_window_uses_realization_dates_and_excludes_day_zero() -> None:
    bars = bars_from_prices({"SPY": [(10.0, 10.0), (10.0, 11.0), (11.0, 12.0), (12.0, 13.0)]})
    dates = _dates(bars)
    result = simulate(bars, dates, {}, 0.0)
    window_dates, window_returns = result.window(0, 4)
    assert window_dates == tuple(dates[1:4])
    assert len(window_returns) == 3
    assert result.window(2, 3)[0] == (dates[2],)


def test_restrict_decisions_maps_execution_days_into_range() -> None:
    decisions = {0: {"A": 1.0}, 1: {"A": 0.5}, 2: {}, 3: {"A": 1.0}}
    assert restrict_decisions(decisions, 2, 4) == {1: {"A": 0.5}, 2: {}}


def test_combined_hand_day_overnight_gap_rebalance_and_cost_match_formula() -> None:
    # Two assets, 50/50 entered at day-1 open. Day 2 has an overnight gap on both assets
    # and no trade. Day 3 rebalances to 20/80 at the open, with a non-zero cost.
    cost = 0.002
    bars = bars_from_prices(
        {
            "AAA": [(100.0, 100.0), (100.0, 110.0), (112.0, 113.0), (113.0, 114.0)],
            "BBB": [(100.0, 100.0), (100.0, 100.0), (98.0, 99.0), (99.0, 97.0)],
        }
    )
    dates = _dates(bars)
    result = simulate(bars, dates, {0: {"AAA": 0.5, "BBB": 0.5}, 2: {"AAA": 0.2, "BBB": 0.8}}, cost)

    # Day 1 (entry from cash at open): T = 1, intraday = 0.5 * 0.10.
    h1_aaa = 0.5 * (110.0 / 100.0) / 1.05
    h1_bbb = 0.5 * (100.0 / 100.0) / 1.05
    assert h1_aaa + h1_bbb == pytest.approx(1.0, abs=1e-12)
    assert result.returns[0] == pytest.approx((1.0 - cost) * 1.05 - 1.0, abs=1e-12)

    # Day 2 (hold): overnight gaps to 112 / 98 from closes 110 / 100, then intraday.
    g_aaa = h1_aaa * 112.0 / 110.0
    g_bbb = h1_bbb * 98.0 / 100.0
    nav_open_2 = g_aaa + g_bbb
    w_aaa, w_bbb = g_aaa / nav_open_2, g_bbb / nav_open_2
    id_2 = w_aaa * (113.0 / 112.0 - 1.0) + w_bbb * (99.0 / 98.0 - 1.0)
    assert result.returns[1] == pytest.approx(nav_open_2 * (1.0 + id_2) - 1.0, abs=1e-12)
    h2_aaa = w_aaa * (113.0 / 112.0) / (1.0 + id_2)
    h2_bbb = w_bbb * (99.0 / 98.0) / (1.0 + id_2)

    # Day 3: open equals prior close, so pre-trade weights are h2. Turnover is measured there.
    turnover = abs(0.2 - h2_aaa) + abs(0.8 - h2_bbb)
    assert result.turnover[2] == pytest.approx(turnover, abs=1e-12)
    id_3 = 0.2 * (114.0 / 113.0 - 1.0) + 0.8 * (97.0 / 99.0 - 1.0)
    multiplicative = (1.0 - cost * turnover) * (1.0 + id_3) - 1.0
    additive = id_3 - cost * turnover
    assert result.returns[2] == pytest.approx(multiplicative, abs=1e-12)
    # The implemented convention differs from the additive spec form by -cost * turnover * intraday.
    assert multiplicative - additive == pytest.approx(-cost * turnover * id_3, abs=1e-12)
    assert abs(multiplicative - additive) > 1e-8
