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
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_outbox_events", Integer.class)).isEqualTo(1);
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

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderFixture {

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
                    if (fail) {
                        throw new IllegalStateException("provider unavailable");
                    }
                    return List.of(new MarketEvent(
                            id,
                            "CIK0001045810:0001045810-26-000001",
                            "SEC_8-K",
                            "NVIDIA filing",
                            Instant.parse("2026-08-01T12:00:00Z"),
                            List.of("NVDA"),
                            List.of()));
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
