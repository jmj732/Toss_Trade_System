#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("collect-yahoo-daily.py")
spec = importlib.util.spec_from_file_location("collect_yahoo_daily", SCRIPT)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)


def sample() -> bytes:
    return json.dumps({"chart": {"error": None, "result": [{
        "meta": {"symbol": "BRK-B", "exchangeTimezoneName": "America/New_York",
                 "currency": "USD", "instrumentType": "EQUITY"},
        "timestamp": [1514903400, 1514989800],
        "indicators": {
            "quote": [{"open": [10, 11], "high": [12, 13], "low": [9, 10],
                       "close": [11, 12], "volume": [100, 200]}],
            "adjclose": [{"adjclose": [5.5, 6.0]}],
        },
    }]}}).encode()


def expect_error(callable_):
    try:
        callable_()
    except module.CollectionError:
        return
    raise AssertionError("CollectionError expected")


def main() -> None:
    assert module.validate_symbol("BRK.B") == "BRK-B"
    refresh_args = module.build_parser().parse_args(["--symbols", "BRK.B", "--out", "/tmp/prices", "--refresh"])
    assert refresh_args.refresh and not refresh_args.resume
    try:
        module.build_parser().parse_args(["--symbols", "BRK.B", "--out", "/tmp/prices", "--refresh", "--resume"])
    except SystemExit:
        pass
    else:
        raise AssertionError("--refresh and --resume must be mutually exclusive")
    url = module.build_url("BRK.B", 1, 2)
    module.check_request(url)
    expect_error(lambda: module.check_request(url + "&crumb=secret"))
    expect_error(lambda: module.check_request(url.replace("query1.finance.yahoo.com", "example.com")))
    expect_error(lambda: module.build_url("../../orders", 1, 2))
    rows, meta = module.parse(sample(), "BRK-B")
    assert len(rows) == 2 and rows[0]["adjustment_factor"] == 0.5
    assert rows[0]["adjusted_open"] == 5.0 and meta["currency"] == "USD"
    encoded = module.csv_bytes(rows)
    assert encoded.startswith(b"date,open,high,low,close,adjclose,volume,adjustment_factor,adjusted_open\n")
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "x.csv"
        module.atomic(path, encoded)
        assert path.read_bytes() == encoded
        original_fetch = module.fetch
        try:
            module.fetch = lambda *args, **kwargs: sample()
            assert module.main(["--symbols", "BRK.B", "--out", directory,
                                "--start", "2018-01-01", "--end-exclusive", "2026-11-21",
                                "--pause", "0", "--max-retries", "0"]) == 0
            manifest_path = Path(directory) / "manifest.json"
            manifest = json.loads(manifest_path.read_text())
            manifest["errors"]["retained"] = "prior failure"
            manifest_path.write_text(json.dumps(manifest))
            assert module.main(["--symbols", "BRK.B", "--out", directory,
                                "--start", "2018-01-01", "--end-exclusive", "2026-11-22",
                                "--pause", "0", "--max-retries", "0", "--refresh"]) == 0
            refreshed = json.loads(manifest_path.read_text())
            assert refreshed["end_exclusive"] == "2026-11-22"
            assert refreshed["errors"]["retained"] == "prior failure"
        finally:
            module.fetch = original_fetch
    invalid_bar = json.loads(sample())
    invalid_bar["chart"]["result"][0]["indicators"]["quote"][0]["low"][1] = 11.5
    filtered, filtered_meta = module.parse(json.dumps(invalid_bar).encode(), "BRK-B")
    assert len(filtered) == 1 and filtered_meta["skipped_invalid_rows"] == 1
    broken = json.loads(sample())
    broken["chart"]["result"][0]["meta"]["symbol"] = "OTHER"
    expect_error(lambda: module.parse(json.dumps(broken).encode(), "BRK-B"))
    print("ok: collect-yahoo-daily")


if __name__ == "__main__":
    main()
