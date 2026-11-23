from __future__ import annotations

import json
from datetime import date
from pathlib import Path

import pytest
from research_fixtures import Row, synthetic_rows, weekdays, write_dataset

from app.research.data import (
    DataIntegrityError,
    ResearchError,
    align,
    canonical_json,
    load_dataset,
)


def _series(count: int = 300) -> dict[str, list[Row]]:
    return {
        "SPY": synthetic_rows(11, count),
        "TLT": synthetic_rows(12, count),
    }


def test_clean_dataset_loads_with_provenance_and_hash(tmp_path: Path) -> None:
    root = write_dataset(tmp_path / "ds", _series())
    dataset = load_dataset(root)
    assert dataset.symbols == ("SPY", "TLT")
    assert len(dataset.bars["SPY"]) == 300
    assert dataset.provenance["adjustment_basis"] == "SPLIT_AND_DIVIDEND"
    assert dataset.provenance["symbols"]["SPY"]["rows"] == 300
    assert len(dataset.dataset_hash) == 64
    assert dataset.warnings == ()


def test_dataset_hash_is_stable_across_loads(tmp_path: Path) -> None:
    root = write_dataset(tmp_path / "ds", _series())
    assert load_dataset(root).dataset_hash == load_dataset(root).dataset_hash


def test_dataset_hash_changes_when_manifest_changes(tmp_path: Path) -> None:
    root = write_dataset(tmp_path / "ds", _series())
    before = load_dataset(root).dataset_hash
    manifest_path = root / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["collected_at"] = "2026-02-02T00:00:00Z"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    assert load_dataset(root).dataset_hash != before


def test_sha256_mismatch_raises(tmp_path: Path) -> None:
    root = write_dataset(tmp_path / "ds", _series())
    with (root / "SPY.csv").open("a", encoding="utf-8") as handle:
        handle.write("\n")
    with pytest.raises(DataIntegrityError, match="sha256 mismatch"):
        load_dataset(root)


def test_bad_ohlc_raises(tmp_path: Path) -> None:
    rows = synthetic_rows(3, 20)
    day, open_price, high, low, close, volume = rows[5]
    rows[5] = (day, open_price, close - 1.0, low, close, volume)
    root = write_dataset(tmp_path / "ds", {"SPY": rows})
    with pytest.raises(DataIntegrityError, match="high below open/close/low"):
        load_dataset(root)


def test_non_positive_price_raises(tmp_path: Path) -> None:
    rows = synthetic_rows(4, 20)
    day, _, high, low, close, volume = rows[2]
    rows[2] = (day, 0.0, high, low, close, volume)
    root = write_dataset(tmp_path / "ds", {"SPY": rows})
    with pytest.raises(DataIntegrityError, match="prices must be > 0"):
        load_dataset(root)


def test_negative_volume_raises(tmp_path: Path) -> None:
    rows = synthetic_rows(4, 20)
    day, open_price, high, low, close, _ = rows[2]
    rows[2] = (day, open_price, high, low, close, -1)
    root = write_dataset(tmp_path / "ds", {"SPY": rows})
    with pytest.raises(DataIntegrityError, match="volume"):
        load_dataset(root)


def test_nan_value_raises(tmp_path: Path) -> None:
    rows = synthetic_rows(4, 20)
    day, open_price, high, low, _, volume = rows[2]
    rows[2] = (day, open_price, high, low, float("nan"), volume)
    root = write_dataset(tmp_path / "ds", {"SPY": rows})
    with pytest.raises(DataIntegrityError, match="non-finite"):
        load_dataset(root)


def test_duplicate_dates_raise(tmp_path: Path) -> None:
    rows = synthetic_rows(5, 20)
    rows.insert(6, rows[5])
    root = write_dataset(tmp_path / "ds", {"SPY": rows})
    with pytest.raises(DataIntegrityError, match="strictly increasing"):
        load_dataset(root)


def test_unsorted_dates_raise(tmp_path: Path) -> None:
    rows = synthetic_rows(5, 20)
    rows[4], rows[5] = rows[5], rows[4]
    root = write_dataset(tmp_path / "ds", {"SPY": rows})
    with pytest.raises(DataIntegrityError, match="strictly increasing"):
        load_dataset(root)


def test_manifest_row_count_mismatch_raises(tmp_path: Path) -> None:
    root = write_dataset(tmp_path / "ds", _series())
    manifest_path = root / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["symbols"]["SPY"]["rows"] = 299
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(DataIntegrityError, match="row count"):
        load_dataset(root)


def test_file_name_outside_directory_is_rejected(tmp_path: Path) -> None:
    root = write_dataset(tmp_path / "ds", _series())
    manifest_path = root / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["symbols"]["SPY"]["file"] = "../SPY.csv"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(DataIntegrityError, match="bare file name"):
        load_dataset(root)


def test_extreme_single_day_move_warns_and_moderate_move_does_not(tmp_path: Path) -> None:
    rows = synthetic_rows(6, 40)
    day, open_price, high, low, close, volume = rows[20]
    jump_close = round(rows[19][4] * 1.5, 4)
    rows[20] = (day, open_price, max(high, jump_close), low, jump_close, volume)
    moderate_rows = synthetic_rows(7, 40)
    day2, o2, h2, l2, _, v2 = moderate_rows[20]
    moderate_close = round(moderate_rows[19][4] * 1.3, 4)
    moderate_rows[20] = (day2, o2, max(h2, moderate_close), l2, moderate_close, v2)
    root = write_dataset(tmp_path / "ds", {"SPY": rows, "TLT": moderate_rows})
    dataset = load_dataset(root)
    jump_warnings = [w for w in dataset.warnings if "single-day move" in w]
    assert len(jump_warnings) >= 1
    assert all(w.startswith("SPY:") for w in jump_warnings)


def test_calendar_gap_warns(tmp_path: Path) -> None:
    rows = synthetic_rows(8, 60)
    gapped = rows[:30] + rows[40:]
    root = write_dataset(tmp_path / "ds", {"SPY": gapped})
    assert any("calendar gap" in warning for warning in load_dataset(root).warnings)


def test_align_reports_dropped_dates(tmp_path: Path) -> None:
    spy = synthetic_rows(9, 30)
    tlt = spy[:3] + spy[4:]
    root = write_dataset(tmp_path / "ds", {"SPY": spy, "TLT": tlt})
    aligned = align(load_dataset(root), ["SPY", "TLT"])
    assert len(aligned.dates) == 29
    assert aligned.dropped_dates == 1
    assert all(len(aligned.bars[symbol]) == 29 for symbol in ("SPY", "TLT"))
    assert aligned.dates == tuple(sorted(aligned.dates))


def test_align_rejects_unknown_symbol(tmp_path: Path) -> None:
    root = write_dataset(tmp_path / "ds", _series())
    with pytest.raises(ResearchError, match="unknown symbols: QQQ"):
        align(load_dataset(root), ["SPY", "QQQ"])


def test_canonical_json_rounds_floats_and_maps_non_finite_to_null() -> None:
    text = canonical_json({"b": -0.0, "a": 0.1 + 0.2, "c": float("nan"), "d": [1, 2.5]})
    assert text == '{"a":0.3,"b":0.0,"c":null,"d":[1,2.5]}'


def test_weekday_helper_skips_weekends() -> None:
    days = weekdays(date(2024, 1, 5), 6)
    assert all(day.weekday() < 5 for day in days)
    assert days[0] == date(2024, 1, 5)
