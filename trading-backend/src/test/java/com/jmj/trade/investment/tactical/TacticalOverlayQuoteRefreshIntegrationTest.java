package com.jmj.trade.investment.tactical;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.account.BrokerSurfaceService;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.connection.BrokerSurfaceResponse;
import com.jmj.trade.investment.InvestmentContextService;
import com.jmj.trade.marketdata.DataProviderRole;
import com.jmj.trade.marketdata.ProviderRequest;
import com.jmj.trade.marketdata.ProviderValue;
import com.jmj.trade.marketdata.StockDataProvider;
import com.jmj.trade.marketdata.StockDataProviderId;
import com.jmj.trade.marketdata.StockDataProviderRegistry;
import com.jmj.trade.monitoring.MonitoringWatchlistService;
import com.jmj.trade.risk.RiskPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TacticalOverlayQuoteRefreshIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private final UUID userId = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private HikariDataSource dataSource;
    private ObjectMapper mapper;
    private DataSourceTransactionManager transactions;

    @BeforeEach
    void migrateAndSeedUser() {
        freshMigratedSchema();
        dataSource = pooledTestDataSource();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        mapper = new ObjectMapper();
        jdbc.update("INSERT INTO users (id) VALUES (?)", userId);
    }

    @AfterEach
    void closeTestDataSource() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void quoteOnlyCaptureRefreshesPositionMarkAndContextStalesItWithoutWrites() throws Exception {
        var now = nowMicros();
        var evaluationClock = new MutableClock(now);
        var properties = TacticalOverlayProperties.defaults();
        var overlay = new TacticalOverlayService(jdbc, mapper, new TacticalOverlayCalculator(properties),
                properties, new TacticalOverlayAggregationCalculator(), "AVT", evaluationClock);
        var connectionId = insertActiveTossConnection();
        var quote = new AtomicReference<>(price("AVT", "110", now.minusSeconds(120)));
        var providerCalls = new AtomicInteger();
        StockDataProvider unrelatedProvider = new StockDataProvider() {
            @Override public StockDataProviderId id() { return StockDataProviderId.FMP; }
            @Override public DataProviderRole role() { return DataProviderRole.FUNDAMENTALS; }
            @Override public Set<String> fields() { return Set.of("fundamental.cash"); }
            @Override public List<ProviderValue> fetch(ProviderRequest request) {
                providerCalls.incrementAndGet();
                return List.of();
            }
        };
        var surface = mock(BrokerSurfaceService.class);
        when(surface.prices(userId, connectionId, "AVT")).thenAnswer(ignored ->
                BrokerSurfaceResponse.available(List.of(quote.get())));
        when(surface.marketCalendar(eq(userId), eq(connectionId), eq("US"), any(LocalDate.class)))
                .thenAnswer(ignored -> calendar(quote.get().brokerTimestamp()));

        PortfolioReadService portfolios = mock(PortfolioReadService.class);
        when(portfolios.read(eq(userId), eq(connectionId))).thenReturn(new PortfolioReadService.PortfolioView(
                UUID.randomUUID(), now, false, null, false, List.of(), List.of(), null, List.of(), Map.of()));
        MonitoringWatchlistService watchlist = mock(MonitoringWatchlistService.class);
        when(watchlist.list(userId)).thenReturn(List.of());
        RiskPolicyService risk = mock(RiskPolicyService.class);
        when(risk.current(userId)).thenReturn(new RiskPolicyService.RiskPolicySnapshot(
                0, new BigDecimal("10000000"), new BigDecimal("10000"),
                new BigDecimal("100"), new BigDecimal("0.25"), false));
        @SuppressWarnings("unchecked")
        ObjectProvider<BrokerSurfaceService> surfaceProvider = mock(ObjectProvider.class);
        when(surfaceProvider.getIfAvailable()).thenReturn(surface);
        var context = new InvestmentContextService(jdbc, mapper, transactions,
                new StockDataProviderRegistry(List.of(unrelatedProvider)), surfaceProvider, portfolios,
                watchlist, risk, Duration.ofMinutes(15), Duration.ofDays(7),
                Duration.ofDays(210), Duration.ofDays(10));
        context.setTacticalOverlayService(overlay);

        var entryDate = now.atZone(NEW_YORK).toLocalDate();
        var lastBarDate = previousWeekday(entryDate);
        var barSourceAsOf = lastBarDate.atTime(16, 0).atZone(NEW_YORK).toInstant();
        overlay.recordTossBars(userId, "AVT", mapper.valueToTree(List.of(barRow(lastBarDate, "100", barSourceAsOf))),
                now, null);
        var entrySourceAsOf = evaluationClock.instant();
        overlay.putPerformanceEntry(userId, "AVT", new TacticalOverlayService.PerformanceInput(
                "quote-mark", null, entryDate, bd("100"), bd("90"), bd("5"),
                "breakout", "NO_EFFECT", List.of(), "USER_INPUT", entrySourceAsOf));

        var barsBeforeQuoteA = count("investment_tactical_overlay_bar_snapshots");
        var nonPositionBeforeQuoteA = tacticalNonPositionSnapshotCount();
        assertThat(context.captureQuoteUpdates(userId)).isEqualTo(1);
        var quoteAId = selectedQuoteId();
        var initial = positionMetrics(context.context(userId));
        assertMetric(initial, "initialR", "1", "OK");
        assertMetric(initial, "currentR", "1", "OK");
        assertMetric(initial, "mae", null, "INSUFFICIENT_HISTORY");
        assertMetric(initial, "mfe", null, "INSUFFICIENT_HISTORY");
        assertThat(initial.path("forwardReturns").isArray()).isTrue();
        assertThat(initial.path("forwardReturns").get(0).path("horizon").asInt()).isEqualTo(5);
        assertThat(initial.path("forwardReturns").get(1).path("horizon").asInt()).isEqualTo(20);
        assertMetric(initial.path("forwardReturns").get(0), "returnPct", null, "INSUFFICIENT_HISTORY");
        assertMetric(initial.path("forwardReturns").get(1), "returnPct", null, "INSUFFICIENT_HISTORY");
        assertThat(tacticalNonPositionSnapshotCount()).isEqualTo(nonPositionBeforeQuoteA);
        assertThat(count("investment_tactical_overlay_bar_snapshots")).isEqualTo(barsBeforeQuoteA);
        assertThat(providerCalls).hasValue(0);

        var barsBeforeQuoteB = count("investment_tactical_overlay_bar_snapshots");
        var nonPositionBeforeQuoteB = tacticalNonPositionSnapshotCount();
        var quoteBTime = nowMicros().minusSeconds(30);
        quote.set(price("AVT", "120", quoteBTime));
        evaluationClock.set(nowMicros());
        assertThat(context.captureQuoteUpdates(userId)).isEqualTo(1);
        evaluationClock.set(nowMicros());

        var quoteBId = selectedQuoteId();
        var expectedSourceAsOf = entrySourceAsOf.isAfter(quoteBTime) ? entrySourceAsOf : quoteBTime;
        assertThat(quoteBId).isNotEqualTo(quoteAId);
        assertThat(tacticalNonPositionSnapshotCount()).isEqualTo(nonPositionBeforeQuoteB);
        assertThat(count("investment_tactical_overlay_bar_snapshots")).isEqualTo(barsBeforeQuoteB);
        assertThat(providerCalls).hasValue(0);
        verify(surface, never()).candles(eq(userId), eq(connectionId), anyString(), anyString(),
                any(Integer.class), isNull(), eq(false));

        var afterQuoteB = context.context(userId);
        var quoteBMetrics = positionMetrics(afterQuoteB);
        assertMetric(quoteBMetrics, "currentR", "2", "OK");
        assertThat(quoteBMetrics.path("markAsOf").asText()).isEqualTo(quoteBTime.toString());
        assertThat(quoteBMetrics.path("markSource").asText()).isEqualTo("TOSS");
        assertThat(quoteBMetrics.path("markSnapshotId").asText()).isEqualTo(quoteBId.toString());
        assertThat(quoteBMetrics.path("entrySourceAsOf").asText()).isEqualTo(entrySourceAsOf.toString());
        assertThat(quoteBMetrics.path("barAsOf").asText()).isEqualTo(lastBarDate.toString());
        assertThat(quoteBMetrics.path("barSourceAsOf").asText()).isEqualTo(barSourceAsOf.toString());
        assertThat(quoteBMetrics.path("sourceAsOf").asText()).isEqualTo(expectedSourceAsOf.toString());
        var persisted = jdbc.queryForMap("""
                SELECT payload::text AS payload, input_refs::text AS input_refs, source_as_of
                  FROM investment_tactical_overlay_snapshots
                 WHERE user_id = ? AND snapshot_type = 'POSITION' AND entity_key = 'AVT:quote-mark'
                 ORDER BY snapshot_order DESC LIMIT 1
                """, userId);
        var payload = mapper.readTree((String) persisted.get("payload"));
        assertThat(payload.path("markAsOf").asText()).isEqualTo(quoteBTime.toString());
        assertThat(payload.path("markSource").asText()).isEqualTo("TOSS");
        assertThat(payload.path("markSnapshotId").asText()).isEqualTo(quoteBId.toString());
        assertThat(payload.path("entrySourceAsOf").asText()).isEqualTo(entrySourceAsOf.toString());
        assertThat(((java.sql.Timestamp) persisted.get("source_as_of")).toInstant()).isEqualTo(expectedSourceAsOf);
        assertThat(mapper.readTree((String) persisted.get("input_refs")).toString()).contains(quoteBId.toString());

        var tacticalSnapshotsBeforeStaleRead = count("investment_tactical_overlay_snapshots");
        evaluationClock.advance(Duration.ofMinutes(16));
        var stale = positionMetrics(context.context(userId));
        assertMetric(stale, "currentR", null, "STALE");
        assertThat(count("investment_tactical_overlay_snapshots")).isEqualTo(tacticalSnapshotsBeforeStaleRead);
    }

    private JsonNode positionMetrics(InvestmentContextService.ContextView context) {
        var security = context.securities().stream().filter(row -> "AVT".equals(row.ticker())).findFirst()
                .orElseThrow();
        var rows = security.tacticalOverlay().performance();
        assertThat(rows).isNotNull();
        assertThat(rows.isArray()).isTrue();
        assertThat(rows.size()).isEqualTo(1);
        return rows.get(0).path("performance");
    }

    private void assertMetric(JsonNode metrics, String name, String expectedValue, String expectedStatus) {
        var metric = metrics.path(name);
        assertThat(metric.path("status").asText()).isEqualTo(expectedStatus);
        if (expectedValue == null) assertThat(metric.path("value").isNull()).isTrue();
        else assertThat(metric.path("value").decimalValue()).isEqualByComparingTo(expectedValue);
    }

    private UUID selectedQuoteId() {
        return jdbc.queryForObject("""
                SELECT id FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = 'AVT' AND source = 'TOSS'
                 ORDER BY latest_price_as_of DESC, as_of DESC LIMIT 1
                """, UUID.class, userId);
    }

    private int tacticalNonPositionSnapshotCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM investment_tactical_overlay_snapshots
                 WHERE user_id = ? AND snapshot_type IN ('SECURITY', 'THEME', 'MARKET')
                """, Integer.class, userId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class, userId);
    }

    private UUID insertActiveTossConnection() {
        var connectionId = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, created_at, updated_at, version, credential_revision
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, ?, ?, 0, 1)
                """, connectionId, userId, new byte[32], new byte[12], now, now);
        return connectionId;
    }

    private BrokerSurfaceResponse<BrokerSurfaceResponse.MarketCalendarView> calendar(Instant quoteAsOf) {
        var quoteDate = quoteAsOf.atZone(NEW_YORK);
        var date = quoteDate.toLocalDate();
        var payload = mapper.createObjectNode();
        var today = payload.putObject("today");
        today.put("date", date.toString());
        var interval = today.putObject("regularMarket");
        interval.put("startTime", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(quoteDate.minusSeconds(30)));
        interval.put("endTime", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(quoteDate.plusSeconds(30)));
        return BrokerSurfaceResponse.available(new BrokerSurfaceResponse.MarketCalendarView("US", payload));
    }

    private static BrokerSurfaceResponse.PriceView price(String ticker, String value, Instant asOf) {
        return new BrokerSurfaceResponse.PriceView(ticker, bd(value), null, null, "USD", asOf, asOf);
    }

    private static Map<String, Object> barRow(LocalDate date, String close, Instant sourceAsOf) {
        var price = bd(close);
        return Map.of("date", date.toString(), "timestamp", sourceAsOf.toString(), "session", "REGULAR_CLOSE",
                "currency", "USD", "open", price, "high", price.add(BigDecimal.ONE),
                "low", price.subtract(BigDecimal.ONE), "close", price, "volume", bd("1000"));
    }

    private static LocalDate previousWeekday(LocalDate date) {
        var prior = date.minusDays(1);
        while (prior.getDayOfWeek().getValue() > 5) prior = prior.minusDays(1);
        return prior;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static Instant nowMicros() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        private MutableClock(Instant instant) {
            this(new AtomicReference<>(instant), NEW_YORK);
        }

        private MutableClock(AtomicReference<Instant> instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void set(Instant value) {
            instant.set(value);
        }

        void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
        }

        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(instant, zone); }
        @Override public Instant instant() { return instant.get(); }
    }
}
