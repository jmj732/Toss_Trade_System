package com.jmj.trade.analysis;

import com.jmj.trade.marketdata.StockDataProviderId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StockAnalysisCoreContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsCoreResponseWithAnalyzerProvenanceAndMissingData() throws Exception {
        var response = objectMapper.readValue("""
                {
                  "requestId":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  "schemaVersion":"1",
                  "inputSnapshotId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "symbol":"AAPL",
                  "asOf":"2026-08-02T00:00:00Z",
                  "status":"DEGRADED",
                  "missingData":["FIELD_MISSING:fundamental.equity"],
                  "observations":[],
                  "analyzers":[
                    {
                      "analyzer":"fundamental",
                      "confidence":"0.75",
                      "missingData":["FIELD_MISSING:fundamental.equity"],
                      "metrics":[{
                        "name":"fundamental.profit_margin",
                        "value":"0.25",
                        "unit":"ratio",
                        "asOf":"2026-08-02T00:00:00Z",
                        "provenance":[{
                          "provider":"SEC",
                          "field":"fundamental.net_income",
                          "asOf":"2026-08-01T20:00:00Z",
                          "collectedAt":"2026-08-02T00:00:00Z"
                        }],
                        "missingData":[]
                      }]
                    }
                  ]
                }
                """, StockAnalysisCoreContract.Response.class);

        assertThat(response.requestId())
                .isEqualTo(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        assertThat(response.analyzers()).extracting(StockAnalysisCoreContract.Analyzer::analyzer)
                .containsExactly("fundamental");
        var metric = response.analyzers().getFirst().metrics().getFirst();
        assertThat(metric.provenance().getFirst().provider()).isEqualTo(StockDataProviderId.SEC);
        assertThat(metric.missingData()).isEmpty();
    }

    @Test
    void readsPinnedDegradedV3FixtureWithAllAnalyzerMetrics() throws Exception {
        var root = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(root.resolve("contracts"))) {
            root = root.getParent();
        }
        var response = objectMapper.readValue(
                Files.readString(root.resolve("contracts/analysis/v3/stock-analysis-core-response.json")),
                StockAnalysisCoreContract.Response.class);

        assertThat(response.analyzers()).extracting(StockAnalysisCoreContract.Analyzer::analyzer)
                .containsExactlyElementsOf(StockAnalysisCoreContract.ANALYZER_ORDER);
        for (var analyzer : response.analyzers()) {
            assertThat(analyzer.metrics()).extracting(StockAnalysisCoreContract.Metric::name)
                    .containsExactlyElementsOf(StockAnalysisCoreContract.metricOrder(analyzer.analyzer()));
        }
    }

    @Test
    void readsForecastResponseWithVersionSnapshotAndProvenance() throws Exception {
        var response = objectMapper.readValue("""
                {
                  "requestId":"cccccccc-cccc-cccc-cccc-cccccccccccc",
                  "schemaVersion":"1",
                  "inputSnapshotId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "symbol":"AAPL",
                  "asOf":"2026-08-02T00:00:00Z",
                  "evaluatedAt":"2026-08-02T00:00:00Z",
                  "status":"COMPLETED",
                  "missingData":[],
                  "confidence":"1",
                  "modelVersion":"deterministic-v1",
                  "contractVersion":"forecast-v1",
                  "forecasts":[{
                    "name":"forecast.d1_up_probability",
                    "value":"0.55",
                    "unit":"probability",
                    "asOf":"2026-08-01T20:00:00Z",
                    "provenance":[{
                      "provider":"FMP",
                      "field":"quote.price",
                      "asOf":"2026-08-01T20:00:00Z",
                      "collectedAt":"2026-08-02T00:00:00Z"
                    }],
                    "missingData":[]
                  }]
                }
                """, StockForecastCoreContract.Response.class);

        assertThat(response.confidence()).isEqualByComparingTo("1");
        assertThat(response.modelVersion()).isEqualTo("deterministic-v1");
        assertThat(response.forecasts().getFirst().provenance().getFirst().provider())
                .isEqualTo(StockDataProviderId.FMP);
    }

    @Test
    void readsPinnedDegradedV4ForecastFixture() throws Exception {
        var root = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(root.resolve("contracts"))) {
            root = root.getParent();
        }
        var response = objectMapper.readValue(
                Files.readString(root.resolve("contracts/analysis/v4/stock-forecast-core-response.json")),
                StockForecastCoreContract.Response.class);

        assertThat(response.status()).isEqualTo(StockAnalysisCoreContract.Status.DEGRADED);
        assertThat(response.forecasts()).extracting(StockForecastCoreContract.Metric::name)
                .containsExactlyElementsOf(StockForecastCoreContract.FORECAST_ORDER);
    }
}
