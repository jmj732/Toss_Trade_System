package com.jmj.trade.intelligence;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.intelligence.ingestion.MarketEvent;
import com.jmj.trade.intelligence.ingestion.MarketEventProvider;
import com.jmj.trade.intelligence.ingestion.MarketEventProviderId;
import com.jmj.trade.intelligence.ingestion.MarketEventProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest(classes = TradingBackendApplication.class)
@Import(AutomatedMarketEventIngestionIntegrationTest.ProviderFixture.class)
@ActiveProfiles("automated-market-events-test")
class AutomatedMarketEventIngestionIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 2, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.jmj.trade.intelligence.ingestion.MarketEventIngestionService ingestion;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE broker_connections, users CASCADE");
        jdbc.update("DELETE FROM market_event_ingestion_runs");
        jdbc.update("DELETE FROM market_event_ingestion_leases");
        ProviderFixture.CAPTURED_SINCE.clear();
    }

    @Test
    void providerFailureDoesNotStopOtherProvidersAndDuplicateIsNoOp() {
        var connectionId = insertConnection(USER_ID);
        insertPortfolio(connectionId);

        var first = ingestion.collect();
        var second = ingestion.collect();

        assertThat(first.providersSucceeded()).contains(MarketEventProviderId.SEC);
        assertThat(first.providersFailed()).contains(MarketEventProviderId.FRED);
        assertThat(second.providersSucceeded()).contains(MarketEventProviderId.SEC);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_events", Integer.class)).isEqualTo(1);
        // Automated official-event ingestion no longer emits EVENT_CREATED notifications directly; the
        // continuous-monitoring evaluator owns alerting on material events (see EventIntelligenceService.insert's
        // notify flag and EventIntelligenceIntegrationTest asserting EVENT_CREATED count == 0 for this path).
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_outbox_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM market_event_ingestion_runs WHERE status = 'FAILED'",
                Integer.class)).isEqualTo(1);

        var failedRunId = jdbc.queryForObject("""
                SELECT id
                  FROM market_event_ingestion_runs
                 WHERE provider = 'FRED' AND status = 'FAILED'
                 ORDER BY started_at DESC, id DESC
                 LIMIT 1
                """, UUID.class);
        assertThat(ingestion.reprocess(failedRunId)).isTrue();
        ingestion.collect();
        assertThat(jdbc.queryForObject("""
                SELECT attempt
                  FROM market_event_ingestion_runs
                 WHERE provider = 'FRED'
                 ORDER BY started_at DESC, id DESC
                 LIMIT 1
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void includesDatabaseWatchlistSymbolsInOfficialEventCollectionTargets() {
        var connectionId = insertConnection(USER_ID);
        insertPortfolio(connectionId);
        jdbc.update("""
                INSERT INTO monitoring_watchlist (id, user_id, symbol, levels, status,
                                                 evidence, observed_at, created_at, updated_at)
                VALUES (?, ?, 'ONTO', '{"prepare":{"min":295,"max":302},"confirm":{"min":306,"max":310},
                        "pullback":{"min":262,"max":270},"invalidate":{"min":240,"max":250}}'::jsonb,
                        'WATCH', '{}'::jsonb, ?, ?, ?)
                """, UUID.randomUUID(), USER_ID, NOW, NOW, NOW);

        ingestion.collect();

        assertThat(jdbc.queryForList("SELECT affected_symbols::text FROM intelligence_events", String.class))
                .contains("[\"NVDA\"]", "[\"ONTO\"]");
    }

    @Test
    void fredUsesLongLookbackWhileSecUsesGlobalLookback() {
        var connectionId = insertConnection(USER_ID);
        insertPortfolio(connectionId);

        ingestion.collect();

        // Line 77: the per-provider lookback drives the collection Request.since handed to each provider.
        var fredSince = ProviderFixture.CAPTURED_SINCE.get(MarketEventProviderId.FRED);
        var secSince = ProviderFixture.CAPTURED_SINCE.get(MarketEventProviderId.SEC);
        assertThat(fredSince).isNotNull();
        assertThat(secSince).isNotNull();
        var now = Instant.now();
        assertThat(fredSince).isCloseTo(
                now.minus(java.time.Duration.ofDays(45)), within(1, java.time.temporal.ChronoUnit.HOURS));
        assertThat(secSince).isCloseTo(
                now.minus(java.time.Duration.ofDays(2)), within(1, java.time.temporal.ChronoUnit.HOURS));

        // Line 211: the recorded requested_since uses the same per-provider lookback relative to started_at.
        assertThat(recordedLookbackDays(MarketEventProviderId.FRED)).isEqualTo(45L);
        assertThat(recordedLookbackDays(MarketEventProviderId.SEC)).isEqualTo(2L);
    }

    private long recordedLookbackDays(MarketEventProviderId provider) {
        return jdbc.queryForObject("""
                SELECT ROUND(EXTRACT(EPOCH FROM (started_at - requested_since)) / 86400)
                  FROM market_event_ingestion_runs
                 WHERE provider = ?
                 ORDER BY started_at DESC, id DESC
                 LIMIT 1
                """, Long.class, provider.name());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderFixture {

        static final java.util.Map<MarketEventProviderId, Instant> CAPTURED_SINCE =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Primary
        @Bean("fixtureMarketEventProviderRegistry")
        MarketEventProviderRegistry fixtureMarketEventProviderRegistry() {
            return new MarketEventProviderRegistry(List.of(
                    provider(MarketEventProviderId.SEC, false),
                    provider(MarketEventProviderId.FRED, true)));
        }

        private static MarketEventProvider provider(
                MarketEventProviderId id,
                boolean fail
        ) {
            return new MarketEventProvider() {
                @Override
                public MarketEventProviderId id() {
                    return id;
                }

                @Override
                public List<MarketEvent> collect(Request request) {
                    CAPTURED_SINCE.put(id, request.since());
                    if (fail) {
                        throw new IllegalStateException("provider unavailable");
                    }
                    // Model a real official provider: emit one filing per symbol in the request, so a symbol
                    // that only reaches the request through the DB watchlist (e.g. ONTO) yields its own event.
                    var events = new java.util.ArrayList<MarketEvent>();
                    for (var symbol : new java.util.TreeSet<>(request.symbols())) {
                        events.add(new MarketEvent(
                                id,
                                "CIK:" + symbol + ":8-K:2026-08-01",
                                "SEC_8-K",
                                symbol + " filing",
                                Instant.parse("2026-08-01T12:00:00Z"),
                                List.of(symbol),
                                List.of()));
                    }
                    return List.copyOf(events);
                }
            };
        }
    }

    private UUID insertConnection(UUID userId) {
        jdbc.update("""
                INSERT INTO users (id)
                VALUES (?)
                ON CONFLICT (id) DO NOTHING
                """, userId);
        var connectionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0)
                """, connectionId, userId, new byte[17], new byte[12], NOW, NOW);
        return connectionId;
    }

    private void insertPortfolio(UUID connectionId) {
        var runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO account_sync_runs (
                    id, user_id, broker_connection_id, credential_revision, status,
                    started_at, completed_at
                ) VALUES (?, ?, ?, 1, 'SUCCEEDED', ?, ?)
                """, runId, USER_ID, connectionId, NOW, NOW.plusMinutes(1));
        jdbc.update("""
                INSERT INTO position_snapshots (
                    id, sync_run_id, user_id, broker_connection_id, symbol, name,
                    market_country, quantity, currency, average_price, last_price,
                    purchase_amount, market_value_amount, market_value_after_cost,
                    profit_loss_amount, profit_loss_after_cost, profit_loss_rate,
                    profit_loss_rate_after_cost, daily_profit_loss_amount,
                    daily_profit_loss_rate, commission, tax, observed_at, created_at
                ) VALUES (?, ?, ?, ?, 'NVDA', 'NVIDIA', 'US', 1, 'USD', 100, 120,
                          100, 120, 120, 20, 20, 0.2, 0.2, 0, 0, 0, 0, ?, ?)
                """, UUID.randomUUID(), runId, USER_ID, connectionId, NOW, NOW);
    }
}
