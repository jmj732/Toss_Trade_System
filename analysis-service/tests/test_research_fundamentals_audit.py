import unittest
from datetime import date, datetime, timezone
from decimal import Decimal

from app.research import fundamentals_audit as audit


def row(period_end, status="OK", gp_a=0.4, revenue=None, concepts=None):
    return {"symbol": "T", "period_end": period_end, "status": status, "gp_a_first": gp_a,
            "revenue_ttm_first": revenue, "revenue_concepts": concepts, "cogs_concepts": None}


class CalendarAndStatisticsTest(unittest.TestCase):
    def test_calendar_window_has_seventy_quarter_ends_with_month_end_dates(self):
        ends = audit.CALENDAR_QUARTERS
        self.assertEqual(len(ends), 70)
        self.assertEqual(ends[0], date(2009, 6, 30))
        self.assertEqual(ends[-1], date(2026, 9, 30))
        self.assertTrue(all(end.month in (3, 6, 9, 12) for end in ends))

    def test_percentile_interpolates(self):
        self.assertEqual(audit.percentile([1, 2, 3, 4], 0.5), 2.5)
        self.assertIsNone(audit.percentile([], 0.5))

    def test_relative_change_uses_absolute_first_value(self):
        self.assertAlmostEqual(audit.relative_change(Decimal("-100"), Decimal("-50")), 0.5)
        self.assertIsNone(audit.relative_change(Decimal("0"), Decimal("5")))

    def test_et_conversion_is_used_for_the_after_close_test(self):
        # 20:05 UTC in January is 15:05 EST, which is before the 16:00 ET cutoff. In EDT it would be 16:05.
        self.assertEqual(audit.to_et(datetime(2024, 1, 15, 20, 5, tzinfo=timezone.utc)).hour, 15)
        self.assertEqual(audit.to_et(datetime(2024, 11, 1, 20, 5, tzinfo=timezone.utc)).hour, 16)


class CoverageTest(unittest.TestCase):
    def test_non_december_fiscal_quarter_ends_match_the_nearest_calendar_quarter(self):
        rows = [row("2010-07-31"), row("2010-10-30"), row("2011-01-29")]
        coverage = audit.coverage_of(rows)
        self.assertEqual(coverage["covered_calendar_quarters"], 3)

    def test_rows_without_gp_a_do_not_count(self):
        coverage = audit.coverage_of([row("2010-06-30", status="NO_ASSETS", gp_a=None)])
        self.assertEqual(coverage["covered_calendar_quarters"], 0)
        self.assertIsNone(coverage["first_computable_period_end"])


class TagSwitchTest(unittest.TestCase):
    def test_jump_at_a_concept_switch_is_counted_separately_from_a_jump_without_one(self):
        rows = [
            row("2018-03-31", revenue="100", concepts=["Revenues"]),
            row("2018-06-30", revenue="150", concepts=["RevenueFromContractWithCustomerExcludingAssessedTax"]),
            row("2018-09-30", revenue="155", concepts=["RevenueFromContractWithCustomerExcludingAssessedTax"]),
        ]
        result = audit.tag_switches(rows)
        self.assertEqual(result["revenue_tag_switches"], 1)
        self.assertEqual(result["jumps_over_20pct_at_switch"], 1)
        self.assertEqual(result["jumps_over_20pct_without_switch"], 0)


class MarkdownTest(unittest.TestCase):
    def test_financial_coverage_is_reported_as_excluded_and_sections_are_present(self):
        cov = {"coverage": 0.1, "covered_calendar_quarters": 7, "calendar_quarters": 70,
               "first_computable_period_end": None, "last_computable_period_end": None}
        audit_result = {
            "as_of": "2026-11-24", "window": {"start": "2009-06-30", "end": "2026-09-30", "calendar_quarters": 70},
            "universe_size": 1,
            "a_publication_timing": {"fact_versions_2009_2026": 1, "accepted_versions": 1, "fallback_versions": 0,
                                     "fallback_share": 0.0, "after_1600_et_share_all_versions": 0.5,
                                     "filed_before_period_end_errors": 0, "by_form_own_period": {},
                                     "utc_validation": {"filings_accepted_after_1730_et": 0,
                                                        "share_with_next_day_filing_date": None},
                                     "pre_2003_acceptance_utc_hours": {}},
            "b_amendments": {"amendment_counts_per_symbol": {}, "periods_total": 0,
                             "periods_with_same_filing_conflict": 0, "periods_with_multiple_versions": 0,
                             "periods_where_latest_differs_from_first": 0, "latest_differs_by_later_form": {},
                             "share_of_multi_version_periods_differing": None,
                             "relative_change_abs_distribution": {"n": 0}, "largest_cases": []},
            "c_industry_comparability": {"per_symbol": {"BAC": {
                "sic": "6021", "financial_sic": True, "coverage": cov,
                "tag_switches": {"revenue_tag_switches": 0, "jumps_over_20pct_at_switch": 0},
                "notes": ["금융업"]}}, "flags": []},
            "d_survivorship": {"universe_symbols": 1, "cik_source_counts": {}, "universe_selected_from_current_ticker_file": 1,
                               "current_ticker_file_share": 1.0, "delisted_tests": {}, "conclusion": "x"},
            "e_coverage": [{"symbol": "BAC", "cik": "0000070858", "name": "BANK", "sic": "6021", "status": "OK",
                            **cov, "first_computable_period_end": None, "last_computable_period_end": None}],
            "data_problems": ["p"],
        }
        text = audit.render_markdown(audit_result)
        for heading in ("## a. 공시 공개 시점", "## b. 정정공시와 재기술", "## c. 산업별 비교 가능성",
                        "## d. 생존 편향", "## e. 종목별 커버리지", "## 방법론과 Java 대응"):
            self.assertIn(heading, text)
        self.assertIn("제외(금융업)", text)


if __name__ == "__main__":
    unittest.main()
