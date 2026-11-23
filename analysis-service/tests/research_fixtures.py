"""Deterministic synthetic datasets for research tests. Test-only; never shipped as data."""

from __future__ import annotations

import csv
import hashlib
import json
import random
from datetime import date, timedelta
from pathlib import Path

ALL_UNIVERSE_SYMBOLS = ("SPY", "QQQ", "IWM", "EFA", "EEM", "TLT", "IEF", "GLD")
COLLECTED_AT = "2026-01-02T00:00:00Z"
ROW_HEADER = ("date", "open", "high", "low", "close", "volume")
Row = tuple[date, float, float, float, float, int]


def weekdays(start: date, count: int) -> list[date]:
    days: list[date] = []
    day = start
    while len(days) < count:
        if day.weekday() < 5:
            days.append(day)
        day += timedelta(days=1)
    return days


def synthetic_rows(
    seed: int,
    count: int,
    *,
    start: date = date(2015, 1, 1),
    drift: float = 0.0004,
    vol: float = 0.011,
    start_price: float = 100.0,
) -> list[Row]:
    """Random-walk OHLCV. Monotone rounding keeps OHLC ordering valid after rounding."""
    rng = random.Random(seed)
    price = start_price
    rows: list[Row] = []
    for day in weekdays(start, count):
        open_price = price * (1.0 + rng.gauss(0.0, vol / 4))
        close = open_price * (1.0 + drift + rng.gauss(0.0, vol))
        high = max(open_price, close) * (1.0 + abs(rng.gauss(0.0, vol / 3)))
        low = min(open_price, close) * (1.0 - abs(rng.gauss(0.0, vol / 3)))
        rows.append(
            (
                day,
                round(open_price, 4),
                round(high, 4),
                round(low, 4),
                round(close, 4),
                rng.randint(1_000_000, 5_000_000),
            )
        )
        price = close
    return rows


def write_dataset(
    root: Path,
    series: dict[str, list[Row]],
    *,
    adjustment_basis: str = "SPLIT_AND_DIVIDEND",
) -> Path:
    """Write per-symbol CSVs and a manifest whose hashes match the files."""
    root.mkdir(parents=True, exist_ok=True)
    symbols: dict[str, dict] = {}
    for symbol in sorted(series):
        rows = series[symbol]
        path = root / f"{symbol}.csv"
        with path.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.writer(handle)
            writer.writerow(ROW_HEADER)
            for day, open_price, high, low, close, volume in rows:
                writer.writerow([day.isoformat(), open_price, high, low, close, volume])
        symbols[symbol] = {
            "file": path.name,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "rows": len(rows),
            "first_date": rows[0][0].isoformat(),
            "last_date": rows[-1][0].isoformat(),
            "pages": [{"before": None, "observed_at": COLLECTED_AT}],
        }
    manifest = {
        "provider": "TOSS",
        "endpoint": "/api/v1/candles",
        "interval": "1d",
        "adjusted": True,
        "adjustment_basis": adjustment_basis,
        "collected_at": COLLECTED_AT,
        "symbols": symbols,
    }
    (root / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True), encoding="utf-8")
    return root


def universe_series(count: int, *, base_seed: int = 7) -> dict[str, list[Row]]:
    """All eight momentum-universe symbols plus deterministic per-symbol seeds."""
    return {
        symbol: synthetic_rows(base_seed + index, count, drift=0.0002 + 0.0001 * index)
        for index, symbol in enumerate(ALL_UNIVERSE_SYMBOLS)
    }
