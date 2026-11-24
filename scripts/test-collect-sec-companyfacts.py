import importlib.util
import hashlib
import io
import json
import tempfile
import unittest
import urllib.error
import urllib.parse
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "collect_sec_companyfacts", Path(__file__).with_name("collect-sec-companyfacts.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

UA = "contact-probe-7f3a@example.invalid"
ENVIRON = {"SEC_USER_AGENT": UA}
TICKERS = {
    "0": {"cik_str": 320193, "ticker": "AAPL", "title": "Apple Inc."},
    "1": {"cik_str": 831001, "ticker": "C", "title": "Citigroup Inc."},
}
APPLE = "0000320193"
LEHMAN = "0000806085"


def submissions(cik10, name, pages=()):
    return json.dumps({
        "cik": str(int(cik10)), "name": name, "sic": "3571", "sicDescription": "Electronic Computers",
        "tickers": [], "filings": {"recent": {"accessionNumber": []},
                                   "files": [{"name": page} for page in pages]},
    }).encode("utf-8")


def companyfacts(cik10):
    return json.dumps({"cik": int(cik10), "entityName": "Test Issuer", "facts": {}}).encode("utf-8")


def ticker_bytes():
    return json.dumps(TICKERS).encode("utf-8")


class FakeResponse:
    def __init__(self, data):
        self.data = data

    def read(self):
        return self.data

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class FakeOpener:
    """Serves scripted responses per URL. Items: bytes (200), or an int status (raised as HTTPError)."""

    def __init__(self, routes):
        self.routes = {url: list(items) for url, items in routes.items()}
        self.calls = []
        self.clock = None
        self.times = []

    def open(self, request, timeout):
        url = request.full_url
        self.calls.append({"url": url, "headers": dict(request.header_items()), "timeout": timeout})
        if self.clock is not None:
            self.times.append(self.clock())
        items = self.routes.get(url)
        if not items:
            raise urllib.error.HTTPError(url, 404, "Not Found", {}, None)
        item = items.pop(0) if len(items) > 1 else items[0]
        if isinstance(item, tuple):
            status, headers = item
            raise urllib.error.HTTPError(url, status, "scripted", headers, None)
        if isinstance(item, int):
            raise urllib.error.HTTPError(url, item, "scripted", {}, None)
        return FakeResponse(item)

    def urls(self):
        return [call["url"] for call in self.calls]


def base_routes(extra=None):
    routes = {
        "https://www.sec.gov/files/company_tickers.json": [ticker_bytes()],
        f"https://data.sec.gov/submissions/CIK{APPLE}.json": [submissions(APPLE, "Apple Inc.")],
        f"https://data.sec.gov/api/xbrl/companyfacts/CIK{APPLE}.json": [companyfacts(APPLE)],
    }
    routes.update(extra or {})
    return routes


class CollectorTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.out = Path(temp.name) / "sec"
        self.sleeps = []
        self.now = [0.0]

    def clock(self):
        return self.now[0]

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now[0] += seconds

    def run_collector(self, opener, *extra, environ=ENVIRON, symbols="AAPL"):
        argv = ["--out", str(self.out), "--symbols", symbols, *extra]
        if isinstance(opener, FakeOpener):
            opener.clock = self.clock
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            code = module.main(argv, environ=environ, opener=opener, sleep=self.sleep, clock=self.clock)
        return code, stdout.getvalue(), stderr.getvalue()

    def manifest(self):
        return json.loads((self.out / "manifest.json").read_text(encoding="utf-8"))


class AllowlistTest(unittest.TestCase):
    def test_accepts_the_four_routes(self):
        accepted = [
            "https://www.sec.gov/files/company_tickers.json",
            f"https://data.sec.gov/api/xbrl/companyfacts/CIK{APPLE}.json",
            f"https://data.sec.gov/submissions/CIK{APPLE}.json",
            f"https://data.sec.gov/submissions/CIK{APPLE}-submissions-001.json",
        ]
        for url in accepted:
            self.assertTrue(module.check_request(url).endswith(urllib.parse.urlsplit(url).path), url)

    def test_rejects_other_hosts_schemes_and_paths_before_io(self):
        refused = [
            "http://data.sec.gov/submissions/CIK0000320193.json",
            "https://evil.example/submissions/CIK0000320193.json",
            "https://www.sec.gov/Archives/edgar/data/320193/index.json",
            "https://data.sec.gov/api/xbrl/frames/us-gaap/Assets/USD/CY2019Q4I.json",
            "https://data.sec.gov/submissions/CIK320193.json",
            "https://data.sec.gov/submissions/CIK0000320193-submissions-1.json",
            "https://data.sec.gov/submissions/CIK0000320193.json?x=1",
            "https://www.sec.gov/files/company_tickers.json#frag",
            "https://data.sec.gov/api/xbrl/companyfacts/CIK0000320193.json/../frames.json",
        ]
        for url in refused:
            with self.subTest(url=url), self.assertRaises(module.CollectionError):
                module.check_request(url)

    def test_companyfacts_route_is_not_valid_on_www_host(self):
        with self.assertRaises(module.CollectionError):
            module.check_request(f"https://www.sec.gov/api/xbrl/companyfacts/CIK{APPLE}.json")



class SetupTest(CollectorTest):
    def test_missing_user_agent_fails_with_clear_message_and_no_requests(self):
        opener = FakeOpener(base_routes())
        code, stdout, stderr = self.run_collector(opener, environ={})
        self.assertEqual(code, 2)
        self.assertIn("SEC_USER_AGENT is not set", stderr)
        self.assertEqual(opener.calls, [])

    def test_control_characters_in_user_agent_are_refused_without_echo(self):
        opener = FakeOpener(base_routes())
        code, stdout, stderr = self.run_collector(opener, environ={"SEC_USER_AGENT": "bad agent\x01 " + UA})
        self.assertEqual(code, 2)
        self.assertNotIn(UA, stderr)
        self.assertEqual(opener.calls, [])

    def test_min_interval_below_five_requests_per_second_is_refused(self):
        opener = FakeOpener(base_routes())
        code, _, stderr = self.run_collector(opener, "--min-interval", "0.1")
        self.assertEqual(code, 2)
        self.assertEqual(opener.calls, [])

    def test_existing_manifest_is_not_overwritten_without_resume(self):
        self.out.mkdir(parents=True)
        (self.out / "manifest.json").write_text("{}", encoding="utf-8")
        opener = FakeOpener(base_routes())
        code, _, stderr = self.run_collector(opener)
        self.assertEqual(code, 2)
        self.assertIn("--resume", stderr)
        self.assertEqual(opener.calls, [])

    def test_unknown_ticker_is_refused_and_points_to_explicit_cik(self):
        opener = FakeOpener(base_routes())
        code, _, stderr = self.run_collector(opener, symbols="ZZZZ")
        self.assertEqual(code, 2)
        self.assertIn("--cik LABEL=CIK", stderr)


class CollectionTest(CollectorTest):
    def test_user_agent_is_sent_but_never_printed_or_stored(self):
        opener = FakeOpener(base_routes())
        code, stdout, stderr = self.run_collector(opener)
        self.assertEqual(code, 0, stderr)
        self.assertTrue(all(call["headers"].get("User-agent") == UA for call in opener.calls))
        self.assertNotIn(UA, stdout)
        self.assertNotIn(UA, stderr)
        for path in self.out.rglob("*"):
            if path.is_file():
                self.assertNotIn(UA.encode("utf-8"), path.read_bytes(), path.name)
        manifest = self.manifest()
        self.assertEqual(manifest["user_agent_source"], "env:SEC_USER_AGENT")
        self.assertEqual(manifest["symbols"]["AAPL"]["status"], "OK")

    def test_manifest_records_sha256_for_every_raw_file(self):
        opener = FakeOpener(base_routes())
        self.run_collector(opener)
        manifest = self.manifest()
        for label, entry in manifest["symbols"].items():
            for relative, info in entry["files"].items():
                data = (self.out / relative).read_bytes()
                self.assertEqual(hashlib.sha256(data).hexdigest(), info["sha256"])
                self.assertEqual(len(data), info["bytes"])
        self.assertEqual(manifest["ticker_file"]["path"], "raw/company_tickers.json")
        self.assertFalse(list(self.out.rglob("*.tmp")))

    def test_older_submission_pages_and_explicit_cik_are_fetched(self):
        lehman_routes = {
            f"https://data.sec.gov/submissions/CIK{LEHMAN}.json": [
                submissions(LEHMAN, "Lehman Brothers Holdings Inc.", pages=[f"CIK{LEHMAN}-submissions-001.json"])],
            f"https://data.sec.gov/submissions/CIK{LEHMAN}-submissions-001.json": [b'{"cik": 806085}'],
            f"https://data.sec.gov/api/xbrl/companyfacts/CIK{LEHMAN}.json": [companyfacts(LEHMAN)],
        }
        opener = FakeOpener(base_routes(lehman_routes))
        code, _, stderr = self.run_collector(opener, "--cik", "LEH=806085", symbols="AAPL")
        self.assertEqual(code, 0, stderr)
        manifest = self.manifest()
        self.assertEqual(manifest["symbols"]["LEH"]["cik_source"], "explicit_cik")
        self.assertEqual(manifest["symbols"]["LEH"]["submission_pages"], [f"CIK{LEHMAN}-submissions-001.json"])
        self.assertTrue((self.out / "raw/submissions" / f"CIK{LEHMAN}-submissions-001.json").is_file())

    def test_companyfacts_404_is_recorded_as_no_xbrl_and_not_retried(self):
        routes = base_routes({f"https://data.sec.gov/api/xbrl/companyfacts/CIK{LEHMAN}.json": [404]})
        routes[f"https://data.sec.gov/submissions/CIK{LEHMAN}.json"] = [submissions(LEHMAN, "Lehman Brothers Holdings Inc.")]
        opener = FakeOpener(routes)
        code, _, stderr = self.run_collector(opener, "--cik", "LEH=806085", symbols="AAPL")
        self.assertEqual(code, 0, stderr)
        self.assertEqual(self.manifest()["symbols"]["LEH"]["status"], "NO_COMPANYFACTS")
        facts_calls = [url for url in opener.urls() if url.endswith(f"CIK{LEHMAN}.json") and "companyfacts" in url]
        self.assertEqual(len(facts_calls), 1)

    def test_submissions_cik_mismatch_is_an_error_for_that_symbol(self):
        routes = base_routes({f"https://data.sec.gov/submissions/CIK{APPLE}.json": [
            submissions("0000000001", "Wrong Issuer")]})
        code, _, _ = self.run_collector(FakeOpener(routes))
        self.assertEqual(code, 1)
        self.assertEqual(self.manifest()["symbols"]["AAPL"]["status"], "ERROR")


class RetryAndRejectionTest(CollectorTest):
    def test_503_then_success_retries_with_retry_after_then_backoff(self):
        url = f"https://data.sec.gov/submissions/CIK{APPLE}.json"
        routes = base_routes({url: [(503, {"Retry-After": "3"}), 503, submissions(APPLE, "Apple Inc.")]})
        code, _, stderr = self.run_collector(FakeOpener(routes), "--backoff", "2")
        self.assertEqual(code, 0, stderr)
        # Throttle sleeps (0.2 s spacing) are interleaved with backoff sleeps; the backoff ones are compared here.
        backoff = [seconds for seconds in self.sleeps if abs(seconds - 0.2) > 1e-6]
        self.assertEqual(backoff, [3.0, 4.0])
        self.assertEqual(self.manifest()["retries"], {"HTTP 503": 2})

    def test_exhausted_retries_mark_symbol_error(self):
        url = f"https://data.sec.gov/submissions/CIK{APPLE}.json"
        routes = base_routes({url: [429]})
        code, _, _ = self.run_collector(FakeOpener(routes), "--max-retries", "1")
        self.assertEqual(code, 1)
        entry = self.manifest()["symbols"]["AAPL"]
        self.assertEqual(entry["status"], "ERROR")
        self.assertIn("persisted after 1 retries", entry["error"])

    def test_403_stops_the_run_writes_manifest_and_requests_nothing_else(self):
        routes = base_routes({"https://www.sec.gov/files/company_tickers.json": [403]})
        opener = FakeOpener(routes)
        code, stdout, stderr = self.run_collector(opener, symbols="AAPL,C")
        self.assertEqual(code, 3)
        self.assertIn("HTTP 403", stderr)
        self.assertEqual(self.manifest()["stopped"]["status"], 403)
        self.assertEqual(len(opener.calls), 1)

    def test_403_is_not_retried(self):
        routes = base_routes({f"https://data.sec.gov/submissions/CIK{APPLE}.json": [403]})
        opener = FakeOpener(routes)
        code, _, _ = self.run_collector(opener)
        self.assertEqual(code, 3)
        self.assertEqual(sum(1 for url in opener.urls() if url.endswith(f"CIK{APPLE}.json")), 1)


class ThrottleAndResumeTest(CollectorTest):
    def test_consecutive_requests_are_spaced_by_at_least_min_interval(self):
        opener = FakeOpener(base_routes())
        self.run_collector(opener, "--min-interval", "0.2")
        spacing = [b - a for a, b in zip(opener.times, opener.times[1:])]
        self.assertGreaterEqual(len(spacing), 2)  # tickers, submissions, companyfacts
        self.assertTrue(all(gap >= 0.2 - 1e-9 for gap in spacing), spacing)

    def test_resume_skips_verified_symbols_and_refetches_tampered_files(self):
        first = FakeOpener(base_routes())
        self.assertEqual(self.run_collector(first)[0], 0)
        second = FakeOpener(base_routes())
        code, stdout, _ = self.run_collector(second, "--resume")
        self.assertEqual(code, 0)
        self.assertIn("resumed status=OK", stdout)
        self.assertEqual([u for u in second.urls() if "companyfacts" in u], [])
        (self.out / "raw/companyfacts" / f"CIK{APPLE}.json").write_bytes(b'{"tampered": true}')
        third = FakeOpener(base_routes())
        code, _, _ = self.run_collector(third, "--resume")
        self.assertEqual(code, 0)
        self.assertTrue(any("companyfacts" in u for u in third.urls()))
        self.assertEqual((self.out / "raw/companyfacts" / f"CIK{APPLE}.json").read_bytes(),
                         companyfacts(APPLE))


if __name__ == "__main__":
    unittest.main()
