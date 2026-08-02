package com.jmj.trade.analysis;

import com.jmj.trade.marketdata.StockAnalysisInput;
import com.jmj.trade.marketdata.StockDataProviderId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StockAnalysisSnapshotHasherTest {

    private static final Instant AT = Instant.parse("2026-08-02T00:00:00Z");
    private static final UUID ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void hashIgnoresObservationOrderButChangesWhenSourceValueChanges() {
        var first = input(List.of(observation("quote.price", "100"), observation("quote.volume", "5")));
        var reordered = input(List.of(observation("quote.volume", "5"), observation("quote.price", "100")));
        var changed = input(List.of(observation("quote.price", "101"), observation("quote.volume", "5")));

        var hasher = new StockAnalysisSnapshotHasher(new ObjectMapper());

        assertThat(hasher.hash(first)).isEqualTo(hasher.hash(reordered));
        assertThat(hasher.hash(first)).isNotEqualTo(hasher.hash(changed));
        assertThat(hasher.hashCanonical(hasher.canonicalJson(first))).isEqualTo(hasher.hash(first));
    }

    private static StockAnalysisInput input(List<StockAnalysisInput.Observation> observations) {
        return new StockAnalysisInput(ID, "AAPL", "1", AT, observations);
    }

    private static StockAnalysisInput.Observation observation(String field, String value) {
        return new StockAnalysisInput.Observation(
                field,
                new ObjectMapper().readTree(value),
                null,
                null,
                "AAPL",
                StockDataProviderId.FMP,
                AT,
                AT,
                List.of());
    }
}
