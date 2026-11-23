"""Dataset loading, integrity verification, canonical JSON and alignment.

Dataset layout: ``manifest.json`` plus one ``<SYMBOL>.csv`` per symbol with the
header ``date,open,high,low,close,volume`` (New York trading dates).
"""

from __future__ import annotations

import csv
import hashlib
import io
import json
import math
from dataclasses import dataclass, field
from datetime import date, datetime, time
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence
from zoneinfo import ZoneInfo

CSV_HEADER = ("date", "open", "high", "low", "close", "volume")
REQUIRED_INTERVAL = "1d"
ALLOWED_ADJUSTMENT_BASIS = ("UNVERIFIED", "SPLIT_ONLY", "SPLIT_AND_DIVIDEND")
GAP_WARNING_CALENDAR_DAYS = 5
EXTREME_MOVE_THRESHOLD = 0.4
FLOAT_DECIMALS = 10
NEW_YORK = ZoneInfo("America/New_York")
COLLECTION_CLOSE_NY = time(16, 30)


class ResearchError(ValueError):
    """Invalid research request or input."""


class DataIntegrityError(ResearchError):
    """Dataset files do not match the manifest or contain invalid bars."""


@dataclass(frozen=True)
class Bar:
    date: date
    open: float
    high: float
    low: float
    close: float
    volume: float


@dataclass(frozen=True)
class Dataset:
    bars: Mapping[str, Sequence[Bar]]
    provenance: Mapping[str, Any]
    dataset_hash: str
    warnings: tuple[str, ...] = field(default_factory=tuple)

    @property
    def symbols(self) -> tuple[str, ...]:
        return tuple(sorted(self.bars))


@dataclass(frozen=True)
class AlignedData:
    symbols: tuple[str, ...]
    dates: tuple[date, ...]
    bars: Mapping[str, Sequence[Bar]]
    dropped_dates: int


def _normalize_for_json(value: Any) -> Any:
    if isinstance(value, bool) or value is None or isinstance(value, str):
        return value
    if isinstance(value, float):
        if not math.isfinite(value):
            return None
        return round(value, FLOAT_DECIMALS) + 0.0  # + 0.0 folds -0.0 into 0.0
    if isinstance(value, Mapping):
        return {str(key): _normalize_for_json(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_normalize_for_json(item) for item in value]
    return value


def canonical_json(payload: Any, *, indent: int | None = None) -> str:
    """Deterministic JSON: sorted keys, floats rounded, non-finite values as null."""
    separators = (",", ":") if indent is None else (",", ": ")
    return json.dumps(
        _normalize_for_json(payload),
        sort_keys=True,
        indent=indent,
        separators=separators,
        ensure_ascii=False,
        allow_nan=False,
    )


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _require_mapping(value: Any, where: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise DataIntegrityError(f"{where}: expected an object")
    return value


def _validate_manifest(manifest: Mapping[str, Any]) -> None:
    if not isinstance(manifest.get("provider"), str):
        raise DataIntegrityError("manifest.provider must be a string")
    if manifest.get("interval") != REQUIRED_INTERVAL:
        raise DataIntegrityError(f"manifest.interval must be {REQUIRED_INTERVAL!r}")
    if not isinstance(manifest.get("adjusted"), bool):
        raise DataIntegrityError("manifest.adjusted must be a boolean")
    if manifest.get("adjustment_basis") not in ALLOWED_ADJUSTMENT_BASIS:
        raise DataIntegrityError(
            f"manifest.adjustment_basis must be one of {ALLOWED_ADJUSTMENT_BASIS}"
        )
    symbols = _require_mapping(manifest.get("symbols"), "manifest.symbols")
    if not symbols:
        raise DataIntegrityError("manifest.symbols is empty")
    for symbol, entry in symbols.items():
        entry = _require_mapping(entry, f"manifest.symbols.{symbol}")
        for key in ("file", "sha256", "rows", "first_date", "last_date", "pages"):
            if key not in entry:
                raise DataIntegrityError(f"manifest.symbols.{symbol} missing {key!r}")
        if not isinstance(entry["pages"], list):
            raise DataIntegrityError(f"manifest.symbols.{symbol}.pages must be a list")


def _parse_row(symbol: str, line_no: int, row: Sequence[str]) -> Bar:
    if len(row) != len(CSV_HEADER):
        raise DataIntegrityError(f"{symbol} line {line_no}: expected {len(CSV_HEADER)} columns")
    try:
        bar_date = date.fromisoformat(row[0])
        open_, high, low, close, volume = (float(value) for value in row[1:])
    except ValueError as error:
        raise DataIntegrityError(f"{symbol} line {line_no}: unparseable value ({error})") from error
    if not all(math.isfinite(v) for v in (open_, high, low, close, volume)):
        raise DataIntegrityError(f"{symbol} line {line_no}: non-finite value")
    if min(open_, high, low, close) <= 0:
        raise DataIntegrityError(f"{symbol} line {line_no}: prices must be > 0")
    if volume < 0:
        raise DataIntegrityError(f"{symbol} line {line_no}: volume must be >= 0")
    if high < max(open_, close, low):
        raise DataIntegrityError(f"{symbol} line {line_no}: high below open/close/low")
    if low > min(open_, close, high):
        raise DataIntegrityError(f"{symbol} line {line_no}: low above open/close/high")
    return Bar(bar_date, open_, high, low, close, volume)


def _parse_csv(symbol: str, raw: bytes) -> list[Bar]:
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise DataIntegrityError(f"{symbol}: file is not UTF-8") from error
    reader = csv.reader(io.StringIO(text))
    header = next(reader, None)
    if tuple(header or ()) != CSV_HEADER:
        raise DataIntegrityError(f"{symbol}: header must be {','.join(CSV_HEADER)}")
    bars: list[Bar] = []
    for line_no, row in enumerate(reader, start=2):
        if not row:
            continue
        bar = _parse_row(symbol, line_no, row)
        if bars and bar.date <= bars[-1].date:
            raise DataIntegrityError(
                f"{symbol} line {line_no}: dates must be strictly increasing and unique"
            )
        bars.append(bar)
    if not bars:
        raise DataIntegrityError(f"{symbol}: no bars")
    return bars


def _series_warnings(symbol: str, bars: Sequence[Bar]) -> list[str]:
    warnings: list[str] = []
    for previous, current in zip(bars, bars[1:]):
        gap_days = (current.date - previous.date).days
        if gap_days > GAP_WARNING_CALENDAR_DAYS:
            warnings.append(
                f"{symbol}: calendar gap of {gap_days} days between "
                f"{previous.date.isoformat()} and {current.date.isoformat()}"
            )
        move = current.close / previous.close - 1.0
        if abs(move) > EXTREME_MOVE_THRESHOLD:
            warnings.append(
                f"{symbol}: single-day move {move:+.1%} on {current.date.isoformat()} "
                "(possible unadjusted split or data error)"
            )
    return warnings


def _parse_utc_timestamp(value: Any, where: str) -> datetime:
    if not isinstance(value, str):
        raise DataIntegrityError(f"{where} must be an ISO-8601 UTC timestamp string")
    text = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError as error:
        raise DataIntegrityError(f"{where} is not an ISO-8601 timestamp ({error})") from error
    if parsed.tzinfo is None:
        raise DataIntegrityError(f"{where} must include a UTC offset")
    return parsed.astimezone(NEW_YORK)


def _collection_cutoff(manifest: Mapping[str, Any]) -> datetime | None:
    """Latest collection instant (New York time), or None when collected_at is absent."""
    collected = manifest.get("collected_at")
    if collected is None:
        return None
    instants = [_parse_utc_timestamp(collected, "manifest.collected_at")]
    resumed = manifest.get("resumed_at")
    if resumed is not None:
        if not isinstance(resumed, list):
            raise DataIntegrityError("manifest.resumed_at must be a list")
        instants.extend(
            _parse_utc_timestamp(item, f"manifest.resumed_at[{index}]")
            for index, item in enumerate(resumed)
        )
    return max(instants)


def _split_incomplete(bars: Sequence[Bar], cutoff: datetime) -> tuple[list[Bar], list[Bar]]:
    """Split bars into (kept, possibly incomplete) relative to the collection cutoff."""
    cutoff_date = cutoff.date()
    before_close = cutoff.time() < COLLECTION_CLOSE_NY
    kept: list[Bar] = []
    incomplete: list[Bar] = []
    for bar in bars:
        if bar.date > cutoff_date or (bar.date == cutoff_date and before_close):
            incomplete.append(bar)
        else:
            kept.append(bar)
    return kept, incomplete


def _exclusion_warning(symbol: str, dropped: Sequence[Bar], cutoff: datetime) -> str:
    dates = ", ".join(bar.date.isoformat() for bar in dropped)
    reasons: list[str] = []
    if any(bar.date == cutoff.date() for bar in dropped):
        reasons.append(f"collected {cutoff:%H:%M} ET, before 16:30 close")
    if any(bar.date > cutoff.date() for bar in dropped):
        reasons.append(f"dated after collection on {cutoff.date().isoformat()}")
    return f"{symbol}: excluded possibly incomplete bar(s) dated {dates} ({'; '.join(reasons)})"


def _load_symbol(root: Path, symbol: str, entry: Mapping[str, Any]) -> list[Bar]:
    file_name = entry["file"]
    if not isinstance(file_name, str) or Path(file_name).name != file_name:
        raise DataIntegrityError(f"{symbol}: file must be a bare file name")
    path = root / file_name
    if not path.is_file():
        raise DataIntegrityError(f"{symbol}: missing file {file_name}")
    raw = path.read_bytes()
    digest = sha256_hex(raw)
    if digest != str(entry["sha256"]).lower():
        raise DataIntegrityError(f"{symbol}: sha256 mismatch for {file_name}")
    bars = _parse_csv(symbol, raw)
    if len(bars) != entry["rows"]:
        raise DataIntegrityError(f"{symbol}: row count {len(bars)} != manifest {entry['rows']}")
    if bars[0].date.isoformat() != entry["first_date"] or bars[-1].date.isoformat() != entry["last_date"]:
        raise DataIntegrityError(f"{symbol}: first/last date disagree with manifest")
    return bars


def _provenance(
    manifest: Mapping[str, Any],
    excluded: Mapping[str, Sequence[str]],
) -> dict[str, Any]:
    symbols = {
        symbol: {
            "sha256": str(entry["sha256"]).lower(),
            "rows": entry["rows"],
            "first_date": entry["first_date"],
            "last_date": entry["last_date"],
            "pages": entry["pages"],
        }
        for symbol, entry in manifest["symbols"].items()
    }
    return {
        "provider": manifest["provider"],
        "endpoint": manifest.get("endpoint"),
        "interval": manifest["interval"],
        "adjusted": manifest["adjusted"],
        "adjustment_basis": manifest["adjustment_basis"],
        "collected_at": manifest.get("collected_at"),
        "excluded_incomplete_bars": {symbol: list(dates) for symbol, dates in sorted(excluded.items())},
        "symbols": symbols,
    }


def load_dataset(directory: str | Path) -> Dataset:
    """Load and verify a dataset directory. Raises DataIntegrityError on any violation.

    Files are verified in full first. Bars that may still be forming at collection
    time are then excluded from the loaded series: any bar dated after the latest
    collection's New York date, and the collection day's own bar when collection
    happened before the 16:30 New York close. Files and dataset_hash are not affected.
    """
    root = Path(directory)
    manifest_path = root / "manifest.json"
    if not manifest_path.is_file():
        raise DataIntegrityError(f"missing manifest.json in {root}")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise DataIntegrityError(f"manifest.json is not valid JSON ({error})") from error
    _validate_manifest(manifest)
    cutoff = _collection_cutoff(manifest)

    bars: dict[str, list[Bar]] = {}
    excluded: dict[str, list[str]] = {}
    warnings: list[str] = []
    for symbol in sorted(manifest["symbols"]):
        series = _load_symbol(root, symbol, manifest["symbols"][symbol])
        if cutoff is not None:
            series, dropped = _split_incomplete(series, cutoff)
            if dropped:
                if not series:
                    raise DataIntegrityError(
                        f"{symbol}: no bars left after excluding possibly incomplete bars"
                    )
                excluded[symbol] = [bar.date.isoformat() for bar in dropped]
                warnings.append(_exclusion_warning(symbol, dropped, cutoff))
        bars[symbol] = series
        warnings.extend(_series_warnings(symbol, series))
    if cutoff is None:
        warnings.append(
            "manifest has no collected_at: possibly incomplete collection-day bars cannot be "
            "detected; none were excluded"
        )

    file_hashes = {symbol: str(entry["sha256"]).lower() for symbol, entry in manifest["symbols"].items()}
    dataset_hash = sha256_hex(canonical_json({"manifest": manifest, "files": file_hashes}).encode("utf-8"))
    return Dataset(
        bars=bars,
        provenance=_provenance(manifest, excluded),
        dataset_hash=dataset_hash,
        warnings=tuple(warnings),
    )


def align(dataset: Dataset, symbols: Iterable[str]) -> AlignedData:
    """Restrict every requested symbol to the dates all of them share."""
    requested = tuple(sorted(set(symbols)))
    if not requested:
        raise ResearchError("align requires at least one symbol")
    unknown = [symbol for symbol in requested if symbol not in dataset.bars]
    if unknown:
        raise ResearchError(f"unknown symbols: {', '.join(unknown)}")
    date_sets = {symbol: {bar.date for bar in dataset.bars[symbol]} for symbol in requested}
    common = set.intersection(*date_sets.values())
    union = set.union(*date_sets.values())
    ordered = tuple(sorted(common))
    aligned = {
        symbol: tuple(bar for bar in dataset.bars[symbol] if bar.date in common) for symbol in requested
    }
    return AlignedData(
        symbols=requested,
        dates=ordered,
        bars=aligned,
        dropped_dates=len(union) - len(common),
    )
