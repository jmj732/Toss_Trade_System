from __future__ import annotations

import random
from datetime import date

import pytest
from research_fixtures import weekdays

from app.research import leadlag as L
from app.research import leadlag_audit as A
from app.research.backtest import simulate
from app.research.data import Bar


def _synthetic(seed: int, n: int, symbols: tuple[str, ...], vol: float = 0.015):
    rng = random.Random(seed)
    dates = weekdays(date(2020, 1, 6), n)
    opens: dict[str, list[float]] = {}
    closes: dict[str, list[float]] = {}
    for s in symbols:
        price = 100.0
        o_list: list[float] = []
        c_list: list[float] = []
        for _ in range(n):
            o = price * (1.0 + rng.gauss(0.0, 0.002))
            c = o * (1.0 + rng.gauss(0.0, vol))
            o_list.append(o)
            c_list.append(c)
            price = c
        opens[s] = o_list
        closes[s] = c_list
    bars = {
        s: [Bar(d, opens[s][i], max(opens[s][i], closes[s][i]), min(opens[s][i], closes[s][i]),
                closes[s][i], 1_000_000.0) for i, d in enumerate(dates)]
        for s in symbols
    }
    return dates, opens, closes, bars


def test_independent_book_matches_simulate_on_random_weights() -> None:
    symbols = ("AAA", "BBB", "CCC")
    n = 120
    dates, opens, closes, bars = _synthetic(7, n, symbols)
    rng = random.Random(11)
    decisions: dict[int, dict[str, float]] = {}
    for t in range(n - 1):
        if rng.random() < 0.6:
            raw = [rng.random() for _ in symbols]
            scale = rng.random() / sum(raw)
            decisions[t] = {s: w * scale for s, w in zip(symbols, raw)}
    rate = 0.0015
    reference = simulate(bars, dates, decisions, rate).returns
    book = A.run_book(list(symbols), opens, closes, 0, n - 1, decisions, rate)
    assert len(book.returns) == len(reference) == n - 1
    assert max(abs(a - b) for a, b in zip(book.returns, reference)) <= 1e-9
    assert book.attribution_residual <= 1e-9
    assert book.cash_min >= -1e-9


def test_leak_sentinel_detects_planted_leak() -> None:
    symbols = ("AAA", "BBB", "CCC")
    n = 150
    dates, opens, closes, bars = _synthetic(3, n, symbols, vol=0.02)
    universe = list(symbols)
    realized = {
        (s, t): opens[s][t + 2] / opens[s][t + 1] - 1.0
        for s in symbols for t in range(n - 2)
    }
    # Planted leak: the forecast stored at t+1 is the return the position earns from open t+1 (known only later).
    leaked = {(s, t + 1): L.Forecast(realized[(s, t)], realized[(s, t)], False)
              for (s, t) in realized if t + 1 <= n - 3}
    days = list(range(0, n - 3))
    leak_dec = L.decisions_for(leaked, "M0", 1, universe, [t + 1 for t in days], 0)
    leak_dec = {t - 1: v for t, v in leak_dec.items() if t - 1 >= 0}
    rng = random.Random(5)
    honest_dec = {t: {rng.choice(universe): 1.0} for t in days}
    leak = A.run_book(universe, opens, closes, 0, n - 2, leak_dec, 0.0)
    honest = A.run_book(universe, opens, closes, 0, n - 2, honest_dec, 0.0)
    leak_cagr = A._cagr(leak.returns)
    honest_cagr = A._cagr(honest.returns)
    assert leak_cagr is not None and honest_cagr is not None
    assert leak_cagr > 1.0
    assert leak_cagr > 5.0 * max(honest_cagr, 0.01)


def test_truncation_flags_leaky_forecaster_and_passes_honest_one() -> None:
    symbols = ("AAA", "BBB")
    n = 40
    dates, opens, closes, bars = _synthetic(9, n, symbols)
    panel = L.Panel(
        tuple(dates),
        {s: list(opens[s]) for s in symbols},
        {s: list(closes[s]) for s in symbols},
        {s: [c * 1e6 for c in closes[s]] for s in symbols},
        {s: {d: bars[s][i] for i, d in enumerate(dates)} for s in symbols},
        {},
    )
    t = 20

    def honest(p, fold, universe, day):
        return {(s, day): (p.close[s][day] / p.close[s][day - 1] - 1.0, None) for s in universe}

    def leaky(p, fold, universe, day):
        out = {}
        for s in universe:
            nxt = p.close[s][day + 1]  # the future bar: missing once the panel is truncated at day
            out[(s, day)] = (None if nxt is None else nxt / p.close[s][day] - 1.0, None)
        return out

    pairs, bad_honest = A.truncation_check(panel, None, list(symbols), t, honest)
    assert pairs == len(symbols)
    assert bad_honest == []
    pairs, bad_leaky = A.truncation_check(panel, None, list(symbols), t, leaky)
    assert pairs == len(symbols)
    assert bad_leaky == sorted(symbols)


def test_dividend_identity_passes_correct_factor_and_catches_wrong_one() -> None:
    calendar = [date(2021, 3, 1), date(2021, 3, 2), date(2021, 3, 3), date(2021, 3, 4), date(2021, 3, 5)]
    split_close = [100.0, 101.0, 102.0, 103.0, 104.0]
    amount = 1.0
    ex = calendar[2]
    # Correct: y = D / C(ex-1) = 1/101 ; factor(e-1) = (1-y), factor(e) = 1.
    y = amount / split_close[1]
    correct_factor = [1 - y, 1 - y, 1.0, 1.0, 1.0]
    tr_ok = [c * f for c, f in zip(split_close, correct_factor)]
    result_ok = A.dividend_identity_errors(calendar, split_close, tr_ok, [(ex, amount)])
    assert result_ok["checked"] == 1
    assert result_ok["failed"] == []
    assert result_ok["unexplained_factor_changes"] == 0

    # Wrong: uses C(ex) instead of C(ex-1) as the denominator.
    y_wrong = amount / split_close[2]
    wrong_factor = [1 - y_wrong, 1 - y_wrong, 1.0, 1.0, 1.0]
    tr_bad = [c * f for c, f in zip(split_close, wrong_factor)]
    result_bad = A.dividend_identity_errors(calendar, split_close, tr_bad, [(ex, amount)])
    assert result_bad["checked"] == 1
    assert len(result_bad["failed"]) == 1
    assert abs(result_bad["failed"][0]["actual"] - result_bad["failed"][0]["expected"]) > 1e-8


def test_ols_recovers_known_coefficients_and_singular_design_is_none() -> None:
    rng = random.Random(2)
    beta = [0.5, -1.25, 2.0]
    rows = [[1.0, rng.gauss(0, 1), rng.gauss(0, 1)] for _ in range(200)]
    target = [sum(b * x for b, x in zip(beta, row)) for row in rows]
    solved = A.normal_equation_ols(rows, target)
    assert solved is not None
    assert max(abs(a - b) for a, b in zip(solved, beta)) <= 1e-9
    singular = [[1.0, 2.0, 2.0] for _ in range(10)]
    assert A.normal_equation_ols(singular, [1.0] * 10) is None


def test_read_csv_table_rejects_duplicate_dates(tmp_path) -> None:
    path = tmp_path / "X.csv"
    path.write_text("date,open,high,low,close,volume\n2020-01-02,1,1,1,1,1\n2020-01-02,2,2,2,2,2\n",
                    encoding="utf-8")
    with pytest.raises(ValueError):
        A.read_csv_table(path)
