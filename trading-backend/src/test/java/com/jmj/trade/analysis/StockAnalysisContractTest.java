package com.jmj.trade.analysis;

import com.jmj.trade.marketdata.StockDataProviderId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StockAnalysisContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsVersionedInputAndPreservesProviderProvenance() throws Exception {
        var request = objectMapper.readValue(
                Files.readString(contract("stock-analysis-input-request.json")),
                StockAnalysisContract.Request.class);

        assertThat(request.requestId())
                .isEqualTo(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        assertThat(request.input().symbol()).isEqualTo("AAPL");
        assertThat(request.input().observations()).hasSize(2);
        assertThat(request.input().observations().getFirst().provider())
                .isEqualTo(StockDataProviderId.FMP);
        assertThat(request.input().observations().get(1).value()).isNull();
        assertThat(request.input().observations().get(1).missingData())
                .containsExactly("PROVIDER_UNAVAILABLE");
    }

    @Test
    void readsDegradedResponseWithoutForecastOrExplainFields() throws Exception {
        var response = objectMapper.readValue(
                Files.readString(contract("stock-analysis-input-response.json")),
                StockAnalysisContract.Response.class);

        assertThat(response.status()).isEqualTo(StockAnalysisContract.Status.DEGRADED);
        assertThat(response.missingData())
                .containsExactly("FRED:macro.cpi:PROVIDER_UNAVAILABLE");
        assertThat(response.observations()).hasSize(2);
    }

    private static Path contract(String file) {
        var cwd = Path.of("").toAbsolutePath();
        var root = Files.isDirectory(cwd.resolve("contracts")) ? cwd : cwd.getParent();
        return root.resolve("contracts/analysis/v2").resolve(file);
    }
}
