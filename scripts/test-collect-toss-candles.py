import hashlib
import importlib.util
import io
import json
import os
import tempfile
import threading
import unittest
from contextlib import redirect_stderr, redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock
import urllib.parse

spec = importlib.util.spec_from_file_location(
    "collect_toss_candles", Path(__file__).with_name("collect-toss-candles.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

TOKEN = "token-must-not-leak-123"
CONNECTION = "11111111-2222-3333-4444-555555555555"
CANDLES_PATH = f"/api/v1/broker-connections/{CONNECTION}/candles"


def envelope(symbol, adjusted, closes):
    candles = [{"timestamp": f"2024-01-{i + 2:02d}T14:30:00Z", "openPrice": c, "highPrice": c + 1,
                "lowPrice": c - 1, "closePrice": c, "volume": 1000 + i}
               for i, c in enumerate(closes)]
    return {"status": "AVAILABLE", "unavailable": False, "unknownFields": [], "provenance": {"source": "TEST"},
            "data": {"symbol": symbol, "interval": "1d", "adjusted": adjusted, "candles": candles,
                     "nextBefore": None}}


def limited(reason):
    return {"status": "UNAVAILABLE", "unavailable": True, "unavailableReason": reason,
            "unknownFields": [], "provenance": None, "data": None}


def serving(bars, scripted=None):
    """bars: symbol -> closes. scripted(path, requests) may return (status, headers, body) to override."""
    def respond(path, requests):
        if scripted is not None:
            override = scripted(path, requests)
            if override is not None:
                return override
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(path).query)
        symbol = query["symbol"][0]
        return 200, {}, envelope(symbol, query["adjusted"][0] == "true", bars[symbol])
    return respond


class FakeBackend:
    """Loopback HTTP server that records request paths and answers with respond(path, requests)."""

    def __init__(self, respond):
        self.requests: list[str] = []
        backend = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                backend.requests.append(self.path)
                status, headers, body = respond(self.path, backend.requests)
                payload = b"" if body is None else json.dumps(body).encode("utf-8")
                self.send_response(status)
                for name, value in headers.items():
                    self.send_header(name, value)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, format, *args):
                return

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def audit(changes=0, negative=0, largest=(), mismatch=0, unmatched=0):
    return {"ratio_change_count": changes, "positive_step_count": changes - negative,
            "negative_step_count": negative,
            "largest_steps": [{"date": "2024-01-02", "ratio_step": step} for step in largest],
            "ohlc_ratio_mismatch_count": mismatch, "ohlc_ratio_mismatch_sample": [],
            "unmatched_dates": unmatched}


def classification_manifest(spy_changes=8, spy_negative=0, spy_largest=(0.01,), gld_changes=0,
                            qqq_mismatch=0, include_spy=True, include_gld=True):
    symbols = {"QQQ": {"rows": 504, "stop_reason": "NO_CURSOR",
                       "adjustment_audit": audit(changes=0, mismatch=qqq_mismatch)}}
    if include_spy:
        symbols["SPY"] = {"rows": 504, "stop_reason": "NO_CURSOR",
                          "adjustment_audit": audit(changes=spy_changes, negative=spy_negative,
                                                    largest=spy_largest)}
    if include_gld:
        symbols["GLD"] = {"rows": 504, "stop_reason": "NO_CURSOR",
                          "adjustment_audit": audit(changes=gld_changes)}
    unadjusted = {symbol: {"stop_reason": "NO_CURSOR"} for symbol in symbols}
    return {"symbols": symbols, "unadjusted": unadjusted, "errors": {}, "warnings": []}


class CollectorTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.out = Path(temp.name) / "dataset"

    def start_backend(self, respond):
        backend = FakeBackend(respond)
        self.addCleanup(backend.close)
        return backend

    def run_collector(self, backend, *extra, out=None):
        sleeps = []
        argv = ["--out", str(out or self.out), "--backend-url", backend.url,
                "--connection-id", CONNECTION, "--pause", "0", *extra]
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr), \
                mock.patch.dict(os.environ, {"TRADE_ACCESS_TOKEN": TOKEN}):
            code = module.main(argv, sleep=sleeps.append)
        return code, stdout.getvalue() + stderr.getvalue(), sleeps

    def read_manifest(self):
        return json.loads((self.out / "manifest.json").read_text(encoding="utf-8"))


class AllowlistTest(CollectorTest):
    def test_rejects_paths_outside_candles_allowlist(self):
        base = "http://127.0.0.1:1"
        for path in [f"/api/v1/broker-connections/{CONNECTION}/orders", "/api/v1/candles",
                     f"{CANDLES_PATH}/../../orders", f"{CANDLES_PATH}/extra"]:
            with self.subTest(path=path), self.assertRaises(module.CollectionError):
                module.check_read_only_request("GET", base + path)

    def test_rejects_non_get_method(self):
        with self.assertRaises(module.CollectionError):
            module.check_read_only_request("POST", f"http://127.0.0.1:1{CANDLES_PATH}")

    def test_rejects_non_http_scheme_and_missing_host_before_io(self):
        with mock.patch.object(module._OPENER, "open", side_effect=AssertionError("network I/O")):
            for base in ["ftp://127.0.0.1:1", "file:///tmp/backend", "http://"]:
                with self.subTest(base=base), self.assertRaises(module.CollectionError):
                    module.fetch_page(base, TOKEN, CONNECTION, "SPY", True, None, 5.0)

    def test_rejects_malformed_connection_id_before_io(self):
        with mock.patch.object(module._OPENER, "open", side_effect=AssertionError("network I/O")):
            with self.assertRaises(module.CollectionError):
                module.fetch_page("http://127.0.0.1:1", TOKEN, "not-a-uuid", "SPY", True, None, 5.0)

    def test_accepts_candles_url(self):
        module.check_read_only_request("GET", f"http://127.0.0.1:8080{CANDLES_PATH}?symbol=SPY")

    def test_refuses_redirects_without_following_them(self):
        backend = self.start_backend(lambda path, requests: (
            302, {"Location": CANDLES_PATH + "?symbol=SPY"}, None))
        with self.assertRaises(module.CollectionError) as caught:
            module.fetch_page(backend.url, TOKEN, CONNECTION, "SPY", True, None, 5.0)
        self.assertIn("redirect refused", str(caught.exception))
        self.assertNotIn(TOKEN, str(caught.exception))
        self.assertEqual(1, len(backend.requests))


class RetryTest(CollectorTest):
    def test_rate_limit_envelope_retries_then_succeeds(self):
        def scripted(path, requests):
            if len(requests) == 1:
                return 200, {}, limited("PROVIDER_RATE_LIMITED")
            return None
        backend = self.start_backend(serving({"SPY": [100.0, 101.0, 102.0]}, scripted))
        code, _, sleeps = self.run_collector(backend, "--symbols", "SPY")
        self.assertEqual(0, code)
        self.assertEqual([2.0], sleeps)
        manifest = self.read_manifest()
        self.assertEqual(1, manifest["symbols"]["SPY"]["retries"])
        self.assertEqual({"PROVIDER_RATE_LIMITED": 1}, manifest["symbols"]["SPY"]["retry_reasons"])
        self.assertEqual(0, manifest["unadjusted"]["SPY"]["retries"])
        self.assertEqual(1, manifest["retry_total"])

    def test_http_429_honors_retry_after_and_caps_it(self):
        def scripted(path, requests):
            if len(requests) == 1:
                return 429, {"Retry-After": "7"}, None
            if len(requests) == 2:
                return 429, {"Retry-After": "999"}, None
            return None
        backend = self.start_backend(serving({"SPY": [100.0, 101.0, 102.0]}, scripted))
        code, _, sleeps = self.run_collector(backend, "--symbols", "SPY")
        self.assertEqual(0, code)
        self.assertEqual([7.0, 120.0], sleeps)
        manifest = self.read_manifest()
        self.assertEqual({"HTTP 429": 2}, manifest["symbols"]["SPY"]["retry_reasons"])
        self.assertEqual(2, manifest["retry_total"])

    def test_retry_exhaustion_is_an_error_naming_the_reason(self):
        backend = self.start_backend(serving({"SPY": [100.0]}, lambda path, requests: (
            200, {}, limited("PROVIDER_TIMEOUT"))))
        code, output, sleeps = self.run_collector(backend, "--symbols", "SPY", "--max-retries", "2")
        self.assertEqual(1, code)
        self.assertEqual([2.0, 4.0], sleeps)
        self.assertIn("PROVIDER_TIMEOUT", self.read_manifest()["errors"]["SPY"])
        self.assertEqual(2, self.read_manifest()["retry_total"])
        self.assertEqual(3, len(backend.requests))


class ResumeAndAuthTest(CollectorTest):
    def test_auth_expired_stops_remaining_symbols_and_keeps_progress(self):
        def scripted(path, requests):
            if "symbol=QQQ" in path:
                return 401, {}, None
            return None
        backend = self.start_backend(serving({"SPY": [100.0, 101.0, 102.0],
                                              "QQQ": [50.0, 51.0, 52.0],
                                              "GLD": [10.0, 11.0, 12.0]}, scripted))
        code, output, _ = self.run_collector(backend, "--symbols", "SPY,QQQ,GLD")
        self.assertEqual(1, code)
        self.assertFalse(any("symbol=GLD" in path for path in backend.requests))
        manifest = self.read_manifest()
        self.assertIn("SPY", manifest["symbols"])
        self.assertIn("QQQ", manifest["errors"])
        self.assertNotIn("GLD", manifest["errors"])
        self.assertIn("rerun with a fresh TRADE_ACCESS_TOKEN and --resume", output)
        self.assertNotIn(TOKEN, output)
        self.assertNotIn(TOKEN, (self.out / "manifest.json").read_text(encoding="utf-8"))

    def test_resume_skips_verified_symbol_and_recollects_tampered_one(self):
        bars = {"SPY": [100.0, 101.0, 102.0], "QQQ": [50.0, 51.0, 52.0]}
        backend = self.start_backend(serving(bars))
        code, _, _ = self.run_collector(backend, "--symbols", "SPY,QQQ")
        self.assertEqual(0, code)
        collected_at = self.read_manifest()["collected_at"]
        (self.out / "QQQ.csv").write_text("date,open,high,low,close,volume\n2024-01-02,1,1,1,1,1\n",
                                          encoding="utf-8")
        backend.requests.clear()
        code, _, _ = self.run_collector(backend, "--symbols", "SPY,QQQ", "--resume")
        self.assertEqual(0, code)
        self.assertFalse(any("symbol=SPY" in path for path in backend.requests))
        self.assertTrue(any("symbol=QQQ" in path for path in backend.requests))
        manifest = self.read_manifest()
        self.assertEqual(collected_at, manifest["collected_at"])
        self.assertEqual(1, len(manifest["resumed_at"]))
        self.assertEqual(manifest["symbols"]["QQQ"]["sha256"],
                         hashlib.sha256((self.out / "QQQ.csv").read_bytes()).hexdigest())

    def test_resume_keeps_max_pages_series_unless_cap_is_raised(self):
        backend = self.start_backend(serving({"SPY": [100.0, 101.0]}))
        self.run_collector(backend, "--symbols", "SPY")
        manifest = self.read_manifest()
        for key in ("symbols", "unadjusted"):
            manifest[key]["SPY"]["stop_reason"] = "MAX_PAGES"
            manifest[key]["SPY"]["pages"] = manifest[key]["SPY"]["pages"] * 2
        self.assertTrue(module.symbol_verified(self.out, manifest, "SPY", 2))
        self.assertFalse(module.symbol_verified(self.out, manifest, "SPY", 3))
        manifest["unadjusted"]["SPY"]["stop_reason"] = "NO_PROGRESS"
        self.assertFalse(module.symbol_verified(self.out, manifest, "SPY", 2))

    def test_existing_manifest_is_refused_without_resume(self):
        backend = self.start_backend(serving({"SPY": [100.0]}))
        self.assertEqual(0, self.run_collector(backend, "--symbols", "SPY")[0])
        backend.requests.clear()
        code, output, _ = self.run_collector(backend, "--symbols", "SPY")
        self.assertEqual(2, code)
        self.assertIn("--resume", output)
        self.assertEqual([], backend.requests)


class AdjustmentTest(CollectorTest):
    def test_audit_counts_step_signs(self):
        adjusted = [["2024-01-02", "1", "1", "1", "1", "1"], ["2024-01-03", "1", "1", "1", "0.9", "1"],
                    ["2024-01-04", "1", "1", "1", "0.99", "1"]]
        raw = [["2024-01-02", "1", "1", "1", "1", "1"], ["2024-01-03", "1", "1", "1", "1", "1"],
               ["2024-01-04", "1", "1", "1", "1", "1"]]
        result = module.adjustment_audit(adjusted, raw)
        self.assertEqual(2, result["ratio_change_count"])
        self.assertEqual(1, result["positive_step_count"])
        self.assertEqual(1, result["negative_step_count"])

    def test_pass_is_split_and_dividend(self):
        classification = module.classify_adjustment(classification_manifest())
        self.assertEqual("adj-rule-v1", classification["rule_version"])
        self.assertEqual("SPLIT_AND_DIVIDEND", classification["result"])
        self.assertTrue(all(check["passed"] for check in classification["checks"].values()))

    def test_gld_control_failure_is_unverified(self):
        classification = module.classify_adjustment(classification_manifest(gld_changes=1))
        self.assertEqual("UNVERIFIED", classification["result"])
        self.assertFalse(classification["checks"]["gld_control"]["passed"])

    def test_spy_negative_step_is_unverified(self):
        classification = module.classify_adjustment(classification_manifest(spy_negative=1))
        self.assertEqual("UNVERIFIED", classification["result"])
        self.assertFalse(classification["checks"]["spy_no_negative_steps"]["passed"])

    def test_ohlc_mismatch_is_unverified(self):
        classification = module.classify_adjustment(classification_manifest(qqq_mismatch=1))
        self.assertEqual("UNVERIFIED", classification["result"])
        self.assertFalse(classification["checks"]["symbol_integrity"]["passed"])

    def test_no_steps_anywhere_is_none_detected(self):
        manifest = classification_manifest(spy_changes=0, spy_largest=())
        self.assertEqual("NONE_DETECTED", module.classify_adjustment(manifest)["result"])

    def test_missing_spy_is_unverified(self):
        manifest = classification_manifest(include_spy=False, spy_changes=0, spy_largest=())
        classification = module.classify_adjustment(manifest)
        self.assertEqual("UNVERIFIED", classification["result"])
        self.assertFalse(classification["checks"]["spy_present"]["passed"])

    def test_classify_only_rewrites_manifest_offline(self):
        backend = self.start_backend(serving({"SPY": [100.0, 101.0, 102.0], "GLD": [10.0, 11.0, 12.0]}))
        self.assertEqual(0, self.run_collector(backend, "--symbols", "SPY,GLD")[0])
        manifest = self.read_manifest()
        manifest["adjustment_basis"] = "BOGUS"
        (self.out / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        with mock.patch.object(module._OPENER, "open", side_effect=AssertionError("network I/O")), \
                mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("TRADE_ACCESS_TOKEN", None)
            stdout = io.StringIO()
            with redirect_stdout(stdout):
                code = module.main(["--out", str(self.out), "--classify-only"])
        self.assertEqual(0, code)
        rewritten = self.read_manifest()
        self.assertEqual("UNVERIFIED", rewritten["adjustment_basis"])
        self.assertEqual("NONE_DETECTED", rewritten["adjustment_classification"]["result"])
        self.assertIn("NONE_DETECTED", stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
