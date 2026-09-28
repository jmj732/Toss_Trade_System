package com.jmj.trade.monitoring;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoringEvaluationContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesExistingConcentrationPolicyUsingFastApiFieldName() throws Exception {
        var policy = new MonitoringEvaluationContract.RiskPolicyInput(
                new BigDecimal("0.25"), null, null);

        var json = objectMapper.writeValueAsString(policy);

        assertThat(json).contains("\"maxConcentration\":0.25")
                .doesNotContain("maxPositionWeight");
    }

    @Test
    void carriesQuoteSourceAndCollectionTimeInWatchlistInputs() throws Exception {
        var range = new MonitoringWatchlistService.Range(BigDecimal.TEN, new BigDecimal("12"));
        var levels = new MonitoringEvaluationContract.WatchlistLevels(range, range, range, range);
        var asOf = Instant.parse("2026-09-27T09:00:00Z");
        var collectedAt = Instant.parse("2026-09-27T09:01:00Z");
        var input = new MonitoringEvaluationContract.WatchlistInput(
                "ONTO", "WATCH", levels, BigDecimal.TEN, null, null, asOf, "fake-price", collectedAt);

        var json = objectMapper.writeValueAsString(List.of(input));

        assertThat(json).contains("\"source\":\"fake-price\"")
                .contains("\"collectedAt\":\"2026-09-27T09:01:00Z\"");
    }

    @Test
    void initialWatchlistStateEvidenceContainsConfiguredLevelsAndTimestamp() {
        var range = new MonitoringWatchlistService.Range(BigDecimal.TEN, new BigDecimal("12"));
        var levels = new MonitoringWatchlistService.WatchlistLevels(range, range, range, range);
        var asOf = Instant.parse("2026-09-27T09:00:00Z");

        var evidence = MonitoringWatchlistService.initialEvidence(levels, asOf);

        assertThat(evidence).containsEntry("action", "CREATE_WATCHLIST")
                .containsEntry("source", "USER_CONFIGURATION")
                .containsEntry("asOf", asOf.toString())
                .containsKey("levels");
    }
}
