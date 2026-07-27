package com.jmj.trade.analysis;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioAnalysisContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void springReadsCanonicalRequest() throws Exception {
        var request = objectMapper.readValue(
                Files.readString(contract("portfolio-analysis-request.json")),
                PortfolioAnalysisContract.Request.class);

        assertThat(request.requestId())
                .isEqualTo(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        assertThat(request.schemaVersion()).isEqualTo("1");
        assertThat(request.quality().stale()).isTrue();
        assertThat(request.quality().partial()).isTrue();
        assertThat(request.quality().unknownFields())
                .containsExactly("positions[2].profitLoss");
        assertThat(request.positions()).hasSize(4);
        assertThat(request.positions().get(2).profitLoss()).isNull();
    }

    @Test
    void springReadsCanonicalResponse() throws Exception {
        var response = objectMapper.readValue(
                Files.readString(contract("portfolio-analysis-response.json")),
                PortfolioAnalysisContract.Response.class);

        assertThat(response.status()).isEqualTo(PortfolioAnalysisContract.Status.DEGRADED);
        assertThat(response.positions().getFirst().weight()).isEqualByComparingTo("0.6000000000");
        assertThat(response.currencyTotals()).hasSize(2);
        assertThat(response.currencyTotals().getFirst().currency())
                .isEqualTo(PortfolioAnalysisContract.Currency.KRW);
        assertThat(response.currencyTotals().getFirst().profitLoss()).isNull();
        assertThat(response.currencyTotals().getFirst().concentration())
                .isEqualByComparingTo("0.7000000000");
    }

    private static Path contract(String file) {
        var cwd = Path.of("").toAbsolutePath();
        var root = Files.isDirectory(cwd.resolve("contracts")) ? cwd : cwd.getParent();
        return root.resolve("contracts/analysis/v1").resolve(file);
    }
}
