package com.jmj.trade.investment;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.marketdata.DataProviderRole;
import com.jmj.trade.marketdata.ProviderValue;
import com.jmj.trade.marketdata.StockDataProvider;
import com.jmj.trade.marketdata.StockDataProviderId;
import com.jmj.trade.marketdata.StockDataProviderRegistry;
import com.jmj.trade.monitoring.MonitoringWatchlistService;
import com.jmj.trade.risk.RiskPolicyService;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class InvestmentPriceHistoryIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("1a318354-27f1-4b2d-96c6-7905caed8346");
    private static final UUID EMPTY_USER_ID = UUID.fromString("2b428465-38f2-4c3e-a7d7-8016bfcf9457");
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;

    @BeforeEach
    void migrateAndSeed() {
        freshMigratedSchema();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        mapper = new ObjectMapper();

        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO users (id) VALUES (?)", USER_ID);
        jdbc.update("""
                INSERT INTO monitoring_watchlist (id, user_id, symbol, levels, evidence, observed_at, created_at, updated_at)
                VALUES (?, ?, 'AAPL', '{"prepare":1,"confirm":2,"pullback":3,"invalidate":0}'::jsonb,
                        '{}'::jsonb, ?, ?, ?)
                """, UUID.randomUUID(), USER_ID, now, now, now);
    }

    @Test
    void storesUnsortedVerifiedDailyClosesIdempotentlyAndBuildsTechnicalFromFiftyActualRows() {
        var history = historyRows();
        history.add(Map.of("date", LocalDate.now(ZoneOffset.UTC).plusDays(2).toString(), "close", 999));
        history.add(Map.of("date", LocalDate.now(ZoneOffset.UTC).minusDays(50).toString(), "close", 0));
        history.add(Map.of("date", "not-a-date", "close", 999));
        var service = service(history, true);

        service.capture(USER_ID);
        service.capture(USER_ID);

        var pipeline = jdbc.queryForMap("""
                SELECT status, last_error FROM investment_pipeline_state
                 WHERE user_id = ? AND pipeline = 'SECURITY_DATA'
                """, USER_ID);
        assertThat(pipeline.get("status")).isEqualTo("FAILED");
        assertThat(pipeline.get("last_error").toString()).startsWith("CANONICAL_REQUIRED_DATA_MISSING");

        var stored = jdbc.queryForList("""
                SELECT regular_close, regular_close_as_of, source
                  FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' AND session = 'REGULAR_CLOSE'
                 ORDER BY regular_close_as_of
                """, USER_ID);
        assertThat(stored).hasSize(50);
        assertThat(stored).allSatisfy(row -> assertThat(row.get("source")).isEqualTo("TOSS"));
        assertThat((BigDecimal) stored.getLast().get("regular_close")).isEqualByComparingTo("50");
        assertThat(stored.getLast().get("regular_close_as_of").toString())
                .contains(LocalDate.now(ZoneOffset.UTC).minusDays(1).toString());

        var technical = jdbc.queryForObject("""
                SELECT payload -> 'technical' ->> 'dailyObservations'
                  FROM investment_security_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL'
                 ORDER BY created_at DESC LIMIT 1
                """, String.class, USER_ID);
        assertThat(technical).isEqualTo("50");
        var sma50 = jdbc.queryForObject("""
                SELECT (payload -> 'technical' ->> 'sma50')::numeric
                  FROM investment_security_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL'
                 ORDER BY created_at DESC LIMIT 1
                """, BigDecimal.class, USER_ID);
        assertThat(sma50).isEqualByComparingTo("25.5");
        var price = jdbc.queryForMap("""
                SELECT payload -> 'price' ->> 'status' AS status,
                       payload -> 'price' ->> 'session' AS session,
                       (payload -> 'price' ->> 'latestPrice')::numeric AS latest_price
                  FROM investment_security_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL'
                 ORDER BY created_at DESC LIMIT 1
                """, USER_ID);
        assertThat(price.get("status")).isEqualTo("OK");
        assertThat(price.get("session")).isEqualTo("REGULAR_CLOSE");
        assertThat((BigDecimal) price.get("latest_price")).isEqualByComparingTo("50");
    }

    @Test
    void ignoresHistoryWithoutExplicitRegularCloseSessionMetadata() {
        var service = service(List.of(Map.of(
                "date", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString(), "close", 10)), false, false);

        service.capture(USER_ID);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL'
                """, Integer.class, USER_ID)).isZero();
        var status = jdbc.queryForObject("""
                SELECT payload -> 'technical' ->> 'trendStatus'
                  FROM investment_security_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL'
                """, String.class, USER_ID);
        assertThat(status).isEqualTo("DATA_MISSING");
    }

    @Test
    void verifiedBarSessionDoesNotDependOnGlobalQuoteSession() {
        var service = service(List.of(Map.of(
                "date", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString(), "close", 10)), false, true);

        service.capture(USER_ID);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' AND source = 'TOSS'
                   AND session = 'REGULAR_CLOSE' AND regular_close = 10
                """, Integer.class, USER_ID)).isEqualTo(1);
    }

    @Test
    void doesNotCaptureAZeroQuantityPosition() {
        insertZeroQuantityPosition();

        service(historyRows(), true).capture(USER_ID);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM analysis_input_snapshots
                 WHERE user_id = ? AND symbol = 'MSFT'
                """, Integer.class, USER_ID)).isZero();
    }

    @Test
    void initialCaptureCheckSkipsAnActiveAccountWithNoPositiveHoldingsOrWatchlist() {
        jdbc.update("DELETE FROM monitoring_watchlist WHERE user_id = ?", USER_ID);
        insertZeroOnlyAccount();

        assertThat(service(historyRows(), true).needsInitialCapture()).isFalse();
    }

    @Test
    void fmpSnapshotsDoNotSatisfyCanonicalBootstrapAndQuoteUpdatesSkipFinancialPersistence() {
        var service = service(historyRows(), true);
        assertThat(service.needsInitialCapture()).isTrue();
        service.capture(USER_ID);
        var inputId = jdbc.queryForObject("""
                SELECT id FROM analysis_input_snapshots WHERE user_id = ? AND symbol = 'AAPL'
                 ORDER BY created_at DESC LIMIT 1
                """, UUID.class, USER_ID);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO fundamental_snapshots (
                    id, user_id, input_snapshot_id, ticker, fiscal_period, reported_at, as_of, source, created_at
                ) VALUES (?, ?, ?, 'AAPL', '2026-06-30', ?, ?, 'FMP', ?)
                """, UUID.randomUUID(), USER_ID, inputId, now, now, now);
        jdbc.update("""
                INSERT INTO consensus_snapshots (
                    id, user_id, input_snapshot_id, ticker, as_of, horizon, eps_consensus, source, created_at
                ) VALUES (?, ?, ?, 'AAPL', ?, 'FY1', 2.5, 'FMP', ?)
                """, UUID.randomUUID(), USER_ID, inputId, now, now);
        assertThat(service.needsInitialCapture()).isTrue();

        service.captureQuoteUpdates(USER_ID);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM fundamental_snapshots WHERE user_id = ?", Integer.class,
                USER_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consensus_snapshots WHERE user_id = ?", Integer.class,
                USER_ID)).isEqualTo(1);
        var latestInput = jdbc.queryForObject("""
                SELECT payload::text FROM analysis_input_snapshots
                 WHERE user_id = ? AND symbol = 'AAPL'
                 ORDER BY created_at DESC LIMIT 1
                """, String.class, USER_ID);
        assertThat(latestInput).contains("quote.price");
        var price = jdbc.queryForMap("""
                SELECT payload -> 'price' ->> 'status' AS status,
                       payload -> 'price' ->> 'session' AS session,
                       (payload -> 'price' ->> 'latestPrice')::numeric AS latest_price
                  FROM investment_security_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL'
                 ORDER BY created_at DESC LIMIT 1
                """, USER_ID);
        assertThat(price.get("status")).isEqualTo("OK");
        assertThat(price.get("session")).isEqualTo("REGULAR_CLOSE");
        assertThat((BigDecimal) price.get("latest_price")).isEqualByComparingTo("50");
        assertThat(service.needsInitialCapture()).isTrue();
        var pipeline = jdbc.queryForMap("""
                SELECT status, last_error FROM investment_pipeline_state
                 WHERE user_id = ? AND pipeline = 'SECURITY_QUOTE_UPDATE'
                """, USER_ID);
        assertThat(pipeline.get("status")).isEqualTo("FAILED");
        assertThat(pipeline.get("last_error").toString())
                .isEqualTo("CANONICAL_REQUIRED_DATA_MISSING:TOSS_LATEST_PRICE_MISSING");
    }

    @Test
    void quoteOnlyCapturePersistsTypedIntradayPriceAndRequestsOnlyQuoteFields() {
        var sourceAsOf = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        var requestedFields = new AtomicReference<Set<String>>();
        var fields = Set.of(
                "quote.price", "quote.volume", "quote.change-percent",
                "price.latestPrice", "price.session", "price.regularCloseHistory",
                "fundamental.eps", "consensus.epsConsensus");
        var values = List.of(
                new ProviderValue("quote.price", mapper.valueToTree(123.45), sourceAsOf, List.of()),
                new ProviderValue("quote.volume", mapper.valueToTree(1_000), sourceAsOf, List.of()),
                new ProviderValue("quote.change-percent", mapper.valueToTree(1.2), sourceAsOf, List.of()),
                new ProviderValue("price.latestPrice", mapper.valueToTree(new BigDecimal("123.45")),
                        sourceAsOf, List.of()),
                new ProviderValue("price.session", mapper.valueToTree("LIVE_REGULAR"), sourceAsOf, List.of()));
        StockDataProvider provider = new StockDataProvider() {
            @Override
            public StockDataProviderId id() {
                return StockDataProviderId.FMP;
            }

            @Override
            public DataProviderRole role() {
                return DataProviderRole.FUNDAMENTALS;
            }

            @Override
            public Set<String> fields() {
                return fields;
            }

            @Override
            public List<ProviderValue> fetch(com.jmj.trade.marketdata.ProviderRequest request) {
                return values;
            }

            @Override
            public List<ProviderValue> fetch(com.jmj.trade.marketdata.ProviderRequest request,
                                             Set<String> selectedFields) {
                requestedFields.set(Set.copyOf(selectedFields));
                return values.stream().filter(value -> selectedFields.contains(value.field())).toList();
            }
        };

        service(provider).captureQuoteUpdates(USER_ID);

        assertThat(requestedFields.get()).containsExactlyInAnyOrder(
                "quote.price", "quote.volume", "quote.change-percent", "price.latestPrice", "price.session");
        var price = jdbc.queryForMap("""
                SELECT source, session, latest_price, latest_price_as_of
                  FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL'
                """, USER_ID);
        assertThat(price.get("source")).isEqualTo("FMP");
        assertThat(price.get("session")).isEqualTo("LIVE_REGULAR");
        assertThat((BigDecimal) price.get("latest_price")).isEqualByComparingTo("123.45");
        var persistedAsOf = jdbc.queryForObject("""
                SELECT latest_price_as_of FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL'
                """, OffsetDateTime.class, USER_ID);
        assertThat(persistedAsOf.toInstant()).isEqualTo(sourceAsOf);
    }

    private InvestmentContextService service(List<Map<String, Object>> history, boolean explicitSession) {
        return service(history, explicitSession, true);
    }

    private InvestmentContextService service(List<Map<String, Object>> history, boolean explicitSession,
                                             boolean verifiedBarSession) {
        var fields = new java.util.HashSet<>(Set.of("price.regularCloseHistory", "quote.price",
                "quote.volume", "quote.change-percent"));
        if (explicitSession) fields.add("price.session");
        StockDataProvider provider = new StockDataProvider() {
            @Override
            public StockDataProviderId id() {
                return StockDataProviderId.TOSS;
            }

            @Override
            public DataProviderRole role() {
                return DataProviderRole.BROKER_ACCOUNT;
            }

            @Override
            public Set<String> fields() {
                return Set.copyOf(fields);
            }

            @Override
            public List<ProviderValue> fetch(com.jmj.trade.marketdata.ProviderRequest request) {
                var asOf = Instant.now().minusSeconds(1);
                var values = new ArrayList<ProviderValue>();
                var verifiedHistory = history.stream().map(row -> {
                    var bar = new java.util.LinkedHashMap<String, Object>();
                    var dateText = (String) row.get("date");
                    bar.put("date", dateText);
                    try {
                        var date = LocalDate.parse(dateText);
                        bar.put("timestamp", date.atStartOfDay(ZoneId.of("America/New_York"))
                                .toOffsetDateTime().toString());
                    } catch (RuntimeException ignored) {
                        // Keep malformed fixture rows in the payload so production validation rejects them.
                    }
                    bar.put("close", row.get("close"));
                    if (verifiedBarSession) bar.put("session", "REGULAR_CLOSE");
                    return Map.copyOf(bar);
                }).toList();
                values.add(new ProviderValue("price.regularCloseHistory", mapper.valueToTree(verifiedHistory),
                        null, null, null, asOf, List.of()));
                values.add(new ProviderValue("quote.price", mapper.valueToTree(51), null, null, null,
                        asOf, List.of()));
                values.add(new ProviderValue("quote.volume", mapper.valueToTree(1_000), null, null, null,
                        asOf, List.of()));
                values.add(new ProviderValue("quote.change-percent", mapper.valueToTree(1.2), null, null, null,
                        asOf, List.of()));
                if (explicitSession) {
                    values.add(new ProviderValue("price.session", mapper.valueToTree("REGULAR_CLOSE"),
                            null, null, null, asOf, List.of()));
                }
                return List.copyOf(values);
            }
        };
        return service(provider);
    }

    private InvestmentContextService service(StockDataProvider provider) {
        return new InvestmentContextService(jdbc, mapper, new DataSourceTransactionManager(jdbc.getDataSource()),
                new StockDataProviderRegistry(List.of(provider)), mock(ObjectProvider.class),
                mock(PortfolioReadService.class),
                mock(MonitoringWatchlistService.class), mock(RiskPolicyService.class), Duration.ofMinutes(15),
                Duration.ofDays(7), Duration.ofDays(210), Duration.ofDays(10));
    }

    private static List<Map<String, Object>> historyRows() {
        var rows = new ArrayList<Map<String, Object>>();
        var latest = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        for (var age = 1; age < 50; age += 2) {
            rows.add(Map.of("date", latest.minusDays(age).toString(), "close", 50 - age));
        }
        for (var age = 0; age < 50; age += 2) {
            rows.add(Map.of("date", latest.minusDays(age).toString(), "close", 50 - age));
        }
        return rows;
    }

    private void insertZeroQuantityPosition() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var connectionId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0)
                """, connectionId, USER_ID, new byte[17], new byte[12], now, now);
        jdbc.update("""
                INSERT INTO account_sync_runs (
                    id, user_id, broker_connection_id, credential_revision,
                    status, started_at, completed_at
                ) VALUES (?, ?, ?, 1, 'SUCCEEDED', ?, ?)
                """, runId, USER_ID, connectionId, now.minusSeconds(5), now);
        jdbc.update("""
                INSERT INTO position_snapshots (
                    id, sync_run_id, user_id, broker_connection_id, symbol, name,
                    market_country, quantity, currency, average_price, last_price,
                    purchase_amount, market_value_amount, market_value_after_cost,
                    profit_loss_amount, profit_loss_after_cost, profit_loss_rate,
                    profit_loss_rate_after_cost, daily_profit_loss_amount,
                    daily_profit_loss_rate, commission, tax, observed_at, created_at
                ) VALUES (
                    ?, ?, ?, ?, 'MSFT', 'Microsoft', 'US', 0, 'USD', 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, NULL, ?, ?
                )
                """, UUID.randomUUID(), runId, USER_ID, connectionId, now, now);
    }

    private void insertZeroOnlyAccount() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var connectionId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id) VALUES (?)", EMPTY_USER_ID);
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0)
                """, connectionId, EMPTY_USER_ID, new byte[17], new byte[12], now, now);
        jdbc.update("""
                INSERT INTO account_sync_runs (
                    id, user_id, broker_connection_id, credential_revision,
                    status, started_at, completed_at
                ) VALUES (?, ?, ?, 1, 'SUCCEEDED', ?, ?)
                """, runId, EMPTY_USER_ID, connectionId, now.minusSeconds(5), now);
        jdbc.update("""
                INSERT INTO position_snapshots (
                    id, sync_run_id, user_id, broker_connection_id, symbol, name,
                    market_country, quantity, currency, average_price, last_price,
                    purchase_amount, market_value_amount, market_value_after_cost,
                    profit_loss_amount, profit_loss_after_cost, profit_loss_rate,
                    profit_loss_rate_after_cost, daily_profit_loss_amount,
                    daily_profit_loss_rate, commission, tax, observed_at, created_at
                ) VALUES (
                    ?, ?, ?, ?, 'MSFT', 'Microsoft', 'US', 0, 'USD', 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, NULL, ?, ?
                )
                """, UUID.randomUUID(), runId, EMPTY_USER_ID, connectionId, now, now);
    }
}
