package com.jmj.trade.marketdata;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.namespace.QName;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecCompanyFactsProviderTest {

    private static final WireMockServer SERVER = new WireMockServer(options().dynamicPort());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant OBSERVED_AT = Instant.parse("2026-10-03T01:44:33Z");
    private static final String CIK = "0000001234";
    private static final String CURRENT_Q = "0000001234-26-000010";
    private static final String PRIOR_Q = "0000001234-25-000002";
    private static final String PRIOR_K = "0000001234-25-000005";
    private static final String CURRENT_K = "0000001234-25-000006";

    static {
        SERVER.start();
    }

    @BeforeEach
    void reset() {
        SERVER.resetAll();
    }

    @AfterAll
    static void stop() {
        SERVER.stop();
    }

    @Test
    void resolvesTickerAndReconstructsFlowsOnlyFromComparableUsdPeriods() throws Exception {
        stubCompany("XYZ", submissions("XYZ", 1234,
                List.of(
                        filing("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31", "2026-04-29T12:00:00Z", 2026, "Q2"),
                        filing("10-Q", PRIOR_Q, "2025-04-29", "2025-03-31", "2025-04-29T12:00:00Z", 2025, "Q2"),
                        filing("10-K", PRIOR_K, "2025-11-10", "2025-09-30", "2025-11-10T12:00:00Z", 2025, "FY"),
                        filing("10-Q/A", "0000001234-26-000011", "2026-05-15", "2025-12-31",
                                "2026-05-15T12:00:00Z", 2026, "Q1"),
                        filing("10-Q", "0000001234-26-000099", "2026-10-04", "2026-09-30",
                                "2026-10-04T12:00:00Z", 2027, "Q4"))), quarterlyFacts());

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertThat(value(values, "filing.form").value().asText()).isEqualTo("10-Q");
        assertThat(value(values, "filing.accession").value().asText()).isEqualTo(CURRENT_Q);
        assertThat(value(values, "fundamental.fiscalPeriod").value().asText()).isEqualTo("2026-03-31");
        assertThat(value(values, "fundamental.reportedAt").value().asText()).isEqualTo("2026-04-29T12:00:00Z");
        assertThat(value(values, "fundamental.fiscalYear").value().asText()).isEqualTo("2026");
        assertThat(value(values, "fundamental.fiscalPeriodCode").value().asText()).isEqualTo("Q2");
        assertThat(value(values, "fundamental.cash").value().decimalValue()).isEqualByComparingTo("120");
        assertThat(value(values, "fundamental.cash").unit()).isEqualTo("USD");
        assertThat(value(values, "fundamental.cash").asOf())
                .isEqualTo(Instant.parse("2026-03-31T00:00:00Z"));
        assertThat(value(values, "fundamental.debt").value().decimalValue()).isEqualByComparingTo("200");
        assertThat(value(values, "fundamental.revenueTTM").value().decimalValue())
                .isEqualByComparingTo("1100");
        assertThat(value(values, "fundamental.revenueTTM").period()).isEqualTo("TTM");
        assertThat(value(values, "fundamental.fcfTTM").value().decimalValue()).isEqualByComparingTo("117");
        assertMissing(values, "fundamental.eps");
        assertMissing(values, "fundamental.dilutedShares");
        assertMissing(values, "fundamental.dilutedSharesBasis");
        assertThat(value(values, "fundamental.revenueGrowthYoY").value().decimalValue())
                .isEqualByComparingTo("0.1");
        assertThat(value(values, "fundamental.revenueGrowthYoY").period()).isEqualTo("TTM");
        assertMissing(values, "fundamental.ebitdaTTM");

        SERVER.verify(getRequestedFor(urlPathEqualTo("/files/company_tickers.json")));
        SERVER.verify(getRequestedFor(urlPathEqualTo("/submissions/CIK" + CIK + ".json")));
        SERVER.verify(getRequestedFor(urlPathEqualTo("/api/xbrl/companyfacts/CIK" + CIK + ".json")));
    }

    @Test
    void reusesIssuerFactsAndSubmissionsWithinTheCacheTtl() throws Exception {
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(
                filing("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31", "2026-04-29T12:00:00Z", 2026, "Q2"))),
                quarterlyFacts());
        var provider = provider();

        provider.fetch(new ProviderRequest("XYZ", Map.of()));
        provider.fetch(new ProviderRequest("XYZ", Map.of()));

        SERVER.verify(1, getRequestedFor(urlPathEqualTo("/files/company_tickers.json")));
        SERVER.verify(1, getRequestedFor(urlPathEqualTo("/submissions/CIK" + CIK + ".json")));
        SERVER.verify(1, getRequestedFor(urlPathEqualTo("/api/xbrl/companyfacts/CIK" + CIK + ".json")));
    }

    @Test
    void prefersCanonicalRevenueFactOverConflictingAliasesForTheSameReportedPeriod() throws Exception {
        var facts = quarterlyFacts();
        var gaap = (ObjectNode) facts.path("facts").path("us-gaap");
        var conflictingAlias = (ObjectNode) gaap.path("RevenueFromContractWithCustomerExcludingAssessedTax")
                .deepCopy();
        var rows = conflictingAlias.path("units").path(USD);
        for (var row : rows) ((ObjectNode) row).put("val", "9999");
        gaap.set("Revenues", conflictingAlias);
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(
                filing("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31", "2026-04-29T12:00:00Z", 2026, "Q2"))),
                facts);

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertThat(value(values, "fundamental.revenueTTM").value().decimalValue()).isEqualByComparingTo("1100");
        assertThat(value(values, "fundamental.revenueTTM").identifier()).contains("RevenueFromContractWithCustomer");
    }

    @Test
    void refreshesIssuerFactsAndSubmissionsAfterTheCacheTtl() throws Exception {
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(
                filing("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31", "2026-04-29T12:00:00Z", 2026, "Q2"))),
                quarterlyFacts());
        var clock = new MutableClock(OBSERVED_AT);
        var provider = provider(clock, Duration.ofMinutes(5));

        provider.fetch(new ProviderRequest("XYZ", Map.of()));
        clock.advance(Duration.ofMinutes(6));
        provider.fetch(new ProviderRequest("XYZ", Map.of()));

        SERVER.verify(2, getRequestedFor(urlPathEqualTo("/files/company_tickers.json")));
        SERVER.verify(2, getRequestedFor(urlPathEqualTo("/submissions/CIK" + CIK + ".json")));
        SERVER.verify(2, getRequestedFor(urlPathEqualTo("/api/xbrl/companyfacts/CIK" + CIK + ".json")));
    }

    @Test
    void emitsCurrentQuarterDilutedEpsAndWeightedSharesAndInstantBasicShares() throws Exception {
        var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
        rows.put("EarningsPerShareDiluted", List.of(durationFact("USD/shares", "2026-01-01", "2026-03-31",
                "2026-04-29", "10-Q", CURRENT_Q, "1.25", 2026, "Q2")));
        rows.put("WeightedAverageNumberOfDilutedSharesOutstanding", List.of(durationFact("shares",
                "2026-01-01", "2026-03-31", "2026-04-29", "10-Q", CURRENT_Q,
                "800000", 2026, "Q2")));
        rows.put("WeightedAverageNumberOfSharesOutstandingBasic", List.of(durationFact("shares",
                "2026-01-01", "2026-03-31", "2026-04-29", "10-Q", CURRENT_Q,
                "850000", 2026, "Q2")));
        var dei = Map.of("EntityCommonStockSharesOutstanding", List.of(instantFact("shares", "2026-04-20",
                "2026-04-29", "10-Q", CURRENT_Q, "300000", 2026, "Q2")));
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(
                filing("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31", "2026-04-29T12:00:00Z", 2026, "Q2"))),
                companyFacts(1234, rows, dei));

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertThat(value(values, "fundamental.eps").value().decimalValue()).isEqualByComparingTo("1.25");
        assertThat(value(values, "fundamental.eps").period()).isEqualTo("Q");
        assertThat(value(values, "fundamental.eps").identifier()).contains(CURRENT_Q);
        assertThat(value(values, "fundamental.dilutedShares").value().decimalValue()).isEqualByComparingTo("800000");
        assertThat(value(values, "fundamental.dilutedSharesBasis").value().asText())
                .isEqualTo("WEIGHTED_AVERAGE_Q");
        assertThat(value(values, "fundamental.basicShares").value().decimalValue()).isEqualByComparingTo("300000");
        assertThat(value(values, "fundamental.basicShares").asOf())
                .isEqualTo(Instant.parse("2026-04-20T00:00:00Z"));
        assertThat(value(values, "fundamental.basicSharesBasis").value().asText())
                .contains("EntityCommonStockSharesOutstanding").contains("INSTANT");
    }

    @Test
    void prefersTheNewestBasicShareInstantAndUsesDeiOnlyAsATieBreak() throws Exception {
        var rows = Map.of("CommonStockSharesOutstanding", List.of(instantFact("shares", "2026-04-25",
                "2026-04-29", "10-Q", CURRENT_Q, "350000", 2026, "Q2")));
        var dei = Map.of("EntityCommonStockSharesOutstanding", List.of(instantFact("shares", "2023-03-29",
                "2023-03-31", "10-K", "0000001234-23-000001", "250000", 2023, "FY")));
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(
                filing("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31", "2026-04-29T12:00:00Z", 2026, "Q2"))),
                companyFacts(1234, rows, dei));

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertThat(value(values, "fundamental.basicShares").value().decimalValue()).isEqualByComparingTo("350000");
        assertThat(value(values, "fundamental.basicShares").asOf())
                .isEqualTo(Instant.parse("2026-04-25T00:00:00Z"));
        assertThat(value(values, "fundamental.basicSharesBasis").value().asText())
                .contains("CommonStockSharesOutstanding");
    }

    @Test
    void prefersDeiBasicSharesWhenTheInstantDatesTie() throws Exception {
        var rows = Map.of("CommonStockSharesOutstanding", List.of(instantFact("shares", "2026-04-20",
                "2026-04-29", "10-Q", CURRENT_Q, "350000", 2026, "Q2")));
        var dei = Map.of("EntityCommonStockSharesOutstanding", List.of(instantFact("shares", "2026-04-20",
                "2026-04-29", "10-Q", CURRENT_Q, "300000", 2026, "Q2")));
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(
                filing("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31", "2026-04-29T12:00:00Z", 2026, "Q2"))),
                companyFacts(1234, rows, dei));

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertThat(value(values, "fundamental.basicShares").value().decimalValue()).isEqualByComparingTo("300000");
        assertThat(value(values, "fundamental.basicSharesBasis").value().asText())
                .contains("EntityCommonStockSharesOutstanding");
    }

    @Test
    void preservesQuarterEpsWhenDilutedSharesAreMissing() throws Exception {
        var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
        rows.put("EarningsPerShareDiluted", List.of(durationFact("USD/shares", "2026-01-01", "2026-03-31",
                "2026-04-29", "10-Q", CURRENT_Q, "1.25", 2026, "Q2")));
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(
                filing("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31", "2026-04-29T12:00:00Z", 2026, "Q2"))),
                companyFacts(1234, rows));

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertThat(value(values, "fundamental.eps").value().decimalValue()).isEqualByComparingTo("1.25");
        assertThat(value(values, "fundamental.eps").period()).isEqualTo("Q");
        assertMissing(values, "fundamental.dilutedShares");
        assertMissing(values, "fundamental.dilutedSharesBasis");
    }

    @Test
    void calculatesEbitdaOnlyFromFourAlignedGaapQuartersAndExposesItsFormulaAndInputs() throws Exception {
        var q2 = "0000001234-25-000002";
        var q3 = "0000001234-25-000003";
        var q4 = "0000001234-25-000004";
        var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
        rows.put("RevenueFromContractWithCustomerExcludingAssessedTax", List.of(
                durationFact(USD, "2026-01-01", "2026-03-31", "2026-04-29", "10-Q", CURRENT_Q,
                        "400", 2026, "Q1")));
        rows.put("NetIncomeLoss", List.of(
                durationFact(USD, "2025-04-01", "2025-06-30", "2025-08-01", "10-Q", q2, "100", 2025, "Q2"),
                durationFact(USD, "2025-07-01", "2025-09-30", "2025-11-01", "10-Q", q3, "110", 2025, "Q3"),
                durationFact(USD, "2025-10-01", "2025-12-31", "2026-02-01", "10-Q", q4, "120", 2026, "Q1"),
                durationFact(USD, "2026-01-01", "2026-03-31", "2026-04-29", "10-Q", CURRENT_Q, "130", 2026, "Q1")));
        rows.put("IncomeTaxExpenseBenefit", List.of(
                durationFact(USD, "2025-04-01", "2025-06-30", "2025-08-01", "10-Q", q2, "20", 2025, "Q2"),
                durationFact(USD, "2025-07-01", "2025-09-30", "2025-11-01", "10-Q", q3, "20", 2025, "Q3"),
                durationFact(USD, "2025-10-01", "2025-12-31", "2026-02-01", "10-Q", q4, "20", 2026, "Q1"),
                durationFact(USD, "2026-01-01", "2026-03-31", "2026-04-29", "10-Q", CURRENT_Q, "20", 2026, "Q1")));
        rows.put("InterestExpenseNonoperating", List.of(
                durationFact(USD, "2025-04-01", "2025-06-30", "2025-08-01", "10-Q", q2, "10", 2025, "Q2"),
                durationFact(USD, "2025-07-01", "2025-09-30", "2025-11-01", "10-Q", q3, "10", 2025, "Q3"),
                durationFact(USD, "2025-10-01", "2025-12-31", "2026-02-01", "10-Q", q4, "10", 2026, "Q1"),
                durationFact(USD, "2026-01-01", "2026-03-31", "2026-04-29", "10-Q", CURRENT_Q, "10", 2026, "Q1")));
        rows.put("DepreciationAndAmortization", List.of(
                durationFact(USD, "2025-04-01", "2025-06-30", "2025-08-01", "10-Q", q2, "30", 2025, "Q2"),
                durationFact(USD, "2025-07-01", "2025-09-30", "2025-11-01", "10-Q", q3, "30", 2025, "Q3"),
                durationFact(USD, "2025-10-01", "2025-12-31", "2026-02-01", "10-Q", q4, "30", 2026, "Q1"),
                durationFact(USD, "2026-01-01", "2026-03-31", "2026-04-29", "10-Q", CURRENT_Q, "30", 2026, "Q1")));
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(
                filing("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31", "2026-04-29T12:00:00Z", 2026, "Q1"))),
                companyFacts(1234, rows));

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertThat(value(values, "fundamental.ebitdaTTM").value().decimalValue()).isEqualByComparingTo("700");
        assertThat(value(values, "fundamental.ebitdaTTM").period()).isEqualTo("TTM");
        assertThat(value(values, "fundamental.ebitdaTTMType").value().asText())
                .isEqualTo("CALCULATED");
        assertThat(value(values, "fundamental.ebitdaTTMFormula").value().asText())
                .contains("GAAP net income").contains("four contiguous quarters");
        assertThat(value(values, "fundamental.ebitdaTTM").identifier())
                .contains("NetIncomeLoss").contains("InterestExpenseNonoperating")
                .contains("DepreciationAndAmortization");
    }

    @Test
    void derivesFourthQuarterEbitdaFromNineMonthYtdAndAnnualFacts() throws Exception {
        var q1 = "0000001234-25-000001";
        var q2 = "0000001234-25-000002";
        var q3 = "0000001234-25-000003";
        var annual = "0000001234-26-000100";
        var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
        rows.put("RevenueFromContractWithCustomerExcludingAssessedTax", List.of(durationFact(USD,
                "2025-01-01", "2025-12-31", "2026-02-15", "10-K", annual, "500", 2025, "FY")));
        addAnnualYtdComponent(rows, "NetIncomeLoss", "10", "30", "60", "100", q1, q2, q3, annual);
        addAnnualYtdComponent(rows, "IncomeTaxExpenseBenefit", "1", "3", "6", "10", q1, q2, q3, annual);
        addAnnualYtdComponent(rows, "InterestExpenseNonoperating", "2", "5", "9", "14",
                q1, q2, q3, annual);
        addAnnualYtdComponent(rows, "DepreciationAndAmortization", "3", "7", "12", "18",
                q1, q2, q3, annual);
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(
                        filing("10-K", annual, "2026-02-15", "2025-12-31", "2026-02-15T12:00:00Z", 2025, "FY"))),
                companyFacts(1234, rows));

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertThat(value(values, "fundamental.ebitdaTTM").value().decimalValue()).isEqualByComparingTo("142");
        assertThat(value(values, "fundamental.ebitdaTTM").period()).isEqualTo("TTM");
        assertThat(value(values, "fundamental.ebitdaTTMType").value().asText()).isEqualTo("CALCULATED");
        assertThat(value(values, "fundamental.ebitdaTTMFormula").value().asText())
                .contains("four contiguous quarters");
    }

    @Test
    void prefersDirectDaAndFillsMissingQuarterWithAlignedDepreciationAndIntangibleAmortization() throws Exception {
        var rows = alignedEbitdaBaseRows();
        rows.put("DepreciationDepletionAndAmortization", List.of(durationFact(USD,
                "2026-01-01", "2026-03-31", "2026-04-29", "10-Q", CURRENT_Q,
                "150", 2026, "Q1")));
        rows.put("Depreciation", List.of(
                durationFact(USD, "2025-04-01", "2025-06-30", "2025-08-01", "10-Q",
                        "0000001234-25-000002", "0", 2025, "Q2"),
                durationFact(USD, "2025-07-01", "2025-09-30", "2025-11-01", "10-Q",
                        "0000001234-25-000003", "100", 2025, "Q3"),
                durationFact(USD, "2025-10-01", "2025-12-31", "2026-02-01", "10-Q",
                        "0000001234-25-000004", "100", 2026, "Q1"),
                durationFact(USD, "2026-01-01", "2026-03-31", "2026-04-29", "10-Q",
                        CURRENT_Q, "100", 2026, "Q1")));
        rows.put("AmortizationOfIntangibleAssets", List.of(
                durationFact(USD, "2025-04-01", "2025-06-30", "2025-08-01", "10-Q",
                        "0000001234-25-000002", "20", 2025, "Q2"),
                durationFact(USD, "2025-07-01", "2025-09-30", "2025-11-01", "10-Q",
                        "0000001234-25-000003", "20", 2025, "Q3"),
                durationFact(USD, "2025-10-01", "2025-12-31", "2026-02-01", "10-Q",
                        "0000001234-25-000004", "20", 2026, "Q1"),
                durationFact(USD, "2026-01-01", "2026-03-31", "2026-04-29", "10-Q",
                        CURRENT_Q, "20", 2026, "Q1")));
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(
                filing("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31", "2026-04-29T12:00:00Z", 2026, "Q1"))),
                companyFacts(1234, rows));

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertThat(value(values, "fundamental.ebitdaTTM").value()).isNotNull();
        assertThat(value(values, "fundamental.ebitdaTTM").value().decimalValue()).isEqualByComparingTo("990");
        assertThat(value(values, "fundamental.ebitdaTTM").identifier())
                .contains("DepreciationDepletionAndAmortization")
                .contains("Depreciation")
                .contains("AmortizationOfIntangibleAssets");
        assertThat(value(values, "fundamental.ebitdaTTMFormula").value().asText())
                .contains("us-gaap:Depreciation + us-gaap:AmortizationOfIntangibleAssets");
    }

    @Test
    void rejectsSeparateDaComponentsWhoseDurationsDoNotAlign() throws Exception {
        var rows = alignedEbitdaBaseRows();
        rows.put("Depreciation", List.of(
                durationFact(USD, "2025-04-01", "2025-06-30", "2025-08-01", "10-Q",
                        "0000001234-25-000002", "100", 2025, "Q2"),
                durationFact(USD, "2025-07-01", "2025-09-30", "2025-11-01", "10-Q",
                        "0000001234-25-000003", "100", 2025, "Q3"),
                durationFact(USD, "2025-10-01", "2025-12-31", "2026-02-01", "10-Q",
                        "0000001234-25-000004", "100", 2026, "Q1"),
                durationFact(USD, "2026-01-01", "2026-03-31", "2026-04-29", "10-Q",
                        CURRENT_Q, "100", 2026, "Q1")));
        rows.put("AmortizationOfIntangibleAssets", List.of(
                durationFact(USD, "2025-04-02", "2025-06-30", "2025-08-01", "10-Q",
                        "0000001234-25-000002", "20", 2025, "Q2"),
                durationFact(USD, "2025-07-02", "2025-09-30", "2025-11-01", "10-Q",
                        "0000001234-25-000003", "20", 2025, "Q3"),
                durationFact(USD, "2025-10-02", "2025-12-31", "2026-02-01", "10-Q",
                        "0000001234-25-000004", "20", 2026, "Q1"),
                durationFact(USD, "2026-01-02", "2026-03-31", "2026-04-29", "10-Q",
                        CURRENT_Q, "20", 2026, "Q1")));
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(
                filing("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31", "2026-04-29T12:00:00Z", 2026, "Q1"))),
                companyFacts(1234, rows));

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertMissing(values, "fundamental.ebitdaTTM");
    }

    private static LinkedHashMap<String, List<Map<String, Object>>> alignedEbitdaBaseRows() {
        var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
        rows.put("RevenueFromContractWithCustomerExcludingAssessedTax", List.of(durationFact(USD,
                "2026-01-01", "2026-03-31", "2026-04-29", "10-Q", CURRENT_Q,
                "400", 2026, "Q1")));
        rows.put("NetIncomeLoss", List.of(
                durationFact(USD, "2025-04-01", "2025-06-30", "2025-08-01", "10-Q",
                        "0000001234-25-000002", "100", 2025, "Q2"),
                durationFact(USD, "2025-07-01", "2025-09-30", "2025-11-01", "10-Q",
                        "0000001234-25-000003", "110", 2025, "Q3"),
                durationFact(USD, "2025-10-01", "2025-12-31", "2026-02-01", "10-Q",
                        "0000001234-25-000004", "120", 2026, "Q1"),
                durationFact(USD, "2026-01-01", "2026-03-31", "2026-04-29", "10-Q",
                        CURRENT_Q, "130", 2026, "Q1")));
        rows.put("IncomeTaxExpenseBenefit", List.of(
                durationFact(USD, "2025-04-01", "2025-06-30", "2025-08-01", "10-Q",
                        "0000001234-25-000002", "20", 2025, "Q2"),
                durationFact(USD, "2025-07-01", "2025-09-30", "2025-11-01", "10-Q",
                        "0000001234-25-000003", "20", 2025, "Q3"),
                durationFact(USD, "2025-10-01", "2025-12-31", "2026-02-01", "10-Q",
                        "0000001234-25-000004", "20", 2026, "Q1"),
                durationFact(USD, "2026-01-01", "2026-03-31", "2026-04-29", "10-Q",
                        CURRENT_Q, "20", 2026, "Q1")));
        rows.put("InterestExpenseNonoperating", List.of(
                durationFact(USD, "2025-04-01", "2025-06-30", "2025-08-01", "10-Q",
                        "0000001234-25-000002", "10", 2025, "Q2"),
                durationFact(USD, "2025-07-01", "2025-09-30", "2025-11-01", "10-Q",
                        "0000001234-25-000003", "10", 2025, "Q3"),
                durationFact(USD, "2025-10-01", "2025-12-31", "2026-02-01", "10-Q",
                        "0000001234-25-000004", "10", 2026, "Q1"),
                durationFact(USD, "2026-01-01", "2026-03-31", "2026-04-29", "10-Q",
                        CURRENT_Q, "10", 2026, "Q1")));
        return rows;
    }

    private static void addAnnualYtdComponent(Map<String, List<Map<String, Object>>> rows, String concept,
                                              String q1Value, String q2YtdValue, String q3YtdValue,
                                              String annualValue, String q1, String q2, String q3, String annual) {
        rows.put(concept, List.of(
                durationFact(USD, "2025-01-01", "2025-03-31", "2025-05-10", "10-Q", q1,
                        q1Value, 2025, "Q1"),
                durationFact(USD, "2025-01-01", "2025-06-30", "2025-08-10", "10-Q", q2,
                        q2YtdValue, 2025, "Q2"),
                durationFact(USD, "2025-01-01", "2025-09-30", "2025-11-10", "10-Q", q3,
                        q3YtdValue, 2025, "Q3"),
                durationFact(USD, "2025-01-01", "2025-12-31", "2026-02-15", "10-K", annual,
                        annualValue, 2025, "FY")));
    }

    @Test
    void companyFactsKeepsPriorityOverConflictingInlineFactForSameAccessionAndPeriod() throws Exception {
        var accession = CURRENT_Q.replace("-", "");
        var doc = inlineSharesDocument("300000");
        var path = "/Archives/edgar/data/1234/" + accession + "/example.htm";
        SERVER.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withBody(doc)));
        var filing = filingWithPrimaryDocument("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31",
                "2026-04-29T12:00:00Z", 2026, "Q2", "example.htm");
        var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
        var facts = companyFacts(1234, rows, Map.of("EntityCommonStockSharesOutstanding",
                List.of(instantFact("shares", "2026-04-20", "2026-04-29", "10-Q", CURRENT_Q,
                        "250000", 2026, "Q2"))));
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(filing)), facts);

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertThat(value(values, "fundamental.basicShares").value().decimalValue()).isEqualByComparingTo("250000");
        assertThat(value(values, "fundamental.basicShares").identifier()).contains(CURRENT_Q);
        SERVER.verify(getRequestedFor(urlPathEqualTo(path)));
    }

    @Test
    void optionalInline429KeepsCompanyFactsAndCachesFailureDuringCooldown() throws Exception {
        var accession = CURRENT_Q.replace("-", "");
        var path = "/Archives/edgar/data/1234/" + accession + "/example.htm";
        SERVER.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(429)
                .withHeader("Retry-After", "1")));
        var filing = filingWithPrimaryDocument("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31",
                "2026-04-29T12:00:00Z", 2026, "Q2", "example.htm");
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(filing)), quarterlyFacts());
        var provider = provider();

        var first = provider.fetch(new ProviderRequest("XYZ", Map.of()));
        var second = provider.fetch(new ProviderRequest("XYZ", Map.of()));

        assertThat(value(first, "fundamental.cash").value().decimalValue()).isEqualByComparingTo("120");
        assertThat(value(first, "fundamental.revenueTTM").value().decimalValue()).isEqualByComparingTo("1100");
        assertThat(value(first, "fundamental.ebitdaTTM").missingData()).contains("INLINE_XBRL_HTTP_429");
        assertThat(value(second, "fundamental.cash").value().decimalValue()).isEqualByComparingTo("120");
        SERVER.verify(1, getRequestedFor(urlPathEqualTo(path)));
    }

    @Test
    void optionalInlineHttpFailureUsesSanitizedReasonAndPreservesCompanyFacts() throws Exception {
        var accession = CURRENT_Q.replace("-", "");
        var path = "/Archives/edgar/data/1234/" + accession + "/example.htm";
        SERVER.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(500)
                .withBody("private upstream error body")));
        var filing = filingWithPrimaryDocument("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31",
                "2026-04-29T12:00:00Z", 2026, "Q2", "example.htm");
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(filing)), quarterlyFacts());

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertThat(value(values, "fundamental.cash").value().decimalValue()).isEqualByComparingTo("120");
        assertThat(value(values, "fundamental.revenueTTM").value().decimalValue()).isEqualByComparingTo("1100");
        assertThat(value(values, "fundamental.ebitdaTTM").missingData()).contains("INLINE_XBRL_HTTP_500")
                .doesNotContain("private upstream error body");
    }

    @Test
    void optionalInlineParseFailureUsesSanitizedReasonAndPreservesCompanyFacts() throws Exception {
        var accession = CURRENT_Q.replace("-", "");
        var path = "/Archives/edgar/data/1234/" + accession + "/example.htm";
        SERVER.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withBody("<html><body>")));
        var filing = filingWithPrimaryDocument("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31",
                "2026-04-29T12:00:00Z", 2026, "Q2", "example.htm");
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(filing)), quarterlyFacts());

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertThat(value(values, "fundamental.cash").value().decimalValue()).isEqualByComparingTo("120");
        assertThat(value(values, "fundamental.revenueTTM").value().decimalValue()).isEqualByComparingTo("1100");
        assertThat(value(values, "fundamental.ebitdaTTM").missingData()).contains("INLINE_XBRL_PARSE_FAILED");
    }

    @Test
    void backfillsMissingBasicSharesFromIssuerMatchedInlineXbrl() throws Exception {
        var accession = CURRENT_Q.replace("-", "");
        var doc = inlineSharesDocument("300000");
        var path = "/Archives/edgar/data/1234/" + accession + "/example.htm";
        SERVER.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withBody(doc)));
        var filing = filingWithPrimaryDocument("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31",
                "2026-04-29T12:00:00Z", 2026, "Q2", "example.htm");
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(filing)), quarterlyFacts());

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertThat(value(values, "fundamental.basicShares").value().decimalValue()).isEqualByComparingTo("300000");
        assertThat(value(values, "fundamental.basicShares").identifier()).contains("{http://xbrl.sec.gov/dei/2025}")
                .contains("SHARES").contains(CURRENT_Q);
        SERVER.verify(getRequestedFor(urlPathEqualTo(path)));
    }

    @Test
    void includesVerifiedSecuritizationBorrowingsWithLongTermDebtWithoutDoubleCounting() throws Exception {
        var taxonomy = new SecInlineXbrlParser().parseTaxonomy(securitizationDebtSchema(),
                securitizationDebtLabels());
        assertThat(taxonomy.get(new QName("https://example.test/lunr/2026", "SecuritizationLiabilityCurrent")))
                .isNotNull();
        var facts = (ObjectNode) quarterlyFacts();
        ((ObjectNode) facts.path("facts").path("us-gaap")).remove("DebtCurrent");
        var filing = filingWithPrimaryDocument("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31",
                "2026-04-29T12:00:00Z", 2026, "Q1", "example.htm");
        stubSecuritizationDebtFiling(filing, true, false, false);
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(filing)), facts);

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        var debt = value(values, "fundamental.debt");
        SERVER.verify(getRequestedFor(urlPathEqualTo("/Archives/edgar/data/1234/"
                + CURRENT_Q.replace("-", "") + "/lunr.xsd")));
        SERVER.verify(getRequestedFor(urlPathEqualTo("/Archives/edgar/data/1234/"
                + CURRENT_Q.replace("-", "") + "/lunr-lab.xml")));
        assertThat(debt.value()).as("missingData=%s", debt.missingData()).isNotNull();
        assertThat(debt.value().decimalValue()).isEqualByComparingTo("185");
        assertThat(debt.asOf()).isEqualTo(Instant.parse("2026-03-31T00:00:00Z"));
        assertThat(debt.identifier()).contains("LongTermDebtNoncurrent")
                .contains("{https://example.test/lunr/2026}SecuritizationLiability")
                .doesNotContain("SecuritizationLiabilityCurrent+");
    }

    @Test
    void ignoresStaleCurrentDebtWhenJoiningVerifiedSecuritizationWithLatestLongTermDebt() throws Exception {
        var facts = (ObjectNode) alignedDebtAndEbitdaFacts();
        var gaap = (ObjectNode) facts.path("facts").path("us-gaap");
        gaap.remove("DebtCurrent");
        appendInstantFact(gaap, "LongTermDebtCurrent", "2024-12-31", "2025-03-25", "10-K",
                "0000001234-25-000005", 10, 2025, "FY");
        var filing = filingWithPrimaryDocument("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31",
                "2026-04-29T12:00:00Z", 2026, "Q1", "example.htm");
        stubSecuritizationDebtFiling(filing, true, false, false);
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(filing)), facts);

        var debt = value(provider().fetch(new ProviderRequest("XYZ", Map.of())), "fundamental.debt");

        assertThat(debt.value()).isNotNull();
        assertThat(debt.value().decimalValue()).isEqualByComparingTo("185");
        assertThat(debt.asOf()).isEqualTo(Instant.parse("2026-03-31T00:00:00Z"));
        assertThat(debt.identifier()).contains("LongTermDebtNoncurrent")
                .contains("{https://example.test/lunr/2026}SecuritizationLiability");
        SERVER.verify(1, getRequestedFor(urlPathEqualTo("/Archives/edgar/data/1234/"
                + CURRENT_Q.replace("-", "") + "/example.htm")));
    }

    @Test
    void requiresCurrentAndNoncurrentDebtComponentsAtTheSelectedReportDate() throws Exception {
        var completeDebt = SecCompanyFactsProvider.class.getDeclaredMethod(
                "hasCompleteDebtForReportDate", tools.jackson.databind.JsonNode.class,
                LocalDate.class, LocalDate.class);
        completeDebt.setAccessible(true);
        var provider = provider();
        var reportDate = LocalDate.parse("2026-03-31");
        var cutoff = LocalDate.parse("2026-04-29");
        var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
        rows.put("DebtCurrent", List.of(instantFact(USD, reportDate.toString(), cutoff.toString(),
                "10-Q", CURRENT_Q, BigDecimal.ONE, 2026, "Q1")));
        rows.put("LongTermDebtNoncurrent", List.of(instantFact(USD, reportDate.toString(), cutoff.toString(),
                "10-Q", CURRENT_Q, BigDecimal.ONE, 2026, "Q1")));
        var facts = MAPPER.readTree(MAPPER.writeValueAsString(companyFacts(1234, rows)));
        var usGaap = facts.path("facts").path("us-gaap");

        assertThat((Boolean) completeDebt.invoke(provider, usGaap, reportDate, cutoff)).isTrue();

        var staleFacts = (ObjectNode) facts.deepCopy();
        var staleNoncurrent = (ObjectNode) staleFacts.path("facts").path("us-gaap")
                .path("LongTermDebtNoncurrent").path("units").path(USD).path(0);
        staleNoncurrent.put("end", "2025-12-31");
        assertThat((Boolean) completeDebt.invoke(provider,
                staleFacts.path("facts").path("us-gaap"), reportDate, cutoff)).isFalse();
    }

    @Test
    void keepsSameDateCurrentDebtAndDoesNotUseExtendedJoin() throws Exception {
        var facts = (ObjectNode) quarterlyFacts();
        var gaap = (ObjectNode) facts.path("facts").path("us-gaap");
        gaap.remove("DebtCurrent");
        appendInstantFact(gaap, "LongTermDebtCurrent", "2026-03-31", "2026-04-29", "10-Q",
                CURRENT_Q, 40, 2026, "Q1");
        var filing = filingWithPrimaryDocument("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31",
                "2026-04-29T12:00:00Z", 2026, "Q1", "example.htm");
        stubSecuritizationDebtFiling(filing, true, false, false);
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(filing)), facts);

        var debt = value(provider().fetch(new ProviderRequest("XYZ", Map.of())), "fundamental.debt");

        assertThat(debt.value()).isNotNull();
        assertThat(debt.value().decimalValue()).isEqualByComparingTo("190");
        assertThat(debt.identifier()).contains("LongTermDebtCurrent")
                .contains("LongTermDebtNoncurrent")
                .doesNotContain("SecuritizationLiability");
    }

    @Test
    void newerCurrentDebtBlocksOlderExtendedDebtJoin() throws Exception {
        var facts = (ObjectNode) quarterlyFacts();
        var gaap = (ObjectNode) facts.path("facts").path("us-gaap");
        gaap.remove("DebtCurrent");
        appendInstantFact(gaap, "LongTermDebtCurrent", "2026-04-30", "2026-05-15", "10-Q",
                "0000001234-26-000020", 40, 2026, "Q2");
        var filing = filingWithPrimaryDocument("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31",
                "2026-04-29T12:00:00Z", 2026, "Q1", "example.htm");
        stubSecuritizationDebtFiling(filing, true, false, false);
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(filing)), facts);

        var debt = value(provider().fetch(new ProviderRequest("XYZ", Map.of())), "fundamental.debt");

        assertThat(debt.value()).isNull();
    }

    @Test
    void sumsAlignedCurrentAndNoncurrentSecuritizationFactsWhenTotalIsAbsent() throws Exception {
        var facts = (ObjectNode) quarterlyFacts();
        ((ObjectNode) facts.path("facts").path("us-gaap")).remove("DebtCurrent");
        var filing = filingWithPrimaryDocument("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31",
                "2026-04-29T12:00:00Z", 2026, "Q1", "example.htm");
        stubSecuritizationDebtFiling(filing, false, false, false);
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(filing)), facts);

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertThat(value(values, "fundamental.debt").value().decimalValue()).isEqualByComparingTo("185");
        assertThat(value(values, "fundamental.debt").identifier())
                .contains("SecuritizationLiabilityCurrent")
                .contains("SecuritizationLiabilityNoncurrent");
    }

    @Test
    void doesNotUseAStaleComparativeZeroDebtStatementToCompleteCurrentTotalDebt() throws Exception {
        var facts = (ObjectNode) quarterlyFacts();
        ((ObjectNode) facts.path("facts").path("us-gaap")).remove("DebtCurrent");
        var filing = filingWithPrimaryDocument("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31",
                "2026-04-29T12:00:00Z", 2026, "Q1", "example.htm");
        stubSecuritizationDebtFiling(filing, true, false, false, true);
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(filing)), facts);

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertThat(value(values, "fundamental.debt").value()).isNull();
        assertThat(value(values, "fundamental.debt").missingData()).contains("INLINE_XBRL_NO_VERIFIED_FACTS");
    }

    @Test
    void doesNotCombineSecuritizationDebtFromDifferentDatesOrDimensionalContexts() throws Exception {
        for (var mismatchedDate : List.of(true, false)) {
            SERVER.resetAll();
            var facts = (ObjectNode) quarterlyFacts();
            ((ObjectNode) facts.path("facts").path("us-gaap")).remove("DebtCurrent");
            var filing = filingWithPrimaryDocument("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31",
                    "2026-04-29T12:00:00Z", 2026, "Q1", "example.htm");
            stubSecuritizationDebtFiling(filing, false, mismatchedDate, !mismatchedDate);
            stubCompany("XYZ", submissions("XYZ", 1234, List.of(filing)), facts);

            var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

            assertThat(value(values, "fundamental.debt").value()).isNull();
        }
    }

    private static void stubSecuritizationDebtFiling(Map<String, Object> filing, boolean includeTotal,
                                                     boolean mismatchedDate, boolean dimensional) {
        stubSecuritizationDebtFiling(filing, includeTotal, mismatchedDate, dimensional, false);
    }

    private static void stubSecuritizationDebtFiling(Map<String, Object> filing, boolean includeTotal,
                                                     boolean mismatchedDate, boolean dimensional,
                                                     boolean staleStifelDisclosure) {
        var accession = ((String) filing.get("accessionNumber")).replace("-", "");
        var folder = "/Archives/edgar/data/1234/" + accession + "/";
        SERVER.stubFor(get(urlPathEqualTo(folder + "example.htm"))
                .willReturn(aResponse().withBody(securitizationDebtDocument(includeTotal, mismatchedDate,
                        dimensional, staleStifelDisclosure))));
        SERVER.stubFor(get(urlPathEqualTo(folder + "lunr.xsd"))
                .willReturn(aResponse().withBody(securitizationDebtSchema())));
        SERVER.stubFor(get(urlPathEqualTo(folder + "lunr-lab.xml"))
                .willReturn(aResponse().withBody(securitizationDebtLabels())));
    }

    private static String securitizationDebtDocument(boolean includeTotal, boolean mismatchedDate,
                                                     boolean dimensional, boolean staleStifelDisclosure) {
        var end = mismatchedDate ? "2026-02-28" : "2026-03-31";
        var segment = dimensional ? "<xbrli:segment><xbrldi:explicitMember dimension=\"lunr:DebtTypeAxis\">"
                + "lunr:SecuredBorrowingMember</xbrldi:explicitMember></xbrli:segment>" : "";
        var total = includeTotal ? "<ix:nonFraction name=\"lunr:SecuritizationLiability\" contextRef=\"DEBT\" "
                + "unitRef=\"USD\" format=\"ixt:num-dot-decimal\">35</ix:nonFraction>" : "";
        var stifelDisclosure = staleStifelDisclosure
                ? "<p>As of December 31, 2025, there was no outstanding debt under the Stifel Loan Agreement.</p>"
                : "<p>As of March <span>31</span>, <strong>2026</strong>, there was no outstanding debt under "
                + "the Stifel Loan Agreement.</p>";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ix="http://www.xbrl.org/2013/inlineXBRL"
                      xmlns:link="http://www.xbrl.org/2003/linkbase"
                      xmlns:xlink="http://www.w3.org/1999/xlink"
                      xmlns:xbrli="http://www.xbrl.org/2003/instance"
                      xmlns:xbrldi="http://xbrl.org/2006/xbrldi"
                      xmlns:lunr="https://example.test/lunr/2026"
                      xmlns:iso4217="http://www.xbrl.org/2003/iso4217"
                      xmlns:ixt="http://www.xbrl.org/inlineXBRL/transformation/2015-02-26">
                  <ix:header><ix:references><link:schemaRef xlink:type="simple" xlink:href="lunr.xsd"/></ix:references>
                    <ix:resources>
                      <xbrli:context id="DEBT"><xbrli:entity>
                        <xbrli:identifier scheme="http://www.sec.gov/CIK">0000001234</xbrli:identifier>
                        %s
                      </xbrli:entity><xbrli:period><xbrli:instant>%s</xbrli:instant></xbrli:period></xbrli:context>
                      <xbrli:unit id="USD"><xbrli:measure>iso4217:USD</xbrli:measure></xbrli:unit>
                    </ix:resources>
                  </ix:header>
                  <body>
                    <p>Our securitization liabilities are accounted for as secured borrowings.</p>
                    <p>The following table summarizes our outstanding debt.</p>
                    %s
                    <p>Our convertible notes are included in long-term debt.</p>
                    <ix:nonFraction name="lunr:SecuritizationLiabilityCurrent" contextRef="DEBT"
                      unitRef="USD" format="ixt:num-dot-decimal">15</ix:nonFraction>
                    <ix:nonFraction name="lunr:SecuritizationLiabilityNoncurrent" contextRef="DEBT"
                      unitRef="USD" format="ixt:num-dot-decimal">20</ix:nonFraction>
                    %s
                    <ix:nonFraction name="lunr:SecuritizationAsset" contextRef="DEBT"
                      unitRef="USD" format="ixt:num-dot-decimal">900</ix:nonFraction>
                  </body>
                </html>
                """.formatted(segment, end, stifelDisclosure, total);
    }

    private static String securitizationDebtSchema() {
        return """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                    xmlns:link="http://www.xbrl.org/2003/linkbase"
                    xmlns:xlink="http://www.w3.org/1999/xlink"
                    targetNamespace="https://example.test/lunr/2026">
                  <xs:element id="lunr_SecuritizationLiabilityCurrent" name="SecuritizationLiabilityCurrent"/>
                  <xs:element id="lunr_SecuritizationLiabilityNoncurrent" name="SecuritizationLiabilityNoncurrent"/>
                  <xs:element id="lunr_SecuritizationLiability" name="SecuritizationLiability"/>
                  <xs:element id="lunr_SecuritizationAsset" name="SecuritizationAsset"/>
                  <link:linkbaseRef xlink:type="simple" xlink:role="http://www.xbrl.org/2003/role/labelLinkbaseRef"
                    xlink:href="lunr-lab.xml"/>
                </xs:schema>
                """;
    }

    private static String securitizationDebtLabels() {
        return """
                <link:linkbase xmlns:link="http://www.xbrl.org/2003/linkbase"
                    xmlns:xlink="http://www.w3.org/1999/xlink">
                  <link:labelLink xlink:type="extended" xlink:role="http://www.xbrl.org/2003/role/label">
                    %s
                  </link:labelLink>
                </link:linkbase>
                """.formatted(String.join("", List.of(
                taxonomyDocumentation("SecuritizationLiabilityCurrent", "Securitization Liability, Current"),
                taxonomyDocumentation("SecuritizationLiabilityNoncurrent", "Securitization Liability, Noncurrent"),
                taxonomyDocumentation("SecuritizationLiability", "Securitization Liability"),
                taxonomyDocumentation("SecuritizationAsset", "Securitization Asset"))));
    }

    private static String taxonomyDocumentation(String concept, String text) {
        var locator = "concept_" + concept;
        var resource = "documentation_" + concept;
        return "<link:loc xlink:type=\"locator\" xlink:href=\"lunr.xsd#lunr_" + concept
                + "\" xlink:label=\"" + locator + "\"/>"
                + "<link:label xlink:type=\"resource\" xlink:label=\"" + resource
                + "\" xlink:role=\"http://www.xbrl.org/2003/role/documentation\" xml:lang=\"en-US\">"
                + text + "</link:label>"
                + "<link:labelArc xlink:type=\"arc\" xlink:from=\"" + locator + "\" xlink:to=\""
                + resource + "\"/>";
    }

    @Test
    void exposesDilutedEpsAndWeightedSharesOnlyForTheSelectedCompletedAnnualFiling() throws Exception {
        var accession = CURRENT_K;
        var filed = "2025-11-10";
        var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
        rows.put("CashAndCashEquivalentsAtCarryingValue", List.of(instantFact(USD, "2025-09-30", filed,
                "10-K", accession, 50, 2025, "FY")));
        rows.put("DebtCurrent", List.of(instantFact(USD, "2025-09-30", filed, "10-K", accession, 10, 2025, "FY")));
        rows.put("LongTermDebtNoncurrent", List.of(instantFact(USD, "2025-09-30", filed,
                "10-K", accession, 30, 2025, "FY")));
        rows.put("EarningsPerShareDiluted", List.of(
                durationFact("USD/shares", "2024-10-01", "2025-09-30", filed, "10-K", accession, "5.2", 2025, "FY"),
                durationFact("USD/shares", "2025-04-01", "2025-06-30", "2025-08-15", "10-Q",
                        "0000001234-25-000004", "2.1", 2025, "Q3")));
        rows.put("WeightedAverageNumberOfDilutedSharesOutstanding", List.of(
                durationFact("shares", "2024-10-01", "2025-09-30", filed, "10-K", accession, "1000000", 2025, "FY"),
                durationFact("shares", "2025-04-01", "2025-06-30", "2025-08-15", "10-Q",
                        "0000001234-25-000004", "900000", 2025, "Q3")));
        rows.put("RevenueFromContractWithCustomerExcludingAssessedTax", List.of(
                durationFact(USD, "2024-10-01", "2025-09-30", filed, "10-K", accession, "1000", 2025, "FY"),
                durationFact(USD, "2023-10-01", "2024-09-30", filed, "10-K", accession, "900", 2024, "FY")));

        stubCompany("XYZ", submissions("XYZ", 1234, List.of(
                filing("10-K", accession, filed, "2025-09-30", "2025-11-10T12:00:00Z", 2025, "FY"))),
                companyFacts(1234, rows));

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertThat(value(values, "fundamental.eps").value().decimalValue()).isEqualByComparingTo("5.2");
        assertThat(value(values, "fundamental.eps").period()).isEqualTo("FY");
        assertThat(value(values, "fundamental.dilutedShares").value().decimalValue())
                .isEqualByComparingTo("1000000");
        assertThat(value(values, "fundamental.dilutedShares").unit()).isEqualTo("shares");
        assertThat(value(values, "fundamental.dilutedSharesBasis").value().asText())
                .isEqualTo("WEIGHTED_AVERAGE_FY");
        assertThat(value(values, "fundamental.revenueGrowthYoY").value().decimalValue())
                .isEqualByComparingTo(new BigDecimal("0.1111111111111111111111111111111111"));
    }

    private static String inlineSharesDocument(String shares) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ix="http://www.xbrl.org/2013/inlineXBRL"
                      xmlns:xbrli="http://www.xbrl.org/2003/instance"
                      xmlns:dei="http://xbrl.sec.gov/dei/2025"
                      xmlns:ixt="http://www.xbrl.org/inlineXBRL/transformation/2015-02-26">
                  <ix:header><ix:resources>
                    <xbrli:context id="SHARES"><xbrli:entity>
                      <xbrli:identifier scheme="http://www.sec.gov/CIK">0000001234</xbrli:identifier>
                    </xbrli:entity><xbrli:period><xbrli:instant>2026-04-20</xbrli:instant></xbrli:period></xbrli:context>
                    <xbrli:unit id="SHARES"><xbrli:measure>xbrli:shares</xbrli:measure></xbrli:unit>
                  </ix:resources></ix:header>
                  <body><ix:nonFraction name="dei:EntityCommonStockSharesOutstanding"
                    contextRef="SHARES" unitRef="SHARES" format="ixt:num-dot-decimal">%s</ix:nonFraction></body>
                </html>
                """.formatted(shares);
    }

    @Test
    void leavesAnnualRevenueGrowthMissingWhenPriorRevenueIsNotPositive() throws Exception {
        var prior = "0000001234-24-000004";
        var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
        rows.put("RevenueFromContractWithCustomerExcludingAssessedTax", List.of(
                durationFact(USD, "2024-10-01", "2025-09-30", "2025-11-10", "10-K",
                        CURRENT_K, "1000", 2025, "FY"),
                durationFact(USD, "2023-10-01", "2024-09-30", "2024-11-10", "10-K",
                        prior, "0", 2024, "FY")));
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(
                filing("10-K", CURRENT_K, "2025-11-10", "2025-09-30",
                        "2025-11-10T12:00:00Z", 2025, "FY"))), companyFacts(1234, rows));

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertThat(value(values, "fundamental.revenueTTM").value().decimalValue())
                .isEqualByComparingTo("1000");
        assertMissing(values, "fundamental.revenueGrowthYoY");
    }

    @Test
    void keepsLatestFactPeriodWhenCompanyFactsHasNotCaughtUpToLatestSubmission() throws Exception {
        var newestSubmission = "0000001234-26-000020";
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(
                filing("10-Q", newestSubmission, "2026-07-29", "2026-06-30",
                        "2026-07-29T12:00:00Z", 2026, "Q3"),
                filing("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31",
                        "2026-04-29T12:00:00Z", 2026, "Q2"))), quarterlyFacts());

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertThat(value(values, "filing.accession").value().asText()).isEqualTo(newestSubmission);
        assertThat(value(values, "fundamental.fiscalPeriod").value().asText()).isEqualTo("2026-03-31");
        assertThat(value(values, "fundamental.reportedAt").value().asText())
                .isEqualTo("2026-04-29T12:00:00Z");
        assertThat(value(values, "fundamental.revenueTTM").value().decimalValue())
                .isEqualByComparingTo("1100");
        assertThat(value(values, "fundamental.revenueTTM").identifier()).contains(CURRENT_Q);
        assertThat(value(values, "fundamental.cash").value().decimalValue()).isEqualByComparingTo("120");
        assertThat(value(values, "fundamental.cash").asOf()).isEqualTo(Instant.parse("2026-03-31T00:00:00Z"));
    }

    @Test
    void leavesQuarterlyRevenueGrowthMissingWhenPriorTtmWindowIsOutsideOneYearRange() throws Exception {
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(
                filing("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31", "2026-04-29T12:00:00Z", 2026, "Q2"),
                filing("10-Q", PRIOR_Q, "2025-04-29", "2025-02-28", "2025-04-29T12:00:00Z", 2025, "Q2"),
                filing("10-K", PRIOR_K, "2025-11-10", "2025-09-30", "2025-11-10T12:00:00Z", 2025, "FY"))),
                quarterlyFacts("2025-02-28", "2024-02-29"));

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertThat(value(values, "fundamental.revenueTTM").value().decimalValue())
                .isEqualByComparingTo("1100");
        assertMissing(values, "fundamental.revenueGrowthYoY");
    }

    @Test
    void rejectsResolverIdentityMismatchAndDoesNotFetchForIntradaySelections() throws Exception {
        stubCompany("XYZ", submissions("XYZ", 9999, List.of(
                filing("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31", "2026-04-29T12:00:00Z", 2026, "Q2"))),
                quarterlyFacts());

        assertThatThrownBy(() -> provider().fetch(new ProviderRequest("XYZ", Map.of())))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("ISSUER_MISMATCH");

        SERVER.resetAll();
        var wrongTicker = new LinkedHashMap<>(submissions("XYZ", 1234, List.of(
                filing("10-Q", CURRENT_Q, "2026-04-29", "2026-03-31", "2026-04-29T12:00:00Z", 2026, "Q2"))));
        wrongTicker.put("tickers", List.of("OTHER"));
        stubCompany("XYZ", wrongTicker, quarterlyFacts());
        assertThatThrownBy(() -> provider().fetch(new ProviderRequest("XYZ", Map.of())))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("ISSUER_MISMATCH");

        SERVER.resetAll();
        var provider = provider();
        var input = new StockAnalysisInputAssembler(new StockDataProviderRegistry(List.of(provider)), fixedClock())
                .assemble("XYZ", Map.of(), Set.of("price.latestPrice", "price.session"));
        assertThat(input.observations()).isEmpty();
        SERVER.verify(0, getRequestedFor(urlPathEqualTo("/files/company_tickers.json")));
    }

    @Test
    void doesNotReadEurFactsWhenUsdFactsAreUnavailable() throws Exception {
        var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
        rows.put("RevenueFromContractWithCustomerExcludingAssessedTax", List.of(
                durationFact("EUR", "2024-10-01", "2025-09-30", "2025-11-10", "10-K", PRIOR_K, "999", 2025, "FY")));
        var companyFacts = companyFacts(1234, rows);
        stubCompany("XYZ", submissions("XYZ", 1234, List.of(
                filing("10-K", PRIOR_K, "2025-11-10", "2025-09-30", "2025-11-10T12:00:00Z", 2025, "FY"))),
                companyFacts);

        var values = provider().fetch(new ProviderRequest("XYZ", Map.of()));

        assertMissing(values, "fundamental.revenueTTM");
    }

    private static SecCompanyFactsProvider provider() {
        return provider(fixedClock(), Duration.ofHours(6));
    }

    private static SecCompanyFactsProvider provider(Clock clock, Duration cacheTtl) {
        var configuration = new StockAnalysisProviderProperties.ProviderConfiguration(
                true, false, URI.create(SERVER.baseUrl()), "/", "", "", "", Map.of(), Set.of(),
                "test@example.com", Map.of(), Map.of(), Map.of(), Map.of(), "INSTANT",
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0, Duration.ZERO,
                100, Duration.ofSeconds(1), "", Map.of());
        var base = URI.create(SERVER.baseUrl());
        var nanos = new java.util.concurrent.atomic.AtomicLong();
        var limiter = new ProviderRateLimiter(StockDataProviderId.SEC,
                configuration.transportPolicy(), nanos::get, nanos::addAndGet);
        return new SecCompanyFactsProvider(configuration, MAPPER, clock,
                base.resolve("/files/company_tickers.json"), base, cacheTtl, limiter);
    }

    private static Clock fixedClock() {
        return Clock.fixed(OBSERVED_AT, ZoneOffset.UTC);
    }

    private static void stubCompany(String ticker, Map<String, Object> submissions,
                                    tools.jackson.databind.JsonNode facts) throws Exception {
        var tickerMap = Map.of("0", Map.of("cik_str", 1234, "ticker", ticker, "title", "Example Co"));
        SERVER.stubFor(get(urlPathEqualTo("/files/company_tickers.json"))
                .willReturn(aResponse().withBody(MAPPER.writeValueAsString(tickerMap))));
        SERVER.stubFor(get(urlPathEqualTo("/submissions/CIK" + CIK + ".json"))
                .willReturn(aResponse().withBody(MAPPER.writeValueAsString(submissions))));
        SERVER.stubFor(get(urlPathEqualTo("/api/xbrl/companyfacts/CIK" + CIK + ".json"))
                .willReturn(aResponse().withBody(MAPPER.writeValueAsString(facts))));
    }

    private static Map<String, Object> submissions(String ticker, int cik, List<Map<String, Object>> filings) {
        var recent = new LinkedHashMap<String, Object>();
        recent.put("form", filings.stream().map(row -> row.get("form")).toList());
        recent.put("accessionNumber", filings.stream().map(row -> row.get("accessionNumber")).toList());
        recent.put("filingDate", filings.stream().map(row -> row.get("filingDate")).toList());
        recent.put("reportDate", filings.stream().map(row -> row.get("reportDate")).toList());
        recent.put("acceptanceDateTime", filings.stream().map(row -> row.get("acceptanceDateTime")).toList());
        recent.put("fy", filings.stream().map(row -> row.get("fy")).toList());
        recent.put("fp", filings.stream().map(row -> row.get("fp")).toList());
        recent.put("primaryDocument", filings.stream().map(row -> row.get("primaryDocument")).toList());
        return Map.of("cik", cik, "tickers", List.of(ticker), "filings", Map.of("recent", recent));
    }

    private static Map<String, Object> filing(String form, String accession, String filed, String reportDate,
                                              String acceptance, int fy, String fp) {
        return Map.of("form", form, "accessionNumber", accession, "filingDate", filed,
                "reportDate", reportDate, "acceptanceDateTime", acceptance, "fy", fy, "fp", fp);
    }

    private static Map<String, Object> filingWithPrimaryDocument(String form, String accession, String filed,
                                                                 String reportDate, String acceptance, int fy,
                                                                 String fp, String primaryDocument) {
        var filing = new LinkedHashMap<>(filing(form, accession, filed, reportDate, acceptance, fy, fp));
        filing.put("primaryDocument", primaryDocument);
        return filing;
    }

    private static tools.jackson.databind.JsonNode quarterlyFacts() {
        return quarterlyFacts("2025-03-31", "2024-03-31");
    }

    private static tools.jackson.databind.JsonNode quarterlyFacts(String priorQuarterEnd,
                                                                   String priorComparableEnd) {
        var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
        rows.put("CashAndCashEquivalentsAtCarryingValue", List.of(instantFact(USD,
                "2026-03-31", "2026-04-29", "10-Q", CURRENT_Q, 120, 2026, "Q2")));
        rows.put("DebtCurrent", List.of(instantFact(USD, "2026-03-31", "2026-04-29", "10-Q", CURRENT_Q, 50, 2026, "Q2")));
        rows.put("LongTermDebtNoncurrent", List.of(instantFact(USD, "2026-03-31", "2026-04-29", "10-Q", CURRENT_Q, 150, 2026, "Q2")));
        rows.put("RevenueFromContractWithCustomerExcludingAssessedTax", List.of(
                durationFact(USD, "2023-10-01", "2024-09-30", "2024-11-10", "10-K",
                        "0000001234-24-000004", "900", 2024, "FY"),
                durationFact(USD, "2024-10-01", "2025-09-30", "2025-11-10", "10-K", PRIOR_K, "1000", 2025, "FY"),
                durationFact(USD, "2025-10-01", "2026-03-31", "2026-04-29", "10-Q", CURRENT_Q, "700", 2026, "Q2"),
                durationFact(USD, "2024-10-01", "2025-03-31", "2026-04-29", "10-Q", CURRENT_Q, "600", 2026, "Q2"),
                durationFact(USD, "2024-10-01", priorQuarterEnd, "2025-04-29", "10-Q", PRIOR_Q,
                        "600", 2025, "Q2"),
                durationFact(USD, "2023-10-01", priorComparableEnd, "2025-04-29", "10-Q", PRIOR_Q,
                        "500", 2025, "Q2")));
        rows.put("NetCashProvidedByUsedInOperatingActivities", List.of(
                durationFact(USD, "2024-10-01", "2025-09-30", "2025-11-10", "10-K", PRIOR_K, "100", 2025, "FY"),
                durationFact(USD, "2025-10-01", "2026-03-31", "2026-04-29", "10-Q", CURRENT_Q, "80", 2026, "Q2"),
                durationFact(USD, "2024-10-01", "2025-03-31", "2026-04-29", "10-Q", CURRENT_Q, "50", 2026, "Q2")));
        rows.put("PaymentsToAcquirePropertyPlantAndEquipment", List.of(
                durationFact(USD, "2024-10-01", "2025-09-30", "2025-11-10", "10-K", PRIOR_K, "10", 2025, "FY"),
                durationFact(USD, "2025-10-01", "2026-03-31", "2026-04-29", "10-Q", CURRENT_Q, "8", 2026, "Q2"),
                durationFact(USD, "2024-10-01", "2025-03-31", "2026-04-29", "10-Q", CURRENT_Q, "5", 2026, "Q2")));
        return companyFacts(1234, rows);
    }

    private static tools.jackson.databind.JsonNode alignedDebtAndEbitdaFacts() {
        var facts = (ObjectNode) companyFacts(1234, alignedEbitdaBaseRows());
        var target = (ObjectNode) facts.path("facts").path("us-gaap");
        var supplement = quarterlyFacts().path("facts").path("us-gaap");
        for (var tag : List.of("CashAndCashEquivalentsAtCarryingValue", "DebtCurrent",
                "LongTermDebtNoncurrent", "RevenueFromContractWithCustomerExcludingAssessedTax",
                "NetCashProvidedByUsedInOperatingActivities", "PaymentsToAcquirePropertyPlantAndEquipment")) {
            target.set(tag, supplement.path(tag).deepCopy());
        }
        appendDurationFact(target, "DepreciationDepletionAndAmortization", USD,
                "2025-04-01", "2025-06-30", "2025-08-01", "10-Q",
                "0000001234-25-000002", "5", 2025, "Q2");
        appendDurationFact(target, "DepreciationDepletionAndAmortization", USD,
                "2025-07-01", "2025-09-30", "2025-11-01", "10-Q",
                "0000001234-25-000003", "5", 2025, "Q3");
        appendDurationFact(target, "DepreciationDepletionAndAmortization", USD,
                "2025-10-01", "2025-12-31", "2026-02-01", "10-Q",
                "0000001234-25-000004", "5", 2026, "Q1");
        appendDurationFact(target, "DepreciationDepletionAndAmortization", USD,
                "2026-01-01", "2026-03-31", "2026-04-29", "10-Q", CURRENT_Q,
                "5", 2026, "Q1");
        appendDurationFact(target, "EarningsPerShareDiluted", "USD/shares", "2026-01-01", "2026-03-31",
                "2026-04-29", "10-Q", CURRENT_Q, "1", 2026, "Q1");
        appendDurationFact(target, "WeightedAverageNumberOfDilutedSharesOutstanding", "shares",
                "2026-01-01", "2026-03-31", "2026-04-29", "10-Q", CURRENT_Q, "100", 2026, "Q1");
        appendInstantFact(target, "CommonStockSharesOutstanding", "2026-03-31", "2026-04-29", "10-Q",
                CURRENT_Q, 100, 2026, "Q1");
        return facts;
    }

    private static tools.jackson.databind.JsonNode companyFacts(int cik,
                                                                Map<String, List<Map<String, Object>>> rows) {
        return companyFacts(cik, rows, Map.of());
    }

    private static tools.jackson.databind.JsonNode companyFacts(int cik,
                                                                Map<String, List<Map<String, Object>>> rows,
                                                                Map<String, List<Map<String, Object>>> dei) {
        var root = MAPPER.createObjectNode();
        root.put("cik", cik).put("entityName", "Example Co");
        var namespaces = root.putObject("facts");
        writeNamespace(namespaces.putObject("us-gaap"), rows);
        if (!dei.isEmpty()) writeNamespace(namespaces.putObject("dei"), dei);
        return root;
    }

    private static void writeNamespace(tools.jackson.databind.node.ObjectNode namespace,
                                       Map<String, List<Map<String, Object>>> facts) {
        facts.forEach((tag, rows) -> {
            var fact = namespace.putObject(tag);
            var units = fact.putObject("units");
            var byUnit = new LinkedHashMap<String, List<Map<String, Object>>>();
            rows.forEach(row -> byUnit.computeIfAbsent((String) row.get("unit"), ignored -> new ArrayList<>()).add(row));
            byUnit.forEach((unit, values) -> {
                var array = units.putArray(unit);
                values.forEach(row -> {
                    var item = array.addObject();
                    row.forEach((key, value) -> {
                        if (!key.equals("unit")) item.putPOJO(key, value);
                    });
                });
            });
        });
    }

    private static void appendInstantFact(ObjectNode namespace, String tag, String end, String filed,
                                          String form, String accession, Object amount, int fy, String fp) {
        var fact = namespace.get(tag) instanceof ObjectNode existing ? existing : namespace.putObject(tag);
        var units = fact.get("units") instanceof ObjectNode existingUnits
                ? existingUnits : fact.putObject("units");
        var rows = units.get(USD) instanceof tools.jackson.databind.node.ArrayNode existingRows
                ? existingRows : units.putArray(USD);
        rows.add(MAPPER.valueToTree(instantFact(USD, end, filed, form, accession, amount, fy, fp)));
    }

    private static void appendDurationFact(ObjectNode namespace, String tag, String unit, String start,
                                           String end, String filed, String form, String accession,
                                           Object amount, int fy, String fp) {
        var fact = namespace.get(tag) instanceof ObjectNode existing ? existing : namespace.putObject(tag);
        var units = fact.get("units") instanceof ObjectNode existingUnits
                ? existingUnits : fact.putObject("units");
        var rows = units.get(unit) instanceof tools.jackson.databind.node.ArrayNode existingRows
                ? existingRows : units.putArray(unit);
        rows.add(MAPPER.valueToTree(durationFact(unit, start, end, filed, form, accession, amount, fy, fp)));
    }

    private static Map<String, Object> instantFact(String unit, String end, String filed, String form,
                                                  String accession, Object value, int fy, String fp) {
        return Map.of("unit", unit, "end", end, "filed", filed, "form", form,
                "accn", accession, "val", value, "fy", fy, "fp", fp);
    }

    private static Map<String, Object> durationFact(String unit, String start, String end, String filed,
                                                   String form, String accession, Object value, int fy, String fp) {
        return Map.of("unit", unit, "start", start, "end", end, "filed", filed, "form", form,
                "accn", accession, "val", value, "fy", fy, "fp", fp);
    }

    private static final String USD = "USD";

    private static ProviderValue value(List<ProviderValue> values, String field) {
        return values.stream().filter(value -> value.field().equals(field)).findFirst().orElseThrow();
    }

    private static void assertMissing(List<ProviderValue> values, String field) {
        assertThat(value(values, field).value()).isNull();
        assertThat(value(values, field).missingData()).contains("DATA_NOT_PRESENT");
    }

    private static final class MutableClock extends Clock {
        private final java.util.concurrent.atomic.AtomicReference<Instant> instant;

        private MutableClock(Instant instant) {
            this.instant = new java.util.concurrent.atomic.AtomicReference<>(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }

        private void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
        }
    }
}
