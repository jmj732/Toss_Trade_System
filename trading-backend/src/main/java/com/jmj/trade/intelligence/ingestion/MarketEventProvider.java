package com.jmj.trade.intelligence.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

public interface MarketEventProvider {

    MarketEventProviderId id();

    List<MarketEvent> collect(Request request);

    default CollectionResult collectWithFailures(Request request) {
        return new CollectionResult(collect(request), List.of());
    }

    record CollectionResult(List<MarketEvent> events, List<RuntimeException> failures) {

        public CollectionResult {
            events = events == null ? List.of() : List.copyOf(events);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        boolean hasFailures() {
            return !failures.isEmpty();
        }
    }

    record Request(Set<String> symbols, Instant since, int maxEvents, BooleanSupplier heartbeat) {

        public Request(Set<String> symbols, Instant since, int maxEvents) {
            this(symbols, since, maxEvents, () -> true);
        }

        public Request {
            symbols = symbols == null ? Set.of() : Set.copyOf(symbols);
            heartbeat = heartbeat == null ? () -> true : heartbeat;
            if (since == null || maxEvents < 1) {
                throw new IllegalArgumentException("provider request is invalid");
            }
        }

        public void heartbeatCheck() {
            if (!heartbeat.getAsBoolean()) {
                throw new IllegalStateException("market event ingestion lease expired");
            }
        }
    }
}
