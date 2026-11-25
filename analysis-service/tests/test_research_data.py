from __future__ import annotations

import json
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest
from research_fixtures import Row, synthetic_rows, weekdays, write_dataset

from app.research.data import (
    DataIntegrityError,
    ResearchError,
    align,
    canonical_json,
    load_dataset,
    sha256_hex,
)

NEW_YORK = ZoneInfo("America/New_York")
COLLECTION_DAY = date(2026, 11, 23)  # Monday, Eastern Standard Time


def _series(count: int = 300) -> dict[str, list[Row]]:
    return {
        "SPY": synthetic_rows(11, count),
        "TLT": synthetic_rows(12, count),
    }


def _rows_ending_on(seed: int, count: int, last: date) -> list[Row]:
    """Synthetic weekday rows whose final bar is ``last``."""
    days: list[date] = []
    day = last
    while len(days) < count:
        if day.weekday() < 5:
            days.append(day)
        day -= timedelta(days=1)
    days.reverse()
    template = synthetic_rows(seed, count)
    return [(new_day, o, h, low, c, v) for new_day, (_, o, h, low, c, v) in zip(days, template)]


def _utc_at_new_york(day: date, hour: int, minute: int) -> str:
    """UTC ``Z`` timestamp for a New York wall-clock time (DST-correct)."""
    local = datetime(day.year, day.month, day.day, hour, minute, tzinfo=NEW_YORK)
    return local.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _collection_dataset(
    tmp_path: Path,
    *,
    collected_at: str | None,
    resumed_at: list[str] | None = None,
    last: date = COLLECTION_DAY,
    count: int = 300,
) -> Path:
    series = {
        "SPY": _rows_ending_on(11, count, last),
        "TLT": _rows_ending_on(12, count, last),
    }
    root = write_dataset(tmp_path / "ds", series)
    manifest_path = root / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if collected_at is None:
        manifest.pop("collected_at")
    else:
        manifest["collected_at"] = collected_at
    if resumed_at is not None:
        manifest["resumed_at"] = resumed_at
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    return root


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


def test_collection_day_bar_before_close_is_excluded(tmp_path: Path) -> None:
    root = _collection_dataset(tmp_path, collected_at="2026-11-23T11:06:27Z")
    dataset = load_dataset(root)
    previous_day = date(2026, 11, 20)
    assert dataset.bars["SPY"][-1].date == previous_day
    assert dataset.bars["TLT"][-1].date == previous_day
    assert len(dataset.bars["SPY"]) == 299
    assert dataset.provenance["excluded_incomplete_bars"] == {
        "SPY": ["2026-11-23"],
        "TLT": ["2026-11-23"],
    }
    assert any(
        "excluded possibly incomplete bar(s) dated 2026-11-23 (collected 06:06 ET, before 16:30 close)"
        in warning
        for warning in dataset.warnings
    )


@pytest.mark.parametrize(
    ("hour", "minute", "excluded"),
    [(16, 29, True), (16, 30, False), (17, 0, False)],
)
def test_collection_day_close_boundary(tmp_path: Path, hour: int, minute: int, excluded: bool) -> None:
    root = _collection_dataset(tmp_path, collected_at=_utc_at_new_york(COLLECTION_DAY, hour, minute))
    dataset = load_dataset(root)
    if excluded:
        assert dataset.bars["SPY"][-1].date == date(2026, 11, 20)
        assert dataset.provenance["excluded_incomplete_bars"]["SPY"] == ["2026-11-23"]
    else:
        assert dataset.bars["SPY"][-1].date == COLLECTION_DAY
        assert dataset.provenance["excluded_incomplete_bars"] == {}
        assert dataset.warnings == ()


def test_future_dated_bar_is_excluded(tmp_path: Path) -> None:
    collected_day = date(2026, 11, 20)
    root = _collection_dataset(tmp_path, collected_at=_utc_at_new_york(collected_day, 17, 0))
    dataset = load_dataset(root)
    assert dataset.bars["SPY"][-1].date == collected_day
    assert dataset.provenance["excluded_incomplete_bars"] == {
        "SPY": ["2026-11-23"],
        "TLT": ["2026-11-23"],
    }
    assert any("dated after collection on 2026-11-20" in warning for warning in dataset.warnings)


def test_resumed_at_latest_timestamp_is_used(tmp_path: Path) -> None:
    before_close = _utc_at_new_york(COLLECTION_DAY, 9, 30)
    after_close = _utc_at_new_york(COLLECTION_DAY, 17, 0)
    earlier_resume = _utc_at_new_york(COLLECTION_DAY, 10, 0)

    without_resume = load_dataset(_collection_dataset(tmp_path / "a", collected_at=before_close))
    assert without_resume.provenance["excluded_incomplete_bars"]["SPY"] == ["2026-11-23"]

    # The latest instant is the first list entry and the last entry is before the close,
    # so only a true maximum (not the last element) keeps the collection-day bar.
    with_resume = load_dataset(
        _collection_dataset(
            tmp_path / "b",
            collected_at=before_close,
            resumed_at=[after_close, earlier_resume],
        )
    )
    assert with_resume.bars["SPY"][-1].date == COLLECTION_DAY
    assert with_resume.provenance["excluded_incomplete_bars"] == {}
    assert with_resume.warnings == ()


def test_missing_collected_at_drops_nothing_and_warns(tmp_path: Path) -> None:
    root = _collection_dataset(tmp_path, collected_at=None)
    dataset = load_dataset(root)
    assert len(dataset.bars["SPY"]) == 300
    assert dataset.bars["SPY"][-1].date == COLLECTION_DAY
    assert dataset.provenance["excluded_incomplete_bars"] == {}
    assert len(dataset.warnings) == 1
    assert "no collected_at" in dataset.warnings[0]


def test_dataset_hash_unchanged_by_exclusion(tmp_path: Path) -> None:
    root = _collection_dataset(tmp_path, collected_at="2026-11-23T11:06:27Z")
    files = sorted(root.iterdir())
    before = {path.name: path.read_bytes() for path in files}

    dataset = load_dataset(root)

    assert dataset.provenance["excluded_incomplete_bars"]  # exclusion really happened
    assert {path.name: path.read_bytes() for path in files} == before
    manifest = json.loads(before["manifest.json"].decode("utf-8"))
    file_hashes = {symbol: entry["sha256"] for symbol, entry in manifest["symbols"].items()}
    expected = sha256_hex(canonical_json({"manifest": manifest, "files": file_hashes}).encode("utf-8"))
    assert dataset.dataset_hash == expected


def test_exclusion_is_deterministic(tmp_path: Path) -> None:
    root = _collection_dataset(tmp_path, collected_at="2026-11-23T11:06:27Z")
    assert load_dataset(root) == load_dataset(root)


@pytest.mark.parametrize(
    ("collected_at", "message"),
    [
        ("2026-11-23T11:06:27", "UTC offset"),
        ("not-a-time", "ISO-8601"),
        (1_700_000_000, "ISO-8601 UTC timestamp string"),
    ],
)
def test_malformed_collected_at_raises(tmp_path: Path, collected_at: object, message: str) -> None:
    root = _collection_dataset(tmp_path, collected_at=collected_at)  # type: ignore[arg-type]
    with pytest.raises(DataIntegrityError, match=message):
        load_dataset(root)


def test_exclusion_leaving_no_bars_raises(tmp_path: Path) -> None:
    root = _collection_dataset(
        tmp_path,
        collected_at=_utc_at_new_york(COLLECTION_DAY, 9, 30),
        count=1,
    )
    with pytest.raises(DataIntegrityError, match="no bars left"):
        load_dataset(root)
