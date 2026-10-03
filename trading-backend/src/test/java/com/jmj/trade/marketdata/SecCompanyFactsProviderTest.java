package com.jmj.trade.marketdata;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                durationFact("USD/shares", "2024-10-01", "2025-09-30", filed, "10-K", accession, "5.2", 2025, "FY")));
        rows.put("WeightedAverageNumberOfDilutedSharesOutstanding", List.of(
                durationFact("shares", "2024-10-01", "2025-09-30", filed, "10-K", accession, "1000000", 2025, "FY")));
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
        var configuration = new StockAnalysisProviderProperties.ProviderConfiguration(
                true, false, URI.create(SERVER.baseUrl()), "/", "", "", "", Map.of(), Set.of(),
                "test@example.com", Map.of(), Map.of(), Map.of(), Map.of(), "INSTANT",
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0, Duration.ZERO,
                100, Duration.ofSeconds(1), "", Map.of());
        var base = URI.create(SERVER.baseUrl());
        return new SecCompanyFactsProvider(configuration, MAPPER, fixedClock(),
                base.resolve("/files/company_tickers.json"), base);
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
        return Map.of("cik", cik, "tickers", List.of(ticker), "filings", Map.of("recent", recent));
    }

    private static Map<String, Object> filing(String form, String accession, String filed, String reportDate,
                                              String acceptance, int fy, String fp) {
        return Map.of("form", form, "accessionNumber", accession, "filingDate", filed,
                "reportDate", reportDate, "acceptanceDateTime", acceptance, "fy", fy, "fp", fp);
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

    private static tools.jackson.databind.JsonNode companyFacts(int cik,
                                                                Map<String, List<Map<String, Object>>> rows) {
        var root = MAPPER.createObjectNode();
        root.put("cik", cik).put("entityName", "Example Co");
        writeNamespace(root.putObject("facts").putObject("us-gaap"), rows);
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
}
