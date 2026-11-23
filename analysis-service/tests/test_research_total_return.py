from __future__ import annotations

import csv
import hashlib
import json
from datetime import date
from pathlib import Path

import pytest
from research_fixtures import COLLECTED_AT, synthetic_rows, weekdays

from app.research.data import Bar, FLOAT_DECIMALS, load_dataset
from app.research.total_return import (
    CHECK_FAILED,
    CHECK_PASSED,
    DIVIDEND_SOURCE,
    DividendEvent,
    TotalReturnError,
    adjusted_rows,
    back_adjust_factors,
    build_total_return_dataset,
    main,
    verify_symbol,
)

START = date(2020, 1, 1)
STOP_OK = ("NO_CURSOR", "NO_CURSOR")


def flat_bars(count: int, price: float = 100.0, start: date = START) -> list[Bar]:
    return [Bar(day, price, price, price, price, 1_000_000.0) for day in weekdays(start, count)]


def with_closes(bars: list[Bar], closes: list[float]) -> list[Bar]:
    return [Bar(b.date, c, c, c, c, b.volume) for b, c in zip(bars, closes)]


def event(bars: list[Bar], index: int, amount: float) -> DividendEvent:
    return DividendEvent(bars[index].date, amount)


# ---------- back-adjust math ----------


def test_back_adjust_factors_cumulate_only_later_ex_dates() -> None:
    applied = [(2, 0.01), (4, 0.02)]
    factors = back_adjust_factors(6, applied)
    assert factors[0] == pytest.approx(0.99 * 0.98)
    assert factors[1] == pytest.approx(0.99 * 0.98)
    assert factors[2] == pytest.approx(0.98)  # ex-date bar itself is not adjusted by its own dividend
    assert factors[3] == pytest.approx(0.98)
    assert factors[4] == 1.0
    assert factors[5] == 1.0


def test_adjusted_rows_scale_ohlc_keep_volume_and_round_to_data_precision() -> None:
    bars = [Bar(date(2020, 1, 2), 48.2188, 49.0, 48.0, 48.5, 390700.0),
            Bar(date(2020, 1, 3), 50.0, 50.0, 50.0, 50.0, 1000.0)]
    rows = adjusted_rows(bars, [(1, 0.5)])
    assert rows[0] == ["2020-01-02", "24.1094", "24.5", "24", "24.25", "390700"]
    assert rows[1] == ["2020-01-03", "50", "50", "50", "50", "1000"]
    assert FLOAT_DECIMALS == 10


def test_adjusted_rows_are_unchanged_without_dividends() -> None:
    bars = flat_bars(3, price=12.3456)
    rows = adjusted_rows(bars, [])
    assert [row[4] for row in rows] == ["12.3456", "12.3456", "12.3456"]


# ---------- per-symbol verification ----------


def test_valid_dividend_passes_and_is_applied_at_the_prior_close() -> None:
    bars = flat_bars(300)
    verdict = verify_symbol("AAA", bars, bars, [event(bars, 100, 1.0)], stop_reasons=STOP_OK, provenance=None)
    assert verdict.status == CHECK_PASSED
    assert verdict.applied == [(100, pytest.approx(0.01))]
    assert verdict.checks["dividends"]["in_window"] == 1


def test_yield_above_limit_fails() -> None:
    bars = flat_bars(300)
    verdict = verify_symbol("AAA", bars, bars, [event(bars, 100, 20.0)], stop_reasons=STOP_OK, provenance=None)
    assert verdict.status == CHECK_FAILED
    assert any(f.startswith("yield_out_of_range") for f in verdict.failures)


def test_non_positive_dividend_fails() -> None:
    bars = flat_bars(300)
    verdict = verify_symbol("AAA", bars, bars, [event(bars, 100, 0.0)], stop_reasons=STOP_OK, provenance=None)
    assert verdict.status == CHECK_FAILED


def test_ex_date_inside_window_that_is_not_a_trading_day_fails() -> None:
    bars = flat_bars(300)
    saturday = date(2020, 1, 4)  # inside the window, not a bar
    assert saturday not in {b.date for b in bars}
    verdict = verify_symbol("AAA", bars, bars, [DividendEvent(saturday, 0.5)],
                            stop_reasons=STOP_OK, provenance=None)
    assert verdict.status == CHECK_FAILED
    assert any(f.startswith("ex_date_not_trading_day") for f in verdict.failures)


def test_ex_date_outside_window_is_recorded_and_not_used() -> None:
    bars = flat_bars(300)
    before = DividendEvent(date(1990, 1, 2), 0.3)
    after = DividendEvent(date(2030, 1, 2), 0.3)
    verdict = verify_symbol("AAA", bars, bars, [before, after], stop_reasons=STOP_OK, provenance=None)
    assert verdict.status == CHECK_PASSED
    assert verdict.applied == []
    assert verdict.checks["dividends"]["out_of_window"] == 2


def test_ex_date_on_first_bar_has_no_prior_close_and_fails() -> None:
    bars = flat_bars(300)
    verdict = verify_symbol("AAA", bars, bars, [event(bars, 0, 0.5)], stop_reasons=STOP_OK, provenance=None)
    assert verdict.status == CHECK_FAILED
    assert any(f.startswith("ex_date_without_prior_close") for f in verdict.failures)


def test_split_with_inconsistent_dividend_yields_fails() -> None:
    raw = flat_bars(700)
    adjusted_closes = [100.0] * 350 + [50.0] * 350
    adjusted = with_closes(raw, adjusted_closes)
    # Raw prices are 100 before the 2:1 split at index 350 and 50 after it; adjusted/raw ratio is 0.5 before.
    raw_bars = with_closes(raw, [200.0] * 350 + [50.0] * 350)
    # Trailing yields are 0.5% per quarterly event; forward events are a quarter of that, ratio near 4.
    events = [event(adjusted, i, 0.5) for i in range(20, 340, 63)]
    events += [event(adjusted, i, 0.0625) for i in range(360, 690, 63)]
    verdict = verify_symbol("AAA", adjusted, raw_bars, events, stop_reasons=STOP_OK, provenance=None)
    assert verdict.status == CHECK_FAILED
    assert any(f.startswith("split_dividend_mismatch") for f in verdict.failures)


def test_split_with_consistent_dividend_yields_passes() -> None:
    raw = flat_bars(700)
    adjusted = with_closes(raw, [100.0] * 350 + [50.0] * 350)
    raw_bars = with_closes(raw, [200.0] * 350 + [50.0] * 350)
    events = [event(adjusted, i, 0.5) for i in range(20, 340, 63)]
    events += [event(adjusted, i, 0.25) for i in range(360, 690, 63)]
    verdict = verify_symbol("AAA", adjusted, raw_bars, events, stop_reasons=STOP_OK, provenance=None)
    assert verdict.status == CHECK_PASSED
    assert verdict.checks["splits"][0]["check"] == CHECK_PASSED


def test_split_check_is_not_applicable_without_dividends_on_one_side() -> None:
    raw = flat_bars(700)
    adjusted = with_closes(raw, [100.0] * 350 + [50.0] * 350)
    raw_bars = with_closes(raw, [200.0] * 350 + [50.0] * 350)
    events = [event(adjusted, i, 0.5) for i in range(20, 340, 63)]
    verdict = verify_symbol("AAA", adjusted, raw_bars, events, stop_reasons=STOP_OK, provenance=None)
    assert verdict.status == CHECK_PASSED
    assert verdict.checks["splits"][0]["check"] == "NOT_APPLICABLE"


def test_symbol_without_any_dividends_passes_with_flag() -> None:
    bars = flat_bars(300)
    verdict = verify_symbol("AMZN", bars, bars, [], stop_reasons=STOP_OK, provenance=None)
    assert verdict.status == CHECK_PASSED
    assert verdict.checks["dividend_status"] == "NO_DIVIDENDS_REPORTED"


def test_toss_suspect_stop_reason_excludes_symbol() -> None:
    bars = flat_bars(300)
    verdict = verify_symbol("AAA", bars, bars, [], stop_reasons=("MAX_PAGES", "NO_CURSOR"), provenance=None)
    assert verdict.status == CHECK_FAILED
    assert any(f.startswith("toss_suspect_stop_adjusted") for f in verdict.failures)


def test_toss_ohlc_ratio_mismatch_excludes_symbol() -> None:
    raw = flat_bars(300)
    # Only the last adjusted bar's close moves, so its open/close ratios to raw disagree.
    adjusted = raw[:-1] + [Bar(raw[-1].date, 100.0, 120.0, 100.0, 120.0, raw[-1].volume)]
    verdict = verify_symbol("AAA", adjusted, raw, [], stop_reasons=STOP_OK, provenance=None)
    assert verdict.status == CHECK_FAILED
    assert "toss_ohlc_ratio_mismatch" in verdict.failures


# ---------- file-level pipeline ----------


def _write_csv(path: Path, bars: list[Bar]) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(["date", "open", "high", "low", "close", "volume"])
        for bar in bars:
            writer.writerow([bar.date.isoformat(), bar.open, bar.high, bar.low, bar.close, int(bar.volume)])
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_toss_dir(root: Path, series: dict[str, tuple[list[Bar], list[Bar]]]) -> Path:
    symbols: dict[str, dict] = {}
    unadjusted: dict[str, dict] = {}
    for symbol, (adjusted, raw) in series.items():
        digest = _write_csv(root / f"{symbol}.csv", adjusted)
        raw_digest = _write_csv(root / "unadjusted" / f"{symbol}.csv", raw)
        pages = [{"before": None, "count": 200, "observed_at": COLLECTED_AT}]
        symbols[symbol] = {"file": f"{symbol}.csv", "sha256": digest, "rows": len(adjusted),
                           "first_date": adjusted[0].date.isoformat(), "last_date": adjusted[-1].date.isoformat(),
                           "pages": pages, "stop_reason": "NO_CURSOR", "retries": 0, "retry_reasons": {}}
        unadjusted[symbol] = {"file": f"unadjusted/{symbol}.csv", "sha256": raw_digest, "rows": len(raw),
                              "first_date": raw[0].date.isoformat(), "last_date": raw[-1].date.isoformat(),
                              "pages": pages, "stop_reason": "NO_CURSOR", "retries": 0, "retry_reasons": {}}
    manifest = {"provider": "TOSS", "endpoint": "/api/v1/candles", "interval": "1d", "adjusted": True,
                "adjustment_basis": "UNVERIFIED", "collected_at": COLLECTED_AT, "resumed_at": [],
                "date_basis": "America/New_York date of candle timestamp", "symbols": symbols,
                "unadjusted": unadjusted}
    (root / "manifest.json").write_text(json.dumps(manifest, sort_keys=True, indent=2), encoding="utf-8")
    return root


def write_dividends(root: Path, symbol: str, events: list[dict], *, raw_override: bytes | None = None,
                    omit_raw: bool = False) -> None:
    root.mkdir(parents=True, exist_ok=True)
    raw_body = raw_override if raw_override is not None else json.dumps({"chart": {"result": [{"events": {
        "dividends": {str(i): {"amount": e["amount"], "date": i} for i, e in enumerate(events)}}}]}}).encode()
    raw_path = root / "raw" / f"{symbol}.json"
    raw_path.parent.mkdir(parents=True, exist_ok=True)
    if not omit_raw:
        raw_path.write_bytes(raw_body)
    document = {"symbol": symbol, "source_url": f"https://query1.finance.yahoo.com/v8/finance/chart/{symbol}",
                "fetched_at": "2026-01-02T00:00:00Z", "timezone": "America/New_York",
                "raw_file": f"raw/{symbol}.json", "raw_sha256": hashlib.sha256(raw_body).hexdigest(),
                "events": events}
    (root / f"{symbol}.json").write_text(json.dumps(document), encoding="utf-8")


def _events_for(bars: list[Bar], indices_and_yields: list[tuple[int, float]]) -> list[dict]:
    return [{"ex_date": bars[i].date.isoformat(), "amount": round(bars[i - 1].close * y, 10)}
            for i, y in indices_and_yields]


def test_pipeline_excludes_failed_symbols_and_builds_loadable_split_and_dividend_dataset(tmp_path: Path) -> None:
    good_rows = synthetic_rows(11, 420, start=date(2015, 1, 1))
    good = [Bar(d, o, h, lo, c, float(v)) for d, o, h, lo, c, v in good_rows]
    bad = [Bar(b.date, b.open, b.high, b.low, b.close, b.volume) for b in good]
    nodiv = [Bar(b.date, b.open, b.high, b.low, b.close, b.volume) for b in good]
    missing = [Bar(b.date, b.open, b.high, b.low, b.close, b.volume) for b in good]
    toss = write_toss_dir(tmp_path / "toss", {"GOOD": (good, good), "BAD": (bad, bad),
                                              "NODIV": (nodiv, nodiv), "MISSING": (missing, missing)})
    dividends = tmp_path / "div"
    write_dividends(dividends, "GOOD", _events_for(good, [(100, 0.01), (250, 0.012)]))
    write_dividends(dividends, "BAD", _events_for(bad, [(100, 0.3)]))
    write_dividends(dividends, "NODIV", [])
    out = tmp_path / "derived"

    summary = build_total_return_dataset(toss, dividends, out)

    assert summary["included"] == ["GOOD", "NODIV"]
    assert set(summary["excluded"]) == {"BAD", "MISSING"}
    assert any(r.startswith("yield_out_of_range") for r in summary["excluded"]["BAD"])
    assert any("dividend" in r for r in summary["excluded"]["MISSING"])

    derived = load_dataset(out)
    assert derived.provenance["adjustment_basis"] == "SPLIT_AND_DIVIDEND"
    assert derived.symbols == ("GOOD", "NODIV")
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["total_return"]["all_source_symbols_verified"] is False
    assert manifest["total_return"]["per_symbol"]["NODIV"]["status"] == CHECK_PASSED
    assert manifest["symbols"]["GOOD"]["verification"]["dividend_provenance"]["source"] == DIVIDEND_SOURCE
    assert set(manifest["unadjusted"]) == {"GOOD", "NODIV"}

    adjusted_good = {b.date: b for b in derived.bars["GOOD"]}
    source_by_date = {b.date: b for b in good}
    before = good[99].date
    # Both events (yields 0.01 and 0.012 by construction) lie after this bar, so both factors apply.
    assert adjusted_good[before].close == pytest.approx(source_by_date[before].close * 0.99 * (1 - 0.012), rel=1e-6)


def test_pipeline_rejects_raw_dividend_response_that_does_not_match_its_hash(tmp_path: Path) -> None:
    rows = synthetic_rows(12, 300, start=date(2015, 1, 1))
    bars = [Bar(d, o, h, lo, c, float(v)) for d, o, h, lo, c, v in rows]
    toss = write_toss_dir(tmp_path / "toss", {"AAA": (bars, bars), "BBB": (bars, bars)})
    dividends = tmp_path / "div"
    write_dividends(dividends, "AAA", [])
    write_dividends(dividends, "BBB", [])
    (dividends / "raw" / "BBB.json").write_bytes(b"tampered")
    summary = build_total_return_dataset(toss, dividends, tmp_path / "derived")
    assert summary["included"] == ["AAA"]
    assert "BBB" in summary["excluded"]


def test_pipeline_requires_a_non_empty_result(tmp_path: Path) -> None:
    rows = synthetic_rows(13, 300, start=date(2015, 1, 1))
    bars = [Bar(d, o, h, lo, c, float(v)) for d, o, h, lo, c, v in rows]
    toss = write_toss_dir(tmp_path / "toss", {"AAA": (bars, bars)})
    dividends = tmp_path / "div"
    write_dividends(dividends, "AAA", _events_for(bars, [(100, 0.3)]))
    with pytest.raises(TotalReturnError):
        build_total_return_dataset(toss, dividends, tmp_path / "derived")


def test_output_directory_must_not_be_the_source(tmp_path: Path) -> None:
    rows = synthetic_rows(14, 300, start=date(2015, 1, 1))
    bars = [Bar(d, o, h, lo, c, float(v)) for d, o, h, lo, c, v in rows]
    toss = write_toss_dir(tmp_path / "toss", {"AAA": (bars, bars)})
    with pytest.raises(TotalReturnError):
        build_total_return_dataset(toss, tmp_path / "div", toss)


def test_cli_reports_inclusion_and_exclusion(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    rows = synthetic_rows(15, 300, start=date(2015, 1, 1))
    bars = [Bar(d, o, h, lo, c, float(v)) for d, o, h, lo, c, v in rows]
    toss = write_toss_dir(tmp_path / "toss", {"AAA": (bars, bars), "BBB": (bars, bars)})
    dividends = tmp_path / "div"
    write_dividends(dividends, "AAA", [])
    write_dividends(dividends, "BBB", _events_for(bars, [(100, 0.3)]))
    code = main(["--toss", str(toss), "--dividends", str(dividends), "--out", str(tmp_path / "derived")])
    text = capsys.readouterr().out
    assert code == 0
    assert "included (1): AAA" in text
    assert "excluded BBB" in text
