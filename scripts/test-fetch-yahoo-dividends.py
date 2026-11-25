import contextlib
import hashlib
import importlib.util
import io
import json
import tempfile
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest import mock

spec = importlib.util.spec_from_file_location(
    "fetch_yahoo_dividends", Path(__file__).with_name("fetch-yahoo-dividends.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

# 2024-01-02T03:00Z is 2024-01-01 22:00 in New York, so the New York date is the previous day.
EPOCH_LATE_UTC = 1704164400
# 2024-03-15T13:30Z (09:30 New York on the same date).
EPOCH_MARKET_OPEN = 1710509400
GOOD_URL_NOW = 1700000000


def chart_body(events=None, error=None, result_present=True):
    chart = {"error": error, "result": None}
    if result_present:
        result = {"meta": {"symbol": "KO"}}
        if events is not None:
            result["events"] = {"dividends": events}
        chart["result"] = [result]
    return json.dumps({"chart": chart}).encode("utf-8")


class FakeResponse:
    def __init__(self, body):
        self._body = body

    def read(self):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def http_error(code):
    return urllib.error.HTTPError("https://query1.finance.yahoo.com/x", code, "err", {}, None)


class AllowlistTests(unittest.TestCase):
    def test_symbol_pattern_accepts_only_expected_shape(self):
        for symbol in ("KO", "BRK-B", "BF.B", "ABCDEFGHIJ"):
            self.assertEqual(symbol, module.validate_symbol(symbol))
        for symbol in ("", "ko", "KO1", "../KO", "ABCDEFGHIJK", "KO/..", "KO?x=1", "K O"):
            with self.assertRaises(module.FetchError, msg=symbol):
                module.validate_symbol(symbol)

    def test_built_url_has_fixed_shape(self):
        url = module.build_url("KO", GOOD_URL_NOW)
        self.assertEqual(
            "https://query1.finance.yahoo.com/v8/finance/chart/KO"
            f"?period1=0&period2={GOOD_URL_NOW}&interval=1mo&events=div",
            url,
        )
        module.check_read_only_request("GET", url)

    def test_refuses_other_methods_hosts_schemes_and_paths(self):
        good = module.build_url("KO", GOOD_URL_NOW)
        cases = [
            ("POST", good),
            ("GET", good.replace("https://", "http://")),
            ("GET", good.replace("query1.finance.yahoo.com", "evil.example.com")),
            ("GET", good.replace("/v8/finance/chart/KO", "/v7/finance/download/KO")),
            ("GET", good.replace("/v8/finance/chart/KO", "/v8/finance/chart/KO/../../x")),
            ("GET", good + "&range=max"),
            ("GET", good.replace("events=div", "events=split")),
            ("GET", good.replace(f"period2={GOOD_URL_NOW}", "period2=now")),
        ]
        for method, url in cases:
            with self.assertRaises(module.FetchError, msg=url):
                module.check_read_only_request(method, url)

    def test_no_network_for_refused_request(self):
        with mock.patch.object(module._OPENER, "open", side_effect=AssertionError("network I/O")):
            with self.assertRaises(module.FetchError):
                module.fetch_body("https://evil.example.com/v8/finance/chart/KO",
                                  timeout=1, max_retries=0, backoff=0, sleep=lambda s: None)

    def test_redirects_are_refused(self):
        handler = module._RefuseRedirects()
        with self.assertRaises(module.FetchError):
            handler.redirect_request(None, None, 302, "Found", {}, "https://other.example/")

    def test_environment_proxies_are_ignored(self):
        # ProxyHandler({}) registers no *_open methods, so the opener never routes through a proxy
        # and the default environment-driven ProxyHandler is not installed.
        self.assertFalse(any(isinstance(h, urllib.request.ProxyHandler) for h in module._OPENER.handlers))
        self.assertFalse(any(hasattr(h, "proxies") for h in module._OPENER.handlers))
        with mock.patch.dict("os.environ", {"https_proxy": "http://127.0.0.1:9"}):
            self.assertFalse(any(isinstance(h, urllib.request.ProxyHandler) for h in module._OPENER.handlers))


class ParsingTests(unittest.TestCase):
    def test_parses_events_and_converts_epoch_to_new_york_date(self):
        body = chart_body({
            str(EPOCH_LATE_UTC): {"amount": 0.5, "date": EPOCH_LATE_UTC},
            str(EPOCH_MARKET_OPEN): {"amount": 0.25, "date": EPOCH_MARKET_OPEN},
        })
        events = module.parse_dividends(body)
        self.assertEqual(
            [{"ex_date": "2024-01-01", "amount": 0.5}, {"ex_date": "2024-03-15", "amount": 0.25}],
            events,
        )

    def test_symbol_without_dividends_gives_empty_list(self):
        self.assertEqual([], module.parse_dividends(chart_body(events=None)))

    def test_provider_error_and_missing_result_are_rejected(self):
        with self.assertRaises(module.FetchError):
            module.parse_dividends(chart_body(error={"code": "Not Found"}))
        with self.assertRaises(module.FetchError):
            module.parse_dividends(chart_body(result_present=False))
        with self.assertRaises(module.FetchError):
            module.parse_dividends(b"<html>not json</html>")

    def test_non_finite_and_malformed_entries_are_rejected(self):
        with self.assertRaises(module.FetchError):
            module.parse_dividends(chart_body({"1": {"amount": "nan", "date": 1}}))
        with self.assertRaises(module.FetchError):
            module.parse_dividends(chart_body({"1": {"date": 1}}))


class RetryTests(unittest.TestCase):
    def test_retries_429_and_5xx_with_exponential_backoff(self):
        body = chart_body({})
        responses = [http_error(429), http_error(503), FakeResponse(body)]

        def fake_open(request, timeout):
            item = responses.pop(0)
            if isinstance(item, Exception):
                raise item
            return item

        waits = []
        with mock.patch.object(module._OPENER, "open", side_effect=fake_open):
            result = module.fetch_body(module.build_url("KO", GOOD_URL_NOW), timeout=1,
                                       max_retries=3, backoff=2.0, sleep=waits.append)
        self.assertEqual(body, result)
        self.assertEqual([2.0, 4.0], waits)

    def test_gives_up_after_max_retries(self):
        with mock.patch.object(module._OPENER, "open", side_effect=http_error(502)):
            with self.assertRaises(module.FetchError):
                module.fetch_body(module.build_url("KO", GOOD_URL_NOW), timeout=1,
                                  max_retries=2, backoff=1.0, sleep=lambda s: None)

    def test_client_errors_are_not_retried(self):
        waits = []
        with mock.patch.object(module._OPENER, "open", side_effect=http_error(404)) as opener:
            with self.assertRaises(module.FetchError):
                module.fetch_body(module.build_url("KO", GOOD_URL_NOW), timeout=1,
                                  max_retries=3, backoff=1.0, sleep=waits.append)
        self.assertEqual(1, opener.call_count)
        self.assertEqual([], waits)


class MainTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.out = Path(self._tmp.name) / "dividends"

    def tearDown(self):
        self._tmp.cleanup()

    def test_writes_events_raw_body_and_hash_with_pause_between_symbols(self):
        bodies = {
            "KO": chart_body({str(EPOCH_MARKET_OPEN): {"amount": 0.5, "date": EPOCH_MARKET_OPEN}}),
            "JNJ": chart_body(None),
        }

        def fake_open(request, timeout):
            symbol = request.full_url.split("/chart/")[1].split("?")[0]
            return FakeResponse(bodies[symbol])

        pauses = []
        stdout = io.StringIO()
        with mock.patch.object(module._OPENER, "open", side_effect=fake_open), \
                contextlib.redirect_stdout(stdout):
            code = module.main(["--symbols", "KO,JNJ", "--out", str(self.out)],
                               sleep=pauses.append, clock=lambda: GOOD_URL_NOW)
        self.assertEqual(0, code)
        self.assertEqual([1.0], pauses)

        document = json.loads((self.out / "KO.json").read_text(encoding="utf-8"))
        self.assertEqual("KO", document["symbol"])
        self.assertEqual(module.build_url("KO", GOOD_URL_NOW), document["source_url"])
        self.assertEqual([{"ex_date": "2024-03-15", "amount": 0.5}], document["events"])
        raw = (self.out / "raw" / "KO.json").read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(), document["raw_sha256"])
        self.assertEqual(bodies["KO"], raw)
        jnj = json.loads((self.out / "JNJ.json").read_text(encoding="utf-8"))
        self.assertEqual([], jnj["events"])
        self.assertFalse(list(self.out.glob("*.tmp")))
        self.assertFalse(list((self.out / "raw").glob("*.tmp")))

    def test_failed_symbol_writes_nothing_and_exits_nonzero(self):
        stderr = io.StringIO()

        def fake_open(request, timeout):
            raise http_error(404)

        with mock.patch.object(module._OPENER, "open", side_effect=fake_open), \
                contextlib.redirect_stderr(stderr):
            code = module.main(["--symbols", "KO", "--out", str(self.out)],
                               sleep=lambda s: None, clock=lambda: GOOD_URL_NOW)
        self.assertEqual(1, code)
        self.assertIn("FAILED", stderr.getvalue())
        self.assertFalse((self.out / "KO.json").exists())

    def test_invalid_symbol_is_rejected_before_any_request(self):
        with mock.patch.object(module._OPENER, "open", side_effect=AssertionError("network I/O")), \
                contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as raised:
                module.main(["--symbols", "KO,../X", "--out", str(self.out)], sleep=lambda s: None)
        self.assertEqual(2, raised.exception.code)


if __name__ == "__main__":
    unittest.main()
