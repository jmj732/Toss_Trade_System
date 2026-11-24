import unittest
from datetime import date, datetime, timezone
from decimal import Decimal

from app.research import fundamentals as f

REV = "RevenueFromContractWithCustomerExcludingAssessedTax"
COGS = "CostOfGoodsAndServicesSold"


def fact(start, end, val, accn, form, filed, fy=2020, fp=None):
    row = {"end": end, "val": val, "accn": accn, "form": form, "filed": filed, "fy": fy, "fp": fp}
    if start is not None:
        row["start"] = start
    return row


def company(us_gaap, pages=None):
    return {"cik": 1, "facts": {"us-gaap": us_gaap}}, pages or []


def submissions(rows):
    """rows: (accession, form, filingDate, reportDate, acceptance or None), in the recent-block layout."""
    return {"accessionNumber": [r[0] for r in rows], "form": [r[1] for r in rows],
            "filingDate": [r[2] for r in rows], "reportDate": [r[3] for r in rows],
            "acceptanceDateTime": [r[4] for r in rows]}


SUB_ROWS = [
    ("Q1", "10-Q", "2020-05-05", "2020-03-31", "2020-05-05T20:05:00.000Z"),
    ("Q2", "10-Q", "2020-08-05", "2020-06-30", "2020-08-05T20:00:00.000Z"),
    ("Q3", "10-Q", "2020-11-05", "2020-09-30", "2020-11-05T20:10:00.000Z"),
    ("K1", "10-K", "2021-02-20", "2020-12-31", "2021-02-20T21:30:00.000Z"),
]


def base_us_gaap(revenue_tag=REV, drop_q2_accession=False):
    rev = {
        REV: [
            fact("2020-01-01", "2020-03-31", 100, "Q1", "10-Q", "2020-05-05", fp="Q1"),
            fact("2020-01-01", "2020-06-30", 210, "Q2", "10-Q", "2020-08-05", fp="Q2"),
            fact("2020-04-01", "2020-06-30", 110, "Q2", "10-Q", "2020-08-05", fp="Q2"),
            fact("2020-01-01", "2020-09-30", 330, "Q3", "10-Q", "2020-11-05", fp="Q3"),
            fact("2020-07-01", "2020-09-30", 120, "Q3", "10-Q", "2020-11-05", fp="Q3"),
            fact("2020-01-01", "2020-12-31", 460, "K1", "10-K", "2021-02-20", fp="FY"),
        ],
        COGS: [
            fact("2020-01-01", "2020-03-31", 60, "Q1", "10-Q", "2020-05-05", fp="Q1"),
            fact("2020-04-01", "2020-06-30", 66, "Q2", "10-Q", "2020-08-05", fp="Q2"),
            fact("2020-07-01", "2020-09-30", 72, "Q3", "10-Q", "2020-11-05", fp="Q3"),
            fact("2020-01-01", "2020-09-30", 198, "Q3", "10-Q", "2020-11-05", fp="Q3"),
            fact("2020-01-01", "2020-12-31", 276, "K1", "10-K", "2021-02-20", fp="FY"),
        ],
        "Assets": [
            fact(None, "2020-03-31", 850, "Q1", "10-Q", "2020-05-05"),
            fact(None, "2020-06-30", 900, "Q2", "10-Q", "2020-08-05"),
            fact(None, "2020-09-30", 950, "Q3", "10-Q", "2020-11-05"),
            fact(None, "2020-12-31", 1000, "K1", "10-K", "2021-02-20"),
        ],
    }
    if revenue_tag != REV:
        rev[revenue_tag] = rev.pop(REV)
    return rev


def panel_for(us_gaap, rows=SUB_ROWS, pages=None):
    wrapped = {tag: {"units": {"USD": values}} for tag, values in us_gaap.items()}
    facts = {"facts": {"us-gaap": wrapped}}
    index_pages = pages if pages is not None else [submissions(rows)]
    return f.build_symbol_panel("TEST", "0000000001", facts, index_pages)


def row_at(panel, end):
    return next(r for r in panel if r["period_end"] == end)


class AcceptanceAndTimestampTest(unittest.TestCase):
    def test_acceptance_is_parsed_as_utc(self):
        self.assertEqual(f.parse_acceptance("2024-11-01T10:01:36.000Z"),
                         datetime(2024, 11, 1, 10, 1, 36, tzinfo=timezone.utc))

    def test_fallback_is_end_of_new_york_filing_day(self):
        self.assertEqual(f.end_of_filing_day(date(2020, 8, 5)),
                         datetime(2020, 8, 6, 3, 59, 59, tzinfo=timezone.utc))

    def test_availability_prefers_acceptance_then_flags_fallback(self):
        index = f.filing_index([submissions([("A1", "10-Q", "2020-05-05", "2020-03-31", "2020-05-05T20:05:00.000Z")])])
        self.assertEqual(f.availability(index, "A1", date(2020, 5, 5)),
                         (datetime(2020, 5, 5, 20, 5, tzinfo=timezone.utc), f.ACCEPTANCE_SOURCE))
        self.assertEqual(f.availability(index, "MISSING", date(2020, 5, 5))[1], f.FALLBACK_SOURCE)

    def test_older_submission_page_layout_is_indexed(self):
        page = {"accessionNumber": ["OLD1"], "form": ["10-K"], "filingDate": ["2009-03-01"],
                "reportDate": ["2008-12-31"], "acceptanceDateTime": ["2009-03-01T21:00:00.000Z"]}
        index = f.filing_index([page])
        self.assertEqual(index["OLD1"].report_date, date(2008, 12, 31))


class PlaceholderAndProvenanceTest(unittest.TestCase):
    def test_exact_new_york_midnight_acceptance_is_treated_as_a_placeholder(self):
        self.assertIsNone(f.acceptance_value("2004-03-01T05:00:00.000Z"))  # 00:00 EST
        self.assertIsNotNone(f.acceptance_value("2004-03-01T05:00:01.000Z"))

    def test_components_carry_fiscal_provenance_and_latest_values(self):
        row = row_at(panel_for(base_us_gaap()), "2020-12-31")
        self.assertEqual(len(row["components"]), 9)  # four revenue, four COGS, one assets
        revenue = [c for c in row["components"] if c["group"] == "revenue"]
        self.assertEqual(sorted(c["fiscal_year"] for c in revenue), [2020, 2020, 2020, 2020])
        self.assertEqual(row["components"][-1]["group"], "assets")
        self.assertEqual(row["revenue_ttm_latest"], row["revenue_ttm_first"])
        self.assertEqual(row["assets_latest"], "1000")
        self.assertEqual(revenue[0]["latest_value"], revenue[0]["value"])


class QuarterDerivationTest(unittest.TestCase):
    def test_fy_end_ttm_matches_annual_and_uses_q4_from_fy_minus_9m(self):
        panel = panel_for(base_us_gaap())
        row = row_at(panel, "2020-12-31")
        self.assertEqual(row["status"], "OK")
        self.assertEqual(row["revenue_ttm_first"], "460")
        self.assertEqual(row["cogs_ttm_first"], "276")
        self.assertEqual(row["gross_profit_ttm_first"], "184")
        self.assertEqual(row["assets_first"], "1000")
        self.assertAlmostEqual(row["gp_a_first"], 0.184)
        self.assertEqual(row["quarter_method"], "FY_MINUS_9M")
        self.assertEqual(row["gross_profit_method"], "REVENUE_MINUS_COGS")

    def test_ytd_difference_gives_second_quarter_when_only_ytd_is_reported(self):
        us_gaap = base_us_gaap()
        us_gaap[REV] = [r for r in us_gaap[REV] if not (r["start"] == "2020-04-01")]
        us_gaap[COGS] = [r for r in us_gaap[COGS] if not (r["start"] == "2020-04-01")]
        panel = panel_for(us_gaap)
        self.assertEqual(row_at(panel, "2020-12-31")["revenue_ttm_first"], "460")

    def test_ttm_is_missing_when_the_prior_year_quarters_are_absent(self):
        panel = panel_for(base_us_gaap())
        self.assertEqual(row_at(panel, "2020-03-31")["status"], "NO_REVENUE_TTM")

    def test_contiguity_gap_blocks_ttm(self):
        us_gaap = base_us_gaap()
        us_gaap[REV] = [r for r in us_gaap[REV] if r.get("start") != "2020-07-01"]
        us_gaap[REV].append(fact("2020-07-02", "2020-09-30", 120, "Q3", "10-Q", "2020-11-05", fp="Q3"))
        us_gaap[COGS] = [r for r in us_gaap[COGS] if r.get("start") != "2020-07-01"]
        panel = panel_for(us_gaap)
        self.assertNotEqual(row_at(panel, "2020-12-31")["status"], "OK")

    def test_conflicting_duplicate_versions_give_no_value(self):
        us_gaap = base_us_gaap()
        us_gaap[REV].append(fact("2020-04-01", "2020-06-30", 111, "Q2", "10-Q", "2020-08-05", fp="Q2"))
        panel = panel_for(us_gaap)
        self.assertEqual(row_at(panel, "2020-12-31")["status"], "NO_REVENUE_TTM")

    def test_cross_alias_duplicates_keep_first_alias(self):
        us_gaap = base_us_gaap()
        us_gaap["Revenues"] = [fact("2020-01-01", "2020-12-31", 999, "K1", "10-K", "2021-02-20", fp="FY")]
        panel = panel_for(us_gaap)
        row = row_at(panel, "2020-12-31")
        self.assertEqual(row["revenue_ttm_first"], "460")
        self.assertTrue(all(concept == REV for concept in row["revenue_concepts"]))


class AmendmentTest(unittest.TestCase):
    def test_first_reported_and_latest_are_both_kept(self):
        us_gaap = base_us_gaap()
        # Filed after the Q2 10-Q, so the Q2 six-month YTD (210) still reflects the original Q1 (100).
        us_gaap[REV].append(fact("2020-01-01", "2020-03-31", 105, "Q1A", "10-Q/A", "2020-09-15", fp="Q1"))
        rows = SUB_ROWS + [("Q1A", "10-Q/A", "2020-09-15", "2020-03-31", "2020-09-15T20:00:00.000Z")]
        panel = panel_for(us_gaap, rows=rows)
        row = row_at(panel, "2020-12-31")
        self.assertEqual(row["revenue_ttm_first"], "460")
        self.assertIn("revenue", row["amended_components"])
        self.assertAlmostEqual(row["gp_a_latest"], (465 - 276) / 1000)
        self.assertAlmostEqual(row["gp_a_first"], 0.184)


class AvailabilityTest(unittest.TestCase):
    def test_availability_is_the_latest_component_acceptance(self):
        panel = panel_for(base_us_gaap())
        self.assertEqual(row_at(panel, "2020-12-31")["available_at"], "2021-02-20T21:30:00Z")
        self.assertEqual(row_at(panel, "2020-12-31")["availability_source"], f.ACCEPTANCE_SOURCE)

    def test_missing_accession_falls_back_and_is_flagged(self):
        rows = [r for r in SUB_ROWS if r[0] != "Q2"]
        panel = panel_for(base_us_gaap(), rows=rows)
        row = row_at(panel, "2020-12-31")
        self.assertEqual(row["availability_source"], f.FALLBACK_SOURCE)
        self.assertEqual(row["fallback_components"], 2)


class ConceptAndMethodTest(unittest.TestCase):
    def test_revenue_tag_switch_is_recorded_per_quarter(self):
        us_gaap = base_us_gaap()
        us_gaap[REV] = [r for r in us_gaap[REV] if r.get("accn") != "Q1" or r["form"] != "10-Q"]
        us_gaap["Revenues"] = [fact("2020-01-01", "2020-03-31", 100, "Q1", "10-Q", "2020-05-05", fp="Q1")]
        panel = panel_for(us_gaap)
        row = row_at(panel, "2020-12-31")
        self.assertEqual(row["revenue_concepts"][-1], "Revenues")
        self.assertEqual(row["revenue_ttm_first"], "460")

    def test_gross_profit_tag_is_used_only_when_cogs_is_missing(self):
        us_gaap = base_us_gaap()
        del us_gaap[COGS]
        us_gaap["GrossProfit"] = [
            fact("2020-01-01", "2020-03-31", 40, "Q1", "10-Q", "2020-05-05", fp="Q1"),
            fact("2020-04-01", "2020-06-30", 44, "Q2", "10-Q", "2020-08-05", fp="Q2"),
            fact("2020-07-01", "2020-09-30", 48, "Q3", "10-Q", "2020-11-05", fp="Q3"),
            fact("2020-01-01", "2020-09-30", 132, "Q3", "10-Q", "2020-11-05", fp="Q3"),
            fact("2020-01-01", "2020-12-31", 184, "K1", "10-K", "2021-02-20", fp="FY"),
        ]
        panel = panel_for(us_gaap)
        row = row_at(panel, "2020-12-31")
        self.assertEqual(row["gross_profit_method"], "GROSS_PROFIT_TAG")
        self.assertEqual(row["gross_profit_ttm_first"], "184")

    def test_no_cogs_and_no_gross_profit_is_reported_not_proxied(self):
        us_gaap = base_us_gaap()
        del us_gaap[COGS]
        row = row_at(panel_for(us_gaap), "2020-12-31")
        self.assertEqual(row["status"], "NO_COGS_OR_GROSS_PROFIT_TTM")
        self.assertIsNone(row["gp_a_first"])


if __name__ == "__main__":
    unittest.main()
