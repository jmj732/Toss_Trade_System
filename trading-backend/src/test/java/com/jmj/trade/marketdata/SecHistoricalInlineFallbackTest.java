package com.jmj.trade.marketdata;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

class SecHistoricalInlineFallbackTest {

    private static final WireMockServer SERVER = new WireMockServer(options().dynamicPort());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant OBSERVED_AT = Instant.parse("2026-10-03T01:44:33Z");
    private static final String CIK = "0000001234";
    private static final String LATEST = "0000001234-26-000010";
    private static final String HISTORICAL = "0000001234-26-000009";
    private static final String HISTORICAL_ORIGINAL = "0000001234-26-000008";

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
    void backfillsMissingHistoricalInterestFromPriorFilingAndCachesBothDocuments() throws Exception {
        var latestPath = filingPath(LATEST, "latest.htm");
        var historicalPath = filingPath(HISTORICAL, "historical.htm");
        var originalPath = filingPath(HISTORICAL_ORIGINAL, "historical-original.htm");
        SERVER.stubFor(get(urlPathEqualTo(latestPath)).willReturn(aResponse().withBody(
                inlineInterestDocument("LATEST_INTEREST", "2026-01-01", "2026-03-31", "10"))));
        SERVER.stubFor(get(urlPathEqualTo(historicalPath)).willReturn(aResponse().withBody(
                inlineInterestDocument("Q4_INTEREST", "2025-10-01", "2025-12-31", "12"))));
        SERVER.stubFor(get(urlPathEqualTo(originalPath)).willReturn(aResponse().withBody(
                inlineInterestDocument("ORIGINAL_Q4_INTEREST", "2025-10-01", "2025-12-31", "99"))));
        stubCompany(submissions(), companyFacts());
        var provider = provider();

        var first = provider.fetch(new ProviderRequest("XYZ", Map.of()));
        var second = provider.fetch(new ProviderRequest("XYZ", Map.of()));

        var ebitda = value(first, "fundamental.ebitdaTTM");
        assertThat(ebitda.value()).isNotNull();
        assertThat(ebitda.value().decimalValue()).isEqualByComparingTo("608");
        assertThat(ebitda.period()).isEqualTo("TTM");
        assertThat(ebitda.asOf()).isEqualTo(Instant.parse("2026-03-31T00:00:00Z"));
        assertThat(value(first, "fundamental.ebitdaTTMType").value().asText()).isEqualTo("CALCULATED");
        var source = value(first, "fundamental.ebitdaTTMSource").value().asText();
        assertThat(source).contains("{" + "http://fasb.org/us-gaap/2025"
                + "}InterestExpenseNonOperating@" + HISTORICAL
                + ";start=2025-10-01;end=2025-12-31;filed=2026-02-01;form=10-Q/A;context=Q4_INTEREST");
        assertThat(value(second, "fundamental.ebitdaTTMSource").value().asText()).isEqualTo(source);

        SERVER.verify(1, getRequestedFor(urlPathEqualTo("/files/company_tickers.json")));
        SERVER.verify(1, getRequestedFor(urlPathEqualTo("/submissions/CIK" + CIK + ".json")));
        SERVER.verify(1, getRequestedFor(urlPathEqualTo("/api/xbrl/companyfacts/CIK" + CIK + ".json")));
        SERVER.verify(1, getRequestedFor(urlPathEqualTo(latestPath)));
        SERVER.verify(1, getRequestedFor(urlPathEqualTo(historicalPath)));
        SERVER.verify(0, getRequestedFor(urlPathEqualTo(originalPath)));
    }

    private static SecCompanyFactsProvider provider() {
        var base = URI.create(SERVER.baseUrl());
        var configuration = new StockAnalysisProviderProperties.ProviderConfiguration(
                true, false, base, "/", "", "", "", Map.of(), Set.of(), "test@example.com",
                Map.of(), Map.of(), Map.of(), Map.of(), "INSTANT", Duration.ofSeconds(1),
                Duration.ofSeconds(1), 0, Duration.ZERO, 100, Duration.ofSeconds(1), "", Map.of());
        return new SecCompanyFactsProvider(configuration, MAPPER,
                Clock.fixed(OBSERVED_AT, ZoneOffset.UTC), base.resolve("/files/company_tickers.json"), base,
                Duration.ofHours(6));
    }

    private static void stubCompany(Map<String, Object> submissions, JsonNode facts) throws Exception {
        SERVER.stubFor(get(urlPathEqualTo("/files/company_tickers.json")).willReturn(aResponse()
                .withBody(MAPPER.writeValueAsString(Map.of("0", Map.of("cik_str", 1234,
                        "ticker", "XYZ", "title", "Example Co"))))));
        SERVER.stubFor(get(urlPathEqualTo("/submissions/CIK" + CIK + ".json")).willReturn(aResponse()
                .withBody(MAPPER.writeValueAsString(submissions))));
        SERVER.stubFor(get(urlPathEqualTo("/api/xbrl/companyfacts/CIK" + CIK + ".json")).willReturn(aResponse()
                .withBody(MAPPER.writeValueAsString(facts))));
    }

    private static Map<String, Object> submissions() {
        var filings = List.of(
                filing("10-Q", LATEST, "2026-04-29", "2026-03-31", 2026, "Q1", "latest.htm"),
                filing("10-Q/A", HISTORICAL, "2026-02-01", "2025-12-31", 2026, "Q4", "historical.htm"),
                filing("10-Q", HISTORICAL_ORIGINAL, "2026-01-31", "2025-12-31", 2026, "Q4",
                        "historical-original.htm"),
                filing("10-Q", "0000001234-25-000003", "2025-11-01", "2025-09-30", 2025, "Q3", "q3.htm"),
                filing("10-Q", "0000001234-25-000002", "2025-08-01", "2025-06-30", 2025, "Q2", "q2.htm"));
        var recent = new LinkedHashMap<String, Object>();
        recent.put("form", filings.stream().map(row -> row.get("form")).toList());
        recent.put("accessionNumber", filings.stream().map(row -> row.get("accessionNumber")).toList());
        recent.put("filingDate", filings.stream().map(row -> row.get("filingDate")).toList());
        recent.put("reportDate", filings.stream().map(row -> row.get("reportDate")).toList());
        recent.put("acceptanceDateTime", filings.stream().map(row -> row.get("acceptanceDateTime")).toList());
        recent.put("fy", filings.stream().map(row -> row.get("fy")).toList());
        recent.put("fp", filings.stream().map(row -> row.get("fp")).toList());
        recent.put("primaryDocument", filings.stream().map(row -> row.get("primaryDocument")).toList());
        return Map.of("cik", 1234, "tickers", List.of("XYZ"), "filings", Map.of("recent", recent));
    }

    private static Map<String, Object> filing(String form, String accession, String filed, String reportDate,
                                               int fiscalYear, String fiscalPeriod, String primaryDocument) {
        return Map.of("form", form, "accessionNumber", accession, "filingDate", filed,
                "reportDate", reportDate, "acceptanceDateTime", filed + "T12:00:00Z", "fy", fiscalYear,
                "fp", fiscalPeriod, "primaryDocument", primaryDocument);
    }

    private static JsonNode companyFacts() {
        var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
        rows.put("CashAndCashEquivalentsAtCarryingValue", List.of(
                instantFact("USD", "2026-03-31", "2026-04-29", "10-Q", LATEST, "120", 2026, "Q1")));
        rows.put("DebtCurrent", List.of(
                instantFact("USD", "2026-03-31", "2026-04-29", "10-Q", LATEST, "50", 2026, "Q1")));
        rows.put("LongTermDebtNoncurrent", List.of(
                instantFact("USD", "2026-03-31", "2026-04-29", "10-Q", LATEST, "150", 2026, "Q1")));
        rows.put("CommonStockSharesOutstanding", List.of(
                instantFact("shares", "2026-03-31", "2026-04-29", "10-Q", LATEST, "300000", 2026, "Q1")));
        rows.put("RevenueFromContractWithCustomerExcludingAssessedTax", List.of(
                quarterFact("2026-01-01", "2026-03-31", "2026-04-29", "10-Q", LATEST, "400", 2026, "Q1")));
        rows.put("NetCashProvidedByUsedInOperatingActivities", List.of(
                quarterFact("2026-01-01", "2026-03-31", "2026-04-29", "10-Q", LATEST, "80", 2026, "Q1")));
        rows.put("PaymentsToAcquirePropertyPlantAndEquipment", List.of(
                quarterFact("2026-01-01", "2026-03-31", "2026-04-29", "10-Q", LATEST, "8", 2026, "Q1")));
        rows.put("EarningsPerShareDiluted", List.of(durationFact("USD/shares", "2026-01-01", "2026-03-31",
                "2026-04-29", "10-Q", LATEST, "1.25", 2026, "Q1")));
        rows.put("WeightedAverageNumberOfDilutedSharesOutstanding", List.of(durationFact("shares",
                "2026-01-01", "2026-03-31", "2026-04-29", "10-Q", LATEST, "800000", 2026, "Q1")));
        rows.put("NetIncomeLoss", List.of(
                quarterFact("2025-04-01", "2025-06-30", "2025-08-01", "10-Q", "0000001234-25-000002", "100", 2025, "Q2"),
                quarterFact("2025-07-01", "2025-09-30", "2025-11-01", "10-Q", "0000001234-25-000003", "110", 2025, "Q3"),
                quarterFact("2025-10-01", "2025-12-31", "2026-02-01", "10-Q/A", HISTORICAL, "120", 2026, "Q4"),
                quarterFact("2026-01-01", "2026-03-31", "2026-04-29", "10-Q", LATEST, "130", 2026, "Q1")));
        rows.put("IncomeTaxExpenseBenefit", List.of(
                quarterFact("2025-04-01", "2025-06-30", "2025-08-01", "10-Q", "0000001234-25-000002", "20", 2025, "Q2"),
                quarterFact("2025-07-01", "2025-09-30", "2025-11-01", "10-Q", "0000001234-25-000003", "20", 2025, "Q3"),
                quarterFact("2025-10-01", "2025-12-31", "2026-02-01", "10-Q/A", HISTORICAL, "20", 2026, "Q4"),
                quarterFact("2026-01-01", "2026-03-31", "2026-04-29", "10-Q", LATEST, "20", 2026, "Q1")));
        rows.put("InterestExpenseNonOperating", List.of(
                quarterFact("2025-04-01", "2025-06-30", "2025-08-01", "10-Q", "0000001234-25-000002", "10", 2025, "Q2"),
                quarterFact("2025-07-01", "2025-09-30", "2025-11-01", "10-Q", "0000001234-25-000003", "10", 2025, "Q3"),
                quarterFact("2026-01-01", "2026-03-31", "2026-04-29", "10-Q", LATEST, "10", 2026, "Q1")));
        rows.put("DepreciationDepletionAndAmortization", List.of(
                quarterFact("2025-04-01", "2025-06-30", "2025-08-01", "10-Q", "0000001234-25-000002", "5", 2025, "Q2"),
                quarterFact("2025-07-01", "2025-09-30", "2025-11-01", "10-Q", "0000001234-25-000003", "6", 2025, "Q3"),
                quarterFact("2025-10-01", "2025-12-31", "2026-02-01", "10-Q/A", HISTORICAL, "7", 2026, "Q4"),
                quarterFact("2026-01-01", "2026-03-31", "2026-04-29", "10-Q", LATEST, "8", 2026, "Q1")));
        return companyFacts(1234, rows);
    }

    private static JsonNode companyFacts(int cik, Map<String, List<Map<String, Object>>> facts) {
        var root = MAPPER.createObjectNode();
        root.put("cik", cik).put("entityName", "Example Co");
        var taxonomy = root.putObject("facts").putObject("us-gaap");
        facts.forEach((tag, rows) -> {
            var units = taxonomy.putObject(tag).putObject("units");
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
        return root;
    }

    private static Map<String, Object> instantFact(String unit, String end, String filed, String form,
                                                   String accession, String value, int fiscalYear,
                                                   String fiscalPeriod) {
        return Map.of("unit", unit, "end", end, "filed", filed, "form", form, "accn", accession,
                "val", value, "fy", fiscalYear, "fp", fiscalPeriod);
    }

    private static Map<String, Object> quarterFact(String start, String end, String filed, String form,
                                                   String accession, String value, int fiscalYear,
                                                   String fiscalPeriod) {
        return durationFact("USD", start, end, filed, form, accession, value, fiscalYear, fiscalPeriod);
    }

    private static Map<String, Object> durationFact(String unit, String start, String end, String filed,
                                                    String form, String accession, String value,
                                                    int fiscalYear, String fiscalPeriod) {
        return Map.of("unit", unit, "start", start, "end", end, "filed", filed, "form", form,
                "accn", accession, "val", value, "fy", fiscalYear, "fp", fiscalPeriod);
    }

    private static String filingPath(String accession, String document) {
        return "/Archives/edgar/data/1234/" + accession.replace("-", "") + "/" + document;
    }

    private static String inlineInterestDocument(String context, String start, String end, String value) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ix="http://www.xbrl.org/2013/inlineXBRL"
                      xmlns:xbrli="http://www.xbrl.org/2003/instance"
                      xmlns:us-gaap="http://fasb.org/us-gaap/2025"
                      xmlns:iso4217="http://www.xbrl.org/2003/iso4217"
                      xmlns:ixt="http://www.xbrl.org/inlineXBRL/transformation/2015-02-26">
                  <ix:header><ix:resources>
                    <xbrli:context id="%s"><xbrli:entity>
                      <xbrli:identifier scheme="http://www.sec.gov/CIK">0000001234</xbrli:identifier>
                    </xbrli:entity><xbrli:period><xbrli:startDate>%s</xbrli:startDate>
                      <xbrli:endDate>%s</xbrli:endDate></xbrli:period></xbrli:context>
                    <xbrli:unit id="USD"><xbrli:measure>iso4217:USD</xbrli:measure></xbrli:unit>
                  </ix:resources></ix:header>
                  <body><ix:nonFraction name="us-gaap:InterestExpenseNonOperating"
                    contextRef="%s" unitRef="USD" format="ixt:num-dot-decimal">%s</ix:nonFraction></body>
                </html>
                """.formatted(context, start, end, context, value);
    }

    private static ProviderValue value(List<ProviderValue> values, String field) {
        return values.stream().filter(value -> value.field().equals(field)).findFirst().orElseThrow();
    }
}
