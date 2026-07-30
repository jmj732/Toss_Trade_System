package com.jmj.trade.prediction;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

@Component
public final class PredictionIngestionApiKeyMetrics {

    private final Map<Reason, Counter> rejected = new EnumMap<>(Reason.class);

    public PredictionIngestionApiKeyMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        for (var reason : Reason.values()) {
            rejected.put(reason, Counter.builder(
                            "trade.prediction.ingestion.api.key.rejected")
                    .tag("reason", reason.tag())
                    .register(registry));
        }
    }

    void recordRejected(Reason reason) {
        rejected.get(Objects.requireNonNull(reason, "reason")).increment();
    }

    enum Reason {
        EXPIRED("expired"),
        RATE_LIMITED("rate_limited"),
        REDIS_UNAVAILABLE("redis_unavailable");

        private final String tag;

        Reason(String tag) {
            this.tag = tag;
        }

        String tag() {
            return tag;
        }
    }
}
