#!/usr/bin/env python3
"""Offline stdlib tests for scripts/ev1-recorder.py."""
from __future__ import annotations

import csv
import importlib.util
import json
import sys
import tempfile
import unittest
import urllib.error
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

SCRIPT = Path(__file__).with_name("ev1-recorder.py")
spec = importlib.util.spec_from_file_location("ev1_recorder", SCRIPT)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
assert spec.loader is not None
spec.loader.exec_module(module)


class FakeResponse:
    def __init__(self, body: bytes, headers=None):
        self.body = body
        self.headers = headers or {}

    def read(self):
        return self.body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class FakeOpener:
    def __init__(self, routes):
        self.routes = {url: list(values) for url, values in routes.items()}
        self.calls = []

    def open(self, request, timeout):
        self.calls.append(request)
        values = self.routes.get(request.full_url)
        if not values:
            raise urllib.error.HTTPError(request.full_url, 404, "not found", {}, None)
        value = values.pop(0) if len(values) > 1 else values[0]
        if isinstance(value, int):
            raise urllib.error.HTTPError(request.full_url, value, "scripted", {}, None)
        return FakeResponse(value)


def price_rows(entry_open: float, future_open: float, start=date(2026, 11, 1)) -> list[dict]:
    rows = []
    for index in range(20):
        day = start + timedelta(days=index)
        rows.append({
            "date": day.isoformat(), "open": entry_open, "high": entry_open + 1,
            "low": entry_open - 1, "close": entry_open, "adjclose": entry_open,
            "adjusted_open": entry_open, "volume": 1000,
        })
    entry_day = date(2026, 12, 1)
    rows.append({
        "date": entry_day.isoformat(), "open": entry_open, "high": entry_open + 1,
        "low": entry_open - 1, "close": entry_open, "adjclose": entry_open,
        "adjusted_open": entry_open, "volume": 1000,
    })
    for index in range(1, 21):
        day = entry_day + timedelta(days=index)
        rows.append({
            "date": day.isoformat(), "open": future_open, "high": future_open + 1,
            "low": future_open - 1, "close": future_open, "adjclose": future_open,
            "adjusted_open": future_open, "volume": 1000,
        })
    return rows


def write_price_csv(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = ["date", "open", "high", "low", "close", "adjclose", "volume",
              "adjustment_factor", "adjusted_open"]
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow({**row, "adjustment_factor": 1.0})


class AllowlistAndObservationTests(unittest.TestCase):
    def test_sec_allowlist_rejects_non_read_only_routes(self):
        accepted = [
            "https://data.sec.gov/submissions/CIK0000002488.json",
            "https://www.sec.gov/Archives/edgar/data/2488/000000248818000058/ex99-1.htm",
        ]
        for url in accepted:
            module.check_sec_url(url)
        refused = [
            "http://data.sec.gov/submissions/CIK0000002488.json",
            "https://evil.example/submissions/CIK0000002488.json",
            "https://data.sec.gov/submissions/CIK0000002488.json?token=x",
            "https://www.sec.gov/Archives/edgar/data/2488/000000248818000058/../secret.txt",
            "https://www.sec.gov/Archives/edgar/data/2488/000000248818000058/file.htm:443",
        ]
        for url in refused:
            with self.subTest(url=url), self.assertRaises(module.FetchError):
                module.check_sec_url(url)

    def test_observed_at_is_separate_from_sec_acceptance(self):
        issuer = {"ticker": "TEST", "cik": "0000002488", "sic": "3571", "sic2": "35"}
        payload = {"filings": {"recent": {
            "form": ["8-K"], "items": ["2.02,9.01"],
            "filingDate": ["2026-11-26"], "accessionNumber": ["0000002488-26-000001"],
            "acceptanceDateTime": ["2026-11-26T13:00:00Z"],
            "primaryDocument": ["release.htm"], "reportDate": ["2026-11-25"],
        }}}
        events = module.extract_submission_events(payload, issuer, "2026-11-26T13:00:02Z")
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0]["acceptanceDateTime"], "2026-11-26T13:00:00Z")
        self.assertEqual(events[0]["observed_at"], "2026-11-26T13:00:02Z")
        self.assertNotEqual(events[0]["acceptanceDateTime"], events[0]["observed_at"])

    def test_unmapped_issuer_is_progress_exclusion_not_error(self):
        manifest = {"progress": {}, "errors": [], "events": {}, "responses": {}}
        client = type("Client", (), {"throttle": type("Throttle", (), {"requests": 0})(), "retries": 0})()
        module.poll_once(
            manifest,
            [{"ticker": "BAD", "mapping_status": "MAPPING_INVALID"}],
            client,
            Path("/nonexistent/ev1-out"),
            Path("/nonexistent/prices"),
        )
        self.assertEqual(manifest["errors"], [])
        self.assertEqual(manifest["progress"]["unmapped_issuers"], 1)

    def test_client_retries_all_5xx_without_printing_user_agent(self):
        url = "https://data.sec.gov/submissions/CIK0000002488.json"
        opener = FakeOpener({url: [501, b"{}"]})
        sleeps = []
        clock = [0.0]
        throttle = module.Throttle(sleep=lambda seconds: (sleeps.append(seconds), clock.__setitem__(0, clock[0] + seconds)),
                                   clock=lambda: clock[0])
        client = module.SecClient("contact@example.invalid", opener=opener, throttle=throttle,
                                 max_retries=1, backoff=0, sleep=lambda _: None,
                                 clock=lambda: datetime(2026, 11, 26, 13, 0, 2, tzinfo=timezone.utc))
        result = client.get(url)
        self.assertEqual(result.body, b"{}")
        self.assertEqual(client.retries, 1)
        self.assertTrue(all("contact@example.invalid" not in str(call) for call in opener.calls))


class ParserAndTimingTests(unittest.TestCase):
    def test_positive_gaap_diluted_pair_is_strong25(self):
        result = module.parse_eps_pair(
            "GAAP diluted earnings per share was $1.25, compared with $1.00 in prior year."
        )
        self.assertEqual(result["status"], "EPS_STRONG25")
        self.assertEqual(result["current_eps"], 1.25)
        self.assertEqual(result["prior_eps"], 1.0)

    def test_ambiguous_or_non_gaap_pair_is_unusable(self):
        ambiguous = (
            "GAAP diluted EPS was $1.25 compared with $1.00. "
            "GAAP diluted EPS was $1.10 compared with $0.90."
        )
        self.assertEqual(module.parse_eps_pair(ambiguous)["status"], "EPS_UNUSABLE")
        self.assertEqual(
            module.parse_eps_pair("Non-GAAP diluted EPS was $1.50 compared with $1.20.")["status"],
            "EPS_UNUSABLE",
        )
        self.assertEqual(
            module.parse_eps_pair("GAAP diluted EPS for 2026 was $1.25 compared with 2025 EPS of $1.00")["status"],
            "EPS_UNUSABLE",
        )

    def test_ready_time_controls_same_day_then_next_day(self):
        rows = [
            {"date": date(2026, 12, 1), "entry_value": 100.0, "entry_value_field": "open"},
            {"date": date(2026, 12, 2), "entry_value": 101.0, "entry_value_field": "open"},
        ]
        before_cutoff = datetime(2026, 12, 1, 14, 0, tzinfo=timezone.utc)  # 09:00 ET
        after_cutoff = datetime(2026, 12, 1, 14, 16, tzinfo=timezone.utc)   # 09:16 ET
        self.assertTrue(module.choose_entry_bar(rows, before_cutoff)["same_day"])
        self.assertFalse(module.choose_entry_bar(rows, after_cutoff)["same_day"])
        self.assertEqual(module.choose_entry_bar(rows, after_cutoff)["entry_date"], "2026-12-02")

    def test_missing_exit_bar_is_explicit(self):
        rows = [{"date": date(2026, 12, 1) + timedelta(days=index),
                 "entry_value": 100.0, "entry_value_field": "open"} for index in range(5)]
        evidence = module.exit_evidence(rows, 0, 100.0)
        self.assertEqual(evidence["3"]["status"], "AVAILABLE")
        self.assertEqual(evidence["5"]["status"], "MISSING")

    def test_round_trip_cost_math_is_multiplicative(self):
        self.assertAlmostEqual(module.net_return(0.10, 30), 1.10 * 0.997 - 1.0)


class ControlsAndAtomicOutputTests(unittest.TestCase):
    def test_control_matching_and_atomic_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            prices = root / "prices"
            universe = []
            # Low-liquidity rows establish a tercile boundary.  The event and
            # three controls share the high tercile; a same-date event issuer
            # shares it too and must be excluded from controls.
            tickers = [f"L{index}" for index in range(12)] + ["EV", "C1", "C2", "C3", "SAME"]
            for ticker in tickers:
                universe.append({
                    "ticker": ticker, "cik": "0000002488", "sic": "3571", "sic2": "35",
                    "mapping_status": "MAPPED",
                })
                rows = price_rows(100.0, 100.0 if ticker.startswith("L") else (110.0 if ticker == "EV" else 101.0))
                if ticker == "SAME":
                    rows = price_rows(100.0, 101.0)
                for row in rows:
                    row["volume"] = 1 if ticker.startswith("L") else 100000
                write_price_csv(prices / f"{ticker}.csv", rows)
            manifest = {"events": {
                "event-ev": {
                    "accession": "event-ev", "ticker": "EV", "cik": "0000002488",
                    "sic": "3571", "sic2": "35", "acceptanceDateTime": "2026-12-01T13:00:00Z",
                    "observed_at": "2026-12-01T13:00:01Z", "parser_status": "EPS_STRONG25",
                    "package_class": "CORE_EARNINGS", "items": "2.02,9.01",
                },
                "event-same": {
                    "accession": "event-same", "ticker": "SAME", "cik": "0000002488",
                    "sic": "3571", "sic2": "35", "acceptanceDateTime": "2026-12-01T13:00:00Z",
                    "observed_at": "2026-12-01T13:00:01Z", "parser_status": "EPS_UNUSABLE",
                    "package_class": "MATERIAL_CONCURRENT", "items": "2.02,1.01",
                },
            }}
            module.evaluate_prices(manifest, universe, prices)
            event = manifest["events"]["event-ev"]
            self.assertEqual(event["status"], "ADMITTED")
            self.assertEqual(event["control_count"], 3)
            self.assertTrue(any(item["ticker"] == "SAME" and "same_date_event_issuer" in item["reasons"]
                                for item in event["control_exclusions"]))
            self.assertIsNotNone(event["control_median_returns"]["5"])
            output = root / "out" / "manifest.json"
            module.write_manifest_atomic(output, {"progress": {"events_seen": 2}, "events": {}})
            self.assertTrue(output.is_file())
            self.assertFalse(list(output.parent.glob("*.tmp")))
            self.assertEqual(json.loads(output.read_text())["progress"]["events_seen"], 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
