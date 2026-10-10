package com.jmj.trade.monitoring;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.investment.InvestmentContextService;
import com.jmj.trade.marketdata.StockDataProviderRegistry;
import com.jmj.trade.notification.NotificationOutboxWriter;
import com.jmj.trade.risk.RiskPolicyService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * CONFIRMED thesis trigger review: alert-only, deduplicated, and never touching thesis state or orders.
 * Price timestamps are relative to the real clock because the context read path refreshes freshness with it.
 */
class MonitoringThesisTriggerDetectorIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("5b0f8f0e-3a52-4c8e-9a52-1f1f6f0a7c11");
    private static final String TICKER = "AAPL";
    private static final List<String> ORDER_TABLES = List.of(
            "order_intents", "broker_orders", "order_intent_outbox_events", "order_submission_outbox_events");

    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private MonitoringThesisTriggerDetector detector;

    @BeforeEach
    void migrateAndSeed() {
        freshMigratedSchema();
        dataSource = pooledTestDataSource();
        jdbc = new JdbcTemplate(dataSource);
        mapper = new ObjectMapper();
        var seededAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("INSERT INTO users (id) VALUES (?)", USER_ID);
        jdbc.update("""
                INSERT INTO investment_thesis_states (
                    user_id, ticker, core_thesis, price_risk_trigger, price_risk_trigger_price,
                    invalidation_status, classification, updated_at
                ) VALUES (?, ?, 'Growth thesis', 'Breaks below support', 100, 'CONFIRMED', 'COMPOUNDER', ?)
                """, USER_ID, TICKER, seededAt);
        jdbc.update("""
                INSERT INTO investment_thesis_revisions (
                    id, user_id, ticker, revision, previous_status, new_status, previous_trigger_price,
                    new_trigger_price, thesis_snapshot, actor_type, actor_user_id, recorded_at
                ) VALUES (?, ?, ?, 1, NULL, 'CONFIRMED', NULL, 100, '{}'::jsonb, 'USER_SESSION', ?, ?)
                """, UUID.randomUUID(), USER_ID, TICKER, USER_ID, seededAt);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.jmj.trade.account.BrokerSurfaceService> noBrokerSurface = mock(ObjectProvider.class);
        var context = new InvestmentContextService(
                jdbc, mapper, new DataSourceTransactionManager(dataSource),
                new StockDataProviderRegistry(List.of()), noBrokerSurface,
                mock(PortfolioReadService.class), mock(MonitoringWatchlistService.class),
                mock(RiskPolicyService.class),
                Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofDays(210), Duration.ofDays(10));
        detector = new MonitoringThesisTriggerDetector(context, new NotificationOutboxWriter(jdbc, mapper));
    }

    @AfterEach
    void closeDataSource() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void confirmedBreachWithVerifiedPriceEmitsOneReviewAlertWithoutTouchingThesisOrOrders() throws Exception {
        seedPrice("97.13", Instant.now().minusSeconds(60), "LIVE_REGULAR", "OK");
        var thesisBefore = thesisRow();
        var revisionsBefore = revisions();

        detector.detect(USER_ID, held(TICKER));
        detector.detect(USER_ID, held(TICKER));

        var alerts = alerts();
        assertThat(alerts).hasSize(1);
        var payload = mapper.readTree(alerts.getFirst());
        assertThat(payload.path("scope").asText()).isEqualTo("THESIS_REVIEW");
        assertThat(payload.path("alertType").asText()).isEqualTo("REVIEW");
        assertThat(payload.path("subjectKey").asText()).isEqualTo(TICKER);
        assertThat(payload.path("level").asText()).isEqualTo("BREACH");
        assertThat(payload.path("distancePct").asText()).isEqualTo("-2.87");
        assertThat(payload.path("thesisStatusChanged").asBoolean(true)).isFalse();
        assertThat(payload.path("orderAction").asText()).isEqualTo("NONE");
        assertThat(payload.path("message").asText()).isEqualTo("투자 논리 재검토 필요 — 자동 무효화·주문 없음");
        // Only the trigger-relative distance leaves the detector; never the raw price or trigger.
        assertThat(alerts.getFirst()).doesNotContain("97.13").doesNotContain("\"100");

        assertThat(thesisRow()).isEqualTo(thesisBefore);
        assertThat(revisions()).isEqualTo(revisionsBefore).isEqualTo(1);
        for (var table : ORDER_TABLES) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class))
                    .as(table).isZero();
        }
    }

    @Test
    void nearBandAlertsOnceAndEscalationToBreachAlertsSeparately() throws Exception {
        seedPrice("102.00", Instant.now().minusSeconds(120), "LIVE_REGULAR", "OK");
        detector.detect(USER_ID, held(TICKER));
        detector.detect(USER_ID, held(TICKER));

        assertThat(alerts()).hasSize(1);
        var near = mapper.readTree(alerts().getFirst());
        assertThat(near.path("level").asText()).isEqualTo("NEAR");
        assertThat(near.path("distancePct").asText()).isEqualTo("+2.00");

        seedPrice("99.50", Instant.now().minusSeconds(30), "LIVE_REGULAR", "OK");
        detector.detect(USER_ID, held(TICKER));
        detector.detect(USER_ID, held(TICKER));

        assertThat(alerts()).hasSize(2);
        assertThat(alerts().stream().map(this::level).toList()).containsExactlyInAnyOrder("NEAR", "BREACH");
    }

    @Test
    void priceAboveTheNearBandDoesNotAlert() throws Exception {
        seedPrice("103.01", Instant.now().minusSeconds(60), "LIVE_REGULAR", "OK");

        detector.detect(USER_ID, held(TICKER));

        assertThat(alerts()).isEmpty();
    }

    @Test
    void unapprovedTriggersAreNeverTreatedAsBreach() throws Exception {
        seedPrice("50.00", Instant.now().minusSeconds(60), "LIVE_REGULAR", "OK");
        for (var status : List.of("AI_PROPOSED", "UNVERIFIED", "INVALIDATION_UNDEFINED")) {
            jdbc.update("UPDATE investment_thesis_states SET invalidation_status = ? WHERE user_id = ? AND ticker = ?",
                    status, USER_ID, TICKER);

            detector.detect(USER_ID, held(TICKER));

            assertThat(alerts()).as(status).isEmpty();
        }
    }

    @Test
    void staleUnverifiedOrMissingPriceNeverAlerts() throws Exception {
        detector.detect(USER_ID, held(TICKER));
        assertThat(alerts()).as("no stored price").isEmpty();

        seedPrice("90.00", Instant.now().minus(Duration.ofHours(2)), "LIVE_REGULAR", "OK");
        detector.detect(USER_ID, held(TICKER));
        assertThat(alerts()).as("stale live quote").isEmpty();

        seedPrice("90.00", Instant.now().minusSeconds(60), "LIVE_REGULAR", "UNVERIFIED");
        detector.detect(USER_ID, held(TICKER));
        assertThat(alerts()).as("unverified").isEmpty();

        seedPrice("90.00", Instant.now().minusSeconds(60), "LIVE_REGULAR", "SOURCE_CONFLICT");
        detector.detect(USER_ID, held(TICKER));
        assertThat(alerts()).as("source conflict").isEmpty();

        seedPrice("90.00", Instant.now().minusSeconds(60), "AFTER_HOURS", "OK");
        detector.detect(USER_ID, held(TICKER));
        assertThat(alerts()).as("extended-hours quote").isEmpty();
    }

    @Test
    void notHeldOrUnverifiedHoldingsNeverAlert() throws Exception {
        seedPrice("90.00", Instant.now().minusSeconds(60), "LIVE_REGULAR", "OK");

        detector.detect(USER_ID, held("MSFT"));
        detector.detect(USER_ID, new MonitoringEvaluationContract.PortfolioInput(null, List.of(), null));
        detector.detect(USER_ID, new MonitoringEvaluationContract.PortfolioInput(Instant.now(),
                List.of(position(TICKER, BigDecimal.ZERO)), null));

        assertThat(alerts()).isEmpty();
    }

    private void seedPrice(String price, Instant asOf, String session, String status) throws Exception {
        var payload = Map.of("asOf", asOf.toString(),
                "price", Map.of("latestPrice", new BigDecimal(price), "latestPriceAsOf", asOf.toString(),
                        "session", session, "status", status));
        var storedAt = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO investment_security_snapshots (id, user_id, ticker, as_of, payload, created_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), USER_ID, TICKER, storedAt, mapper.writeValueAsString(payload), storedAt);
    }

    private static MonitoringEvaluationContract.PortfolioInput held(String symbol) {
        return new MonitoringEvaluationContract.PortfolioInput(Instant.now(),
                List.of(position(symbol, BigDecimal.TEN)), null);
    }

    private static MonitoringEvaluationContract.PositionInput position(String symbol, BigDecimal quantity) {
        return new MonitoringEvaluationContract.PositionInput(symbol, quantity, null, null, null,
                null, null, null, null, null, null, Map.of(), List.of());
    }

    private List<String> alerts() {
        return jdbc.queryForList("""
                SELECT payload::text FROM notification_outbox_events
                 WHERE user_id = ? AND event_type = 'MONITORING_ALERT' AND payload->>'scope' = 'THESIS_REVIEW'
                 ORDER BY created_at
                """, String.class, USER_ID);
    }

    private String level(String payload) {
        return mapper.readTree(payload).path("level").asText();
    }

    private String thesisRow() {
        return jdbc.queryForObject("""
                SELECT row_to_json(t)::text FROM investment_thesis_states t WHERE user_id = ? AND ticker = ?
                """, String.class, USER_ID, TICKER);
    }

    private int revisions() {
        return jdbc.queryForObject("SELECT count(*) FROM investment_thesis_revisions WHERE user_id = ?",
                Integer.class, USER_ID);
    }
}
