"""Pre-registered total-return verification and derived dataset (LL-1 section 3.2).

Input: a Toss collection directory (manifest.json, <SYMBOL>.csv split-adjusted, unadjusted/<SYMBOL>.csv)
and a dividends directory written by ``scripts/fetch-yahoo-dividends.py``.

Per symbol, in order (any failure excludes the symbol and records why):

1. Toss integrity: both series have the same dates after the collection cutoff, OHLC ratios of
   adjusted/unadjusted agree, and neither series stopped on a suspect reason (NO_PROGRESS, MAX_PAGES).
2. Dividend provenance: the dividends JSON exists, its raw response exists and matches raw_sha256.
3. Ex-date mapping: an ex-date inside the Toss window [first_date, last_date] must be a trading date
   with a prior bar. Ex-dates outside the window are recorded as out_of_window and not used, because
   they cannot touch any bar that is present.
4. Yield: D / C_adj(ex-1) lies in (0, 0.15] for every in-window event.
5. Split consistency: split dates are days where the adjusted/unadjusted close ratio changes by more
   than 1%. Around each split, the dividend-yield sum over the 252 bars before and the 252 bars after
   (truncated at the series ends) must have ratio in [0.5, 2] when both sides have dividends.
   If either side is zero the check is recorded as not applicable.

A symbol with no dividends at all passes if integrity holds and is recorded as NO_DIVIDENDS_REPORTED.
A passing symbol is back-adjusted: each OHLC bar dated before an ex-date is multiplied by
(1 - D / C_adj(ex-1)); the factors of all later ex-dates are cumulated. Volume is unchanged.
Values are rounded to data.FLOAT_DECIMALS.

The derived dataset contains only passing symbols, is labelled SPLIT_AND_DIVIDEND, and loads through
data.load_dataset. Excluded symbols and their check results are recorded in the manifest.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any

from app.research.data import (
    CSV_HEADER,
    FLOAT_DECIMALS,
    REQUIRED_INTERVAL,
    Bar,
    DataIntegrityError,
    ResearchError,
    _collection_cutoff,
    _load_symbol,
    _split_incomplete,
    load_dataset,
    sha256_hex,
)

TOTAL_RETURN_RULE_VERSION = "ll1-total-return-v1"
TOTAL_RETURN_BASIS = "SPLIT_AND_DIVIDEND"
DIVIDEND_SOURCE = "yahoo-finance-chart-events-div"
SUSPECT_STOP_REASONS = frozenset({"NO_PROGRESS", "MAX_PAGES"})
MAX_PAGES_STOP = "MAX_PAGES"
# Pre-registered amendment (claudedocs/leadlag-preregistration.md, 부록 A): a MAX_PAGES stop truncates only
# the oldest history. It is accepted when both series reach back to this date or earlier. The default is the
# first trading day of the sector SPDR ETFs (XLE..XLY first bar 1998-12-22 in the collection), and the
# walk-forward folds start after it, so older bars are never used by the analysis.
DEFAULT_ANALYSIS_START = date(1998, 12, 22)
TRUNCATED_BEFORE_ANALYSIS_START = "TRUNCATED_BEFORE_ANALYSIS_START"
TRUNCATED_AFTER_ANALYSIS_START = "TRUNCATED_AFTER_ANALYSIS_START"
NOT_TRUNCATED = "NOT_TRUNCATED"
OHLC_RATIO_TOLERANCE = 1e-4
RATIO_CHANGE_EPSILON = 1e-6
SPLIT_STEP_THRESHOLD = 0.01
YIELD_MAX = 0.15
SPLIT_WINDOW_BARS = 252
SPLIT_RATIO_MIN = 0.5
SPLIT_RATIO_MAX = 2.0
NO_DIVIDENDS = "NO_DIVIDENDS_REPORTED"
CHECK_PASSED = "PASSED"
CHECK_FAILED = "FAILED"
RAW_DIRECTORY = "raw"


class TotalReturnError(ResearchError):
    """Derived dataset cannot be built from the given inputs."""


@dataclass(frozen=True)
class DividendEvent:
    ex_date: date
    amount: float


@dataclass
class SymbolVerdict:
    symbol: str
    status: str = CHECK_PASSED
    failures: list[str] = field(default_factory=list)
    checks: dict[str, Any] = field(default_factory=dict)
    dividend_provenance: dict[str, Any] = field(default_factory=dict)
    # Set only for passing symbols: (trading index, yield) per in-window event.
    applied: list[tuple[int, float]] = field(default_factory=list)

    def fail(self, reason: str) -> None:
        self.status = CHECK_FAILED
        self.failures.append(reason)

    def record(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "failures": list(self.failures),
            "checks": self.checks,
            "dividend_provenance": self.dividend_provenance,
        }


def _sha256_file(path: Path) -> str:
    return sha256_hex(path.read_bytes())


def _parse_ex_date(text: Any, where: str) -> date:
    if not isinstance(text, str):
        raise TotalReturnError(f"{where}: ex_date must be an ISO date string")
    try:
        return date.fromisoformat(text)
    except ValueError as error:
        raise TotalReturnError(f"{where}: ex_date is not an ISO date ({error})") from error


def load_dividend_file(directory: Path, symbol: str) -> tuple[list[DividendEvent], dict[str, Any]]:
    """Events and provenance for one symbol. Raises TotalReturnError when the file is unusable."""
    path = directory / f"{symbol}.json"
    if not path.is_file():
        raise TotalReturnError(f"dividend file missing: {symbol}.json")
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise TotalReturnError(f"{symbol}.json is not valid JSON ({error})") from error
    if not isinstance(document, dict) or document.get("symbol") != symbol:
        raise TotalReturnError(f"{symbol}.json does not name its symbol")
    raw_name = document.get("raw_file")
    raw_sha = document.get("raw_sha256")
    if raw_name != f"{RAW_DIRECTORY}/{symbol}.json":
        raise TotalReturnError(f"{symbol}.json raw_file must be {RAW_DIRECTORY}/{symbol}.json")
    raw_path = directory / raw_name
    if not raw_path.is_file() or not isinstance(raw_sha, str):
        raise TotalReturnError(f"{symbol}: raw dividend response missing or unhashed")
    if _sha256_file(raw_path) != raw_sha.lower():
        raise TotalReturnError(f"{symbol}: raw dividend response does not match raw_sha256")
    raw_events = document.get("events")
    if not isinstance(raw_events, list):
        raise TotalReturnError(f"{symbol}.json events must be a list")
    events: list[DividendEvent] = []
    for index, item in enumerate(raw_events):
        if not isinstance(item, dict):
            raise TotalReturnError(f"{symbol}: event {index} is not an object")
        amount = item.get("amount")
        if not isinstance(amount, (int, float)) or isinstance(amount, bool) or not math.isfinite(amount):
            raise TotalReturnError(f"{symbol}: event {index} amount is not a finite number")
        events.append(DividendEvent(_parse_ex_date(item.get("ex_date"), f"{symbol} event {index}"), float(amount)))
    provenance = {
        "source": DIVIDEND_SOURCE,
        "source_url": document.get("source_url"),
        "fetched_at": document.get("fetched_at"),
        "dividends_file": path.name,
        "dividends_file_sha256": _sha256_file(path),
        "raw_file": raw_name,
        "raw_sha256": raw_sha.lower(),
        "event_count": len(events),
    }
    return events, provenance


def _ratio_steps(adjusted: Sequence[Bar], unadjusted: Sequence[Bar]) -> list[tuple[int, float]]:
    """(index, step) for each trading index where adjusted/unadjusted close ratio changes."""
    steps: list[tuple[int, float]] = []
    previous: float | None = None
    for index, (adj, raw) in enumerate(zip(adjusted, unadjusted)):
        ratio = adj.close / raw.close
        if previous is not None and abs(ratio / previous - 1.0) > RATIO_CHANGE_EPSILON:
            steps.append((index, ratio / previous - 1.0))
        previous = ratio
    return steps


def _history_truncation(
    adjusted: Sequence[Bar],
    unadjusted: Sequence[Bar],
    stop_reasons: tuple[str | None, str | None],
    analysis_start: date,
) -> tuple[bool, dict[str, Any]]:
    """Whether MAX_PAGES truncation is acceptable: both series' first bar is on or before analysis_start.

    NO_PROGRESS is never accepted here. Returns (accepted, record). The record always holds both first dates.
    """
    first = {
        "adjusted": adjusted[0].date.isoformat() if adjusted else None,
        "unadjusted": unadjusted[0].date.isoformat() if unadjusted else None,
    }
    truncated = [label for label, reason in zip(("adjusted", "unadjusted"), stop_reasons)
                 if reason == MAX_PAGES_STOP]
    record: dict[str, Any] = {"analysis_start": analysis_start.isoformat(), "first_date": first,
                              "max_pages_series": truncated}
    if not truncated:
        record["result"] = NOT_TRUNCATED
        return False, record
    accepted = all(first[label] is not None and date.fromisoformat(first[label]) <= analysis_start
                   for label in ("adjusted", "unadjusted"))
    record["result"] = TRUNCATED_BEFORE_ANALYSIS_START if accepted else TRUNCATED_AFTER_ANALYSIS_START
    return accepted, record


def _integrity_failures(
    adjusted: Sequence[Bar],
    unadjusted: Sequence[Bar],
    stop_reasons: tuple[str | None, str | None],
    analysis_start: date,
) -> tuple[list[str], dict[str, Any]]:
    failures: list[str] = []
    truncation_accepted, truncation = _history_truncation(adjusted, unadjusted, stop_reasons, analysis_start)
    for label, reason in zip(("adjusted", "unadjusted"), stop_reasons):
        if reason == MAX_PAGES_STOP and truncation_accepted:
            continue
        if reason in SUSPECT_STOP_REASONS:
            failures.append(f"toss_suspect_stop_{label}:{reason}")
    adjusted_dates = {bar.date for bar in adjusted}
    unadjusted_dates = {bar.date for bar in unadjusted}
    if adjusted_dates != unadjusted_dates:
        failures.append("toss_unmatched_dates")
    raw_by_date = {bar.date: bar for bar in unadjusted}
    for bar in adjusted:
        raw = raw_by_date.get(bar.date)
        if raw is None:
            continue
        ratios = [a / r for a, r in ((bar.open, raw.open), (bar.high, raw.high),
                                     (bar.low, raw.low), (bar.close, raw.close))]
        if max(ratios) / min(ratios) - 1.0 > OHLC_RATIO_TOLERANCE:
            failures.append("toss_ohlc_ratio_mismatch")
            break
    return failures, truncation


def verify_symbol(
    symbol: str,
    adjusted: Sequence[Bar],
    unadjusted: Sequence[Bar],
    events: Sequence[DividendEvent],
    *,
    stop_reasons: tuple[str | None, str | None],
    provenance: Mapping[str, Any] | None,
    analysis_start: date = DEFAULT_ANALYSIS_START,
) -> SymbolVerdict:
    """Apply the pre-registered checks to one symbol. Pure: no I/O."""
    verdict = SymbolVerdict(symbol=symbol)
    if provenance is not None:
        verdict.dividend_provenance = dict(provenance)
    integrity_failures, truncation = _integrity_failures(adjusted, unadjusted, stop_reasons, analysis_start)
    verdict.checks["history_truncation"] = truncation
    for failure in integrity_failures:
        verdict.fail(failure)
    if verdict.failures or not adjusted:
        verdict.checks["integrity"] = CHECK_FAILED if verdict.failures else CHECK_PASSED
        return verdict
    verdict.checks["integrity"] = CHECK_PASSED

    index_by_date = {bar.date: index for index, bar in enumerate(adjusted)}
    first_date, last_date = adjusted[0].date, adjusted[-1].date
    seen: set[date] = set()
    in_window: list[tuple[int, float, date]] = []
    out_of_window = 0
    for event in sorted(events, key=lambda item: item.ex_date):
        if event.ex_date in seen:
            verdict.fail("duplicate_ex_date")
            continue
        seen.add(event.ex_date)
        if event.ex_date < first_date or event.ex_date > last_date:
            out_of_window += 1
            continue
        index = index_by_date.get(event.ex_date)
        if index is None:
            verdict.fail(f"ex_date_not_trading_day:{event.ex_date.isoformat()}")
            continue
        if index == 0:
            verdict.fail(f"ex_date_without_prior_close:{event.ex_date.isoformat()}")
            continue
        if not event.amount > 0:
            verdict.fail(f"non_positive_dividend:{event.ex_date.isoformat()}")
            continue
        yield_ = event.amount / adjusted[index - 1].close
        if not 0.0 < yield_ <= YIELD_MAX:
            verdict.fail(f"yield_out_of_range:{event.ex_date.isoformat()}")
            continue
        in_window.append((index, yield_, event.ex_date))
    yields = [item[1] for item in in_window]
    verdict.checks["dividends"] = {
        "events_total": len(events),
        "in_window": len(in_window),
        "out_of_window": out_of_window,
        "yield_min": min(yields) if yields else None,
        "yield_max": max(yields) if yields else None,
    }

    steps = _ratio_steps(adjusted, unadjusted)
    splits = [(index, step) for index, step in steps if abs(step) > SPLIT_STEP_THRESHOLD]
    ex_indices = {index for index, _, _ in in_window}
    verdict.checks["ex_date_ratio_changes_below_split_threshold"] = sum(
        1 for index, step in steps if abs(step) <= SPLIT_STEP_THRESHOLD and index in ex_indices
    )
    split_records: list[dict[str, Any]] = []
    n = len(adjusted)
    for split_index, step in splits:
        trailing_lo = max(0, split_index - SPLIT_WINDOW_BARS)
        forward_hi = min(n, split_index + SPLIT_WINDOW_BARS)
        trailing = sum(y for i, y, _ in in_window if trailing_lo <= i < split_index)
        forward = sum(y for i, y, _ in in_window if split_index <= i < forward_hi)
        record: dict[str, Any] = {
            "date": adjusted[split_index].date.isoformat(),
            "ratio_step": round(step, 10),
            "trailing_bars": split_index - trailing_lo,
            "forward_bars": forward_hi - split_index,
            "trailing_yield_sum": trailing,
            "forward_yield_sum": forward,
        }
        if trailing > 0 and forward > 0:
            ratio = trailing / forward
            record["ratio"] = ratio
            record["check"] = CHECK_PASSED
            if not SPLIT_RATIO_MIN <= ratio <= SPLIT_RATIO_MAX:
                record["check"] = CHECK_FAILED
                verdict.fail(f"split_dividend_mismatch:{record['date']}")
        else:
            record["check"] = "NOT_APPLICABLE"
        split_records.append(record)
    verdict.checks["splits"] = split_records
    verdict.checks["split_dividend"] = CHECK_FAILED if any(
        r["check"] == CHECK_FAILED for r in split_records) else CHECK_PASSED
    verdict.checks["dividend_status"] = NO_DIVIDENDS if not events else (
        "CHECKED" if verdict.status == CHECK_PASSED else "CHECKED_FAILED")

    if verdict.status == CHECK_PASSED:
        verdict.applied = [(index, yield_) for index, yield_, _ in in_window]
    return verdict


def back_adjust_factors(length: int, applied: Sequence[tuple[int, float]]) -> list[float]:
    """Cumulative factor per trading index: product of (1 - yield) over ex-dates strictly after it."""
    by_index = {index: 1.0 - yield_ for index, yield_ in applied}
    factors = [1.0] * length
    multiplier = 1.0
    for index in range(length - 1, -1, -1):
        factors[index] = multiplier
        if index in by_index:
            multiplier *= by_index[index]
    return factors


def _format_price(value: float) -> str:
    text = format(round(value, FLOAT_DECIMALS), f".{FLOAT_DECIMALS}f").rstrip("0").rstrip(".")
    return text if text not in ("", "-0") else "0"


def _format_volume(value: float) -> str:
    return str(int(value)) if float(value).is_integer() else repr(float(value))


def adjusted_rows(bars: Sequence[Bar], applied: Sequence[tuple[int, float]]) -> list[list[str]]:
    factors = back_adjust_factors(len(bars), applied)
    rows: list[list[str]] = []
    for bar, factor in zip(bars, factors):
        rows.append([
            bar.date.isoformat(),
            _format_price(bar.open * factor),
            _format_price(bar.high * factor),
            _format_price(bar.low * factor),
            _format_price(bar.close * factor),
            _format_volume(bar.volume),
        ])
    return rows


def _csv_bytes(rows: Sequence[Sequence[str]]) -> bytes:
    lines = [",".join(CSV_HEADER)]
    lines.extend(",".join(row) for row in rows)
    return ("\n".join(lines) + "\n").encode("utf-8")


def _load_unadjusted(toss: Path, manifest: Mapping[str, Any], symbol: str, cutoff) -> list[Bar]:
    entry = manifest["unadjusted"][symbol]
    bare = dict(entry)
    bare["file"] = Path(str(entry["file"])).name
    bars = _load_symbol(toss / "unadjusted", symbol, bare)
    if cutoff is not None:
        bars, _ = _split_incomplete(bars, cutoff)
    return bars


def build_total_return_dataset(
    toss_dir: str | Path,
    dividends_dir: str | Path,
    out_dir: str | Path,
    *,
    analysis_start: date = DEFAULT_ANALYSIS_START,
) -> dict[str, Any]:
    """Verify every Toss symbol, write the passing ones back-adjusted, and return the summary."""
    toss = Path(toss_dir)
    dividends = Path(dividends_dir)
    out = Path(out_dir)
    if out.resolve() == toss.resolve():
        raise TotalReturnError("--out must differ from the Toss collection directory")
    source_manifest_path = toss / "manifest.json"
    manifest = json.loads(source_manifest_path.read_text(encoding="utf-8"))
    if manifest.get("interval") != REQUIRED_INTERVAL or manifest.get("adjusted") is not True:
        raise TotalReturnError("source must be a 1d adjusted Toss collection")
    source = load_dataset(toss)
    cutoff = _collection_cutoff(manifest)

    verdicts: dict[str, SymbolVerdict] = {}
    kept_bars: dict[str, list[Bar]] = {}
    kept_unadjusted: dict[str, list[Bar]] = {}
    for symbol in source.symbols:
        adjusted = list(source.bars[symbol])
        unadjusted_entry = manifest["unadjusted"].get(symbol)
        stop_reasons = (
            manifest["symbols"][symbol].get("stop_reason"),
            unadjusted_entry.get("stop_reason") if unadjusted_entry is not None else None,
        )
        if unadjusted_entry is None:
            verdict = verify_symbol(symbol, adjusted, [], [], stop_reasons=stop_reasons, provenance=None,
                                    analysis_start=analysis_start)
            verdict.fail("toss_unadjusted_missing")
            verdicts[symbol] = verdict
            continue
        try:
            unadjusted = _load_unadjusted(toss, manifest, symbol, cutoff)
        except DataIntegrityError as error:
            verdict = verify_symbol(symbol, adjusted, [], [], stop_reasons=stop_reasons, provenance=None,
                                    analysis_start=analysis_start)
            verdict.fail(f"toss_unadjusted_unreadable:{error}")
            verdicts[symbol] = verdict
            continue
        try:
            events, provenance = load_dividend_file(dividends, symbol)
        except TotalReturnError as error:
            verdict = verify_symbol(symbol, adjusted, unadjusted, [], stop_reasons=stop_reasons,
                                    provenance=None)
            verdict.fail(f"dividends_unavailable:{error}")
            verdicts[symbol] = verdict
            continue
        verdicts[symbol] = verify_symbol(symbol, adjusted, unadjusted, events,
                                         stop_reasons=stop_reasons, provenance=provenance)
        kept_bars[symbol] = adjusted
        kept_unadjusted[symbol] = unadjusted

    included = sorted(symbol for symbol, verdict in verdicts.items() if verdict.status == CHECK_PASSED)
    excluded = {symbol: verdicts[symbol].failures for symbol in sorted(verdicts)
                if verdicts[symbol].status != CHECK_PASSED}
    if not included:
        raise TotalReturnError("no symbol passed total-return verification")

    out.mkdir(parents=True, exist_ok=True)
    symbol_entries: dict[str, Any] = {}
    unadjusted_entries: dict[str, Any] = {}
    for symbol in included:
        verdict = verdicts[symbol]
        bars = kept_bars[symbol]
        data = _csv_bytes(adjusted_rows(bars, verdict.applied))
        _write_atomic(out / f"{symbol}.csv", data)
        raw_data = _csv_bytes(adjusted_rows(kept_unadjusted[symbol], []))
        (out / "unadjusted").mkdir(parents=True, exist_ok=True)
        _write_atomic(out / "unadjusted" / f"{symbol}.csv", raw_data)
        source_entry = manifest["symbols"][symbol]
        source_unadjusted = manifest["unadjusted"][symbol]
        symbol_entries[symbol] = {
            "file": f"{symbol}.csv",
            "sha256": sha256_hex(data),
            "rows": len(bars),
            "first_date": bars[0].date.isoformat(),
            "last_date": bars[-1].date.isoformat(),
            "pages": source_entry["pages"],
            "adjustment_basis": TOTAL_RETURN_BASIS,
            "source_sha256": str(source_entry["sha256"]).lower(),
            "verification": verdict.record(),
        }
        unadjusted_entries[symbol] = {
            "file": f"unadjusted/{symbol}.csv",
            "sha256": sha256_hex(raw_data),
            "rows": len(bars),
            "first_date": bars[0].date.isoformat(),
            "last_date": bars[-1].date.isoformat(),
            "pages": source_unadjusted["pages"],
            "source_sha256": str(source_unadjusted["sha256"]).lower(),
        }

    derived = {
        "provider": manifest["provider"],
        "endpoint": manifest.get("endpoint"),
        "interval": REQUIRED_INTERVAL,
        "adjusted": True,
        "adjustment_basis": TOTAL_RETURN_BASIS,
        "collected_at": manifest.get("collected_at"),
        "resumed_at": manifest.get("resumed_at", []),
        "date_basis": manifest.get("date_basis"),
        "symbols": symbol_entries,
        "unadjusted": unadjusted_entries,
        "total_return": {
            "rule_version": TOTAL_RETURN_RULE_VERSION,
            "derived_from": {
                "toss_manifest_sha256": _sha256_file(source_manifest_path),
                "toss_adjustment_classification": manifest.get("adjustment_classification"),
            },
            "rules": {
                "analysis_start": analysis_start.isoformat(),
                "yield_range": [0.0, YIELD_MAX],
                "split_step_threshold": SPLIT_STEP_THRESHOLD,
                "split_window_bars": SPLIT_WINDOW_BARS,
                "split_ratio_range": [SPLIT_RATIO_MIN, SPLIT_RATIO_MAX],
                "back_adjust": "OHLC before ex-date x (1 - D / C_adj(ex-1)), cumulative; volume unchanged",
            },
            "included": included,
            "excluded": excluded,
            "all_source_symbols_verified": not excluded,
            "per_symbol": {symbol: verdicts[symbol].record() for symbol in sorted(verdicts)},
        },
    }
    _write_atomic(out / "manifest.json", (json.dumps(derived, sort_keys=True, indent=2) + "\n").encode("utf-8"))
    return {"included": included, "excluded": excluded, "out": str(out)}


def _write_atomic(path: Path, data: bytes) -> None:
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("wb") as handle:
        handle.write(data)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)


def _iso_date(text: str) -> date:
    try:
        return date.fromisoformat(text)
    except ValueError as error:
        raise argparse.ArgumentTypeError(f"not an ISO date: {text!r}") from error


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m app.research.total_return",
        description="Verify Toss split-adjusted prices against Yahoo dividends and build a SPLIT_AND_DIVIDEND dataset.",
    )
    parser.add_argument("--toss", required=True, type=Path, help="Toss collection directory with manifest.json")
    parser.add_argument("--dividends", required=True, type=Path, help="directory from fetch-yahoo-dividends.py")
    parser.add_argument("--out", required=True, type=Path, help="derived dataset directory")
    parser.add_argument("--analysis-start", type=_iso_date, default=DEFAULT_ANALYSIS_START,
                        help="MAX_PAGES truncation is accepted when both series start on or before this date")
    args = parser.parse_args(argv)
    try:
        summary = build_total_return_dataset(args.toss, args.dividends, args.out,
                                             analysis_start=args.analysis_start)
    except (ResearchError, OSError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    print(f"included ({len(summary['included'])}): {', '.join(summary['included'])}")
    for symbol, reasons in summary["excluded"].items():
        print(f"excluded {symbol}: {', '.join(reasons)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
