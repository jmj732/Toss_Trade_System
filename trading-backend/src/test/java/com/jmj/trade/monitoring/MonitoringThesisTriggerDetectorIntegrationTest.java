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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private InvestmentRiskEventService riskEventService;
    private DataSourceTransactionManager transactions;

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
        transactions = new DataSourceTransactionManager(dataSource);
        var context = new InvestmentContextService(
                jdbc, mapper, transactions,
                new StockDataProviderRegistry(List.of()), noBrokerSurface,
                mock(PortfolioReadService.class), mock(MonitoringWatchlistService.class),
                mock(RiskPolicyService.class),
                Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofDays(210), Duration.ofDays(10));
        var notifications = new NotificationOutboxWriter(jdbc, mapper);
        var stateStore = new MonitoringStateStore(jdbc, mapper);
        riskEventService = new InvestmentRiskEventService(jdbc, mapper, transactions, stateStore, notifications);
        detector = new MonitoringThesisTriggerDetector(context, riskEventService);
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
        assertThat(payload.path("eventId").asText()).isNotBlank();
        assertThat(payload.path("transition").asText()).isEqualTo("NORMAL_TO_INTRADAY_BREACH");
        assertThat(riskEvents()).hasSize(1);
        assertThat(payload.path("eventId").asText()).isEqualTo(riskEventId(0).toString());
        assertThat(riskEventEvidence(0).path("thesisRevisionId").asText()).isNotBlank();
        assertThat(riskEventEvidence(0).path("priceBasis").asText()).isEqualTo("LIVE_REGULAR");
        assertThat(riskEventEvidence(0).path("riskMarkStatus").asText()).isNotBlank();
        assertThat(payload.path("observedAt").asText()).isNotBlank();
        assertThat(payload.has("observedPrice")).isFalse();
        assertThat(payload.has("triggerPrice")).isFalse();
        assertThat(payload.path("distancePct").asText()).isEqualTo("-2.87");
        assertThat(riskEventEvidence(0).path("observedPrice").decimalValue()).isEqualByComparingTo("97.13");
        assertThat(riskEventEvidence(0).path("triggerPrice").decimalValue()).isEqualByComparingTo("100");

        assertThat(thesisRow()).isEqualTo(thesisBefore);
        assertThat(revisions()).isEqualTo(revisionsBefore).isEqualTo(1);
        for (var table : ORDER_TABLES) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class))
                    .as(table).isZero();
        }
    }

    @Test
    void verifiedLastCompletedCloseCanTriggerReviewWhenPremarketQuoteIsStale() throws Exception {
        var sessionDate = LocalDate.now(ZoneId.of("America/New_York")).minusDays(1);
        seedPriceWithRegularClose("150.00", Instant.now().minus(Duration.ofMinutes(30)), "PREMARKET",
                "97.13", sessionDate, sessionDate, "TOSS", Instant.now().plus(Duration.ofDays(1)));
        var thesisBefore = thesisRow();
        var revisionsBefore = revisions();

        detector.detect(USER_ID, held(TICKER));

        var alerts = alerts();
        assertThat(alerts).hasSize(1);
        var payload = mapper.readTree(alerts.getFirst());
        assertThat(payload.path("level").asText()).isEqualTo("BREACH");
        assertThat(payload.path("priceBasis").asText()).isEqualTo("REGULAR_CLOSE");
        assertThat(payload.path("sessionDate").asText()).isEqualTo(sessionDate.toString());
        assertThat(riskEventEvidence(0).path("eventType").asText()).isEqualTo("PRICE_RISK_BREACH");
        assertThat(riskEventEvidence(0).path("priceBasis").asText()).isEqualTo("REGULAR_CLOSE");
        assertThat(payload.path("thesisStatusChanged").asBoolean(true)).isFalse();
        assertThat(payload.path("orderAction").asText()).isEqualTo("NONE");
        assertThat(payload.has("observedPrice")).isFalse();
        assertThat(payload.has("triggerPrice")).isFalse();
        assertThat(riskEventEvidence(0).path("observedPrice").decimalValue()).isEqualByComparingTo("97.13");
        assertThat(riskEventEvidence(0).path("triggerPrice").decimalValue()).isEqualByComparingTo("100");
        assertThat(thesisRow()).isEqualTo(thesisBefore);
        assertThat(revisions()).isEqualTo(revisionsBefore);
        for (var table : ORDER_TABLES) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).as(table).isZero();
        }
    }

    @Test
    void regularCloseForDifferentSessionCannotTriggerReview() throws Exception {
        var closeDate = LocalDate.now(ZoneId.of("America/New_York")).minusDays(2);
        var lastCompletedSession = closeDate.plusDays(1);
        seedPriceWithRegularClose("150.00", Instant.now().minus(Duration.ofMinutes(30)), "PREMARKET",
                "97.13", closeDate, lastCompletedSession, "TOSS", Instant.now().plus(Duration.ofDays(1)));

        detector.detect(USER_ID, held(TICKER));

        assertThat(alerts()).isEmpty();
    }

    @Test
    void closeDateLabelDoesNotBackdateTransitionAfterLaterIntradayEvent() throws Exception {
        seedPrice("97.13", Instant.now().minus(Duration.ofMinutes(1)), "LIVE_REGULAR", "OK");
        detector.detect(USER_ID, held(TICKER));

        var closeDate = LocalDate.now(ZoneId.of("America/New_York")).minusDays(1);
        seedPriceWithRegularClose("150.00", Instant.now().minus(Duration.ofMinutes(30)), "PREMARKET",
                "103.01", closeDate, closeDate, "TOSS", Instant.now().plus(Duration.ofDays(1)));
        detector.detect(USER_ID, held(TICKER));

        assertThat(riskEvents()).hasSize(2);
        var first = riskEventEvidence(0);
        var second = riskEventEvidence(1);
        assertThat(first.path("eventType").asText()).isEqualTo("INTRADAY_BREACH");
        assertThat(second.path("eventType").asText()).isEqualTo("RECOVERED");
        assertThat(second.path("sourceAsOf").asText())
                .isEqualTo(closeDate.atStartOfDay(ZoneId.of("America/New_York")).toInstant().toString());
        assertThat(Instant.parse(second.path("observedAt").asText()))
                .isAfter(Instant.parse(first.path("observedAt").asText()));
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
        assertThat(riskEvents()).hasSize(2);
        assertThat(riskEventEvidence(0).path("incidentId").asText())
                .isEqualTo(riskEventEvidence(1).path("incidentId").asText());
    }

    @Test
    void recoveryClosesIncidentAndLaterBreachStartsANewIncident() throws Exception {
        seedPrice("102.00", Instant.now().minusSeconds(120), "LIVE_REGULAR", "OK");
        detector.detect(USER_ID, held(TICKER));
        seedPrice("99.50", Instant.now().minusSeconds(90), "LIVE_REGULAR", "OK");
        detector.detect(USER_ID, held(TICKER));
        seedPrice("103.01", Instant.now().minusSeconds(60), "LIVE_REGULAR", "OK");
        detector.detect(USER_ID, held(TICKER));
        seedPrice("99.00", Instant.now().minusSeconds(30), "LIVE_REGULAR", "OK");
        detector.detect(USER_ID, held(TICKER));
        detector.detect(USER_ID, held(TICKER));

        assertThat(riskEvents()).hasSize(4);
        assertThat(riskEventEvidence(0).path("transition").asText()).isEqualTo("NORMAL_TO_NEAR");
        assertThat(riskEventEvidence(1).path("transition").asText()).isEqualTo("NEAR_TO_BREACH");
        assertThat(riskEventEvidence(2).path("transition").asText()).isEqualTo("BREACH_TO_RECOVERED");
        assertThat(riskEventEvidence(2).path("resolvedAt").asText()).isNotBlank();
        assertThat(riskEventEvidence(0).path("incidentId").asText())
                .isEqualTo(riskEventEvidence(2).path("incidentId").asText());
        assertThat(riskEventEvidence(3).path("transition").asText()).isEqualTo("RECOVERED_TO_BREACH");
        assertThat(riskEventEvidence(3).path("incidentId").asText())
                .isNotEqualTo(riskEventEvidence(2).path("incidentId").asText());
        assertThat(alerts()).hasSize(4);
        for (var index = 0; index < 4; index++) {
            assertThat(mapper.readTree(alerts().get(index)).path("eventId").asText())
                    .isEqualTo(riskEventId(index).toString());
        }
    }

    @Test
    void eachEventKeepsTheThesisRevisionObservedAtThatTransition() throws Exception {
        var firstRevisionId = jdbc.queryForObject("""
                SELECT id FROM investment_thesis_revisions
                 WHERE user_id = ? AND ticker = ? AND revision = 1
                """, UUID.class, USER_ID, TICKER);
        seedPrice("102.00", Instant.now().minusSeconds(120), "LIVE_REGULAR", "OK");
        detector.detect(USER_ID, held(TICKER));

        var secondRevisionId = UUID.randomUUID();
        var recordedAt = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO investment_thesis_revisions (
                    id, user_id, ticker, revision, previous_status, new_status, previous_trigger_price,
                    new_trigger_price, thesis_snapshot, actor_type, actor_user_id, recorded_at
                ) VALUES (?, ?, ?, 2, 'CONFIRMED', 'CONFIRMED', 100, 100, '{}'::jsonb, 'USER_SESSION', ?, ?)
                """, secondRevisionId, USER_ID, TICKER, USER_ID, recordedAt);
        seedPrice("99.50", Instant.now().minusSeconds(30), "LIVE_REGULAR", "OK");
        detector.detect(USER_ID, held(TICKER));

        assertThat(riskEvents()).hasSize(2);
        assertThat(riskEventEvidence(0).path("thesisRevisionId").asText())
                .isEqualTo(firstRevisionId.toString());
        assertThat(riskEventEvidence(0).path("thesisRevision").asLong()).isEqualTo(1);
        assertThat(riskEventEvidence(1).path("thesisRevisionId").asText())
                .isEqualTo(secondRevisionId.toString());
        assertThat(riskEventEvidence(1).path("thesisRevision").asLong()).isEqualTo(2);
        assertThat(riskEventEvidence(0).path("triggerPrice").decimalValue()).isEqualByComparingTo("100");
    }

    @Test
    void priceAboveTheNearBandDoesNotAlert() throws Exception {
        seedPrice("103.01", Instant.now().minusSeconds(60), "LIVE_REGULAR", "OK");

        detector.detect(USER_ID, held(TICKER));

        assertThat(alerts()).isEmpty();
        assertThat(riskEvents()).isEmpty();
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
        assertThat(riskEvents()).isEmpty();
    }

    @Test
    void riskEventReadIsUserScopedPaginatedAndSurvivesServiceRestart() throws Exception {
        seedPrice("102.00", Instant.now().minusSeconds(120), "LIVE_REGULAR", "OK");
        detector.detect(USER_ID, held(TICKER));
        seedPrice("99.50", Instant.now().minusSeconds(30), "LIVE_REGULAR", "OK");
        detector.detect(USER_ID, held(TICKER));

        var first = riskEventService.read(USER_ID,
                new InvestmentRiskEventService.RiskEventsQuery(TICKER, null, true, 1, null));
        assertThat(first.events()).hasSize(1);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(first.events().getFirst()).containsEntry("ticker", TICKER)
                .containsEntry("priceSource", "TOSS")
                .containsEntry("priceBasis", "LIVE_REGULAR")
                .containsEntry("eventType", "INTRADAY_BREACH");
        assertThat(first.unresolvedEvents()).hasSize(1);
        assertThat(first.status()).isEqualTo("OK");

        var second = riskEventService.read(USER_ID,
                new InvestmentRiskEventService.RiskEventsQuery(TICKER, null, false, 1, first.nextCursor()));
        assertThat(second.events()).hasSize(1);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.events().getFirst().get("eventId"))
                .isNotEqualTo(first.events().getFirst().get("eventId"));

        var anotherUser = riskEventService.read(UUID.randomUUID(),
                new InvestmentRiskEventService.RiskEventsQuery(null, null, true, 50, null));
        assertThat(anotherUser.events()).isEmpty();
        assertThat(anotherUser.unresolvedEvents()).isEmpty();
        assertThat(anotherUser.status()).isEqualTo("OK");

        var afterAll = riskEventService.read(USER_ID,
                new InvestmentRiskEventService.RiskEventsQuery(TICKER, Instant.now().plusSeconds(30), false, 50, null));
        assertThat(afterAll.events()).isEmpty();
        assertThatThrownBy(() -> riskEventService.read(USER_ID,
                new InvestmentRiskEventService.RiskEventsQuery(TICKER, null, false, 50, "not-a-cursor")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid cursor");

        var restartedService = new InvestmentRiskEventService(jdbc, mapper, transactions,
                new MonitoringStateStore(jdbc, mapper), new NotificationOutboxWriter(jdbc, mapper));
        assertThat(restartedService.read(USER_ID,
                new InvestmentRiskEventService.RiskEventsQuery(TICKER, null, false, 50, null)).events())
                .hasSize(2);
    }

    @Test
    void riskEventReadBoundsLinkedDecisionDetailsAndMarksTruncation() throws Exception {
        seedPrice("99.50", Instant.now().minusSeconds(30), "LIVE_REGULAR", "OK");
        detector.detect(USER_ID, held(TICKER));
        var eventId = riskEventId(0);
        var revisionId = jdbc.queryForObject(
                "SELECT id FROM investment_thesis_revisions WHERE user_id = ? AND ticker = ? ORDER BY revision DESC LIMIT 1",
                UUID.class, USER_ID, TICKER);
        var linkedAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        for (int i = 0; i < 7; i++) {
            var decisionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO investment_decision_ledger (
                        decision_id, user_id, as_of, asset, action, alpha_thesis, invalidation,
                        next_review_trigger, risk_policy_check, created_at
                    ) VALUES (?, ?, ?, ?, 'HOLD', ?, ?, ?, '{}'::jsonb, ?)
                    """, decisionId, USER_ID, linkedAt.plusSeconds(i), TICKER,
                    "rationale-" + i + "x".repeat(1200), "invalidation", "review", linkedAt.plusSeconds(i));
            jdbc.update("""
                    INSERT INTO investment_risk_event_decision_links (
                        id, user_id, ticker, event_id, decision_id, thesis_revision_id, linked_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), USER_ID, TICKER, eventId, decisionId, revisionId, linkedAt.plusSeconds(i));
        }

        var event = riskEventService.read(USER_ID,
                new InvestmentRiskEventService.RiskEventsQuery(TICKER, null, false, 50, null))
                .events().getFirst();
        @SuppressWarnings("unchecked")
        var decisions = (List<Map<String, Object>>) event.get("relatedDecisions");
        assertThat(decisions).hasSize(5);
        assertThat(event).containsEntry("relatedDecisionsTruncated", true);
        assertThat(decisions).allSatisfy(decision -> {
            assertThat((String) decision.get("rationale")).hasSize(500);
            assertThat(decision).containsEntry("rationaleTruncated", true);
        });
    }

    @Test
    void concurrentDuplicateRiskEvaluationsPersistOneEventAndOneTelegramOutboxRow() throws Exception {
        var observedAt = Instant.now().minusSeconds(15).truncatedTo(ChronoUnit.MICROS);
        var evidence = new java.util.LinkedHashMap<String, Object>();
        evidence.put("sourceAsOf", observedAt.toString());
        evidence.put("priceSource", "TOSS");
        evidence.put("priceSession", "LIVE_REGULAR");
        evidence.put("priceBasis", "LIVE_REGULAR");
        evidence.put("observedPrice", new BigDecimal("102.00"));
        evidence.put("triggerPrice", new BigDecimal("100.00"));
        evidence.put("regularCloseSessionDate", null);
        evidence.put("riskMarkStatus", "OK");
        evidence.put("thesisRevisionId", jdbc.queryForObject(
                "SELECT id FROM investment_thesis_revisions WHERE user_id = ? AND ticker = ? ORDER BY revision DESC LIMIT 1",
                UUID.class, USER_ID, TICKER).toString());
        evidence.put("invalidationStatus", "CONFIRMED");
        evidence.put("reasonCode", "APPROVED_TRIGGER_NEAR");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return riskEventService.recordObservation(
                    USER_ID, TICKER, "NEAR", evidence, observedAt); });
            var second = executor.submit(() -> { start.await(); return riskEventService.recordObservation(
                    USER_ID, TICKER, "NEAR", evidence, observedAt); });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(riskEvents()).hasSize(1);
        assertThat(alerts()).hasSize(1);
        assertThat(alerts().getFirst()).contains(riskEventId(0).toString());
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
                        "session", session, "status", status, "source", "TOSS"));
        var storedAt = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO investment_security_snapshots (id, user_id, ticker, as_of, payload, created_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), USER_ID, TICKER, storedAt, mapper.writeValueAsString(payload), storedAt);
    }

    private void seedPriceWithRegularClose(
            String latestPrice, Instant latestAsOf, String session, String regularClose,
            LocalDate closeDate, LocalDate lastCompletedSessionDate, String closeSource, Instant closeValidUntil
    ) throws Exception {
        var closeAsOf = closeDate.atStartOfDay(ZoneId.of("America/New_York")).toInstant();
        var price = mapper.createObjectNode()
                .put("latestPrice", new BigDecimal(latestPrice))
                .put("latestPriceAsOf", latestAsOf.toString())
                .put("session", session)
                .put("status", "OK")
                .put("source", "TOSS")
                .put("regularClose", new BigDecimal(regularClose))
                .put("regularCloseAsOf", closeAsOf.toString())
                .put("regularCloseSessionDate", closeDate.toString())
                .put("regularCloseSource", closeSource)
                .put("regularCloseValidUntil", closeValidUntil.toString())
                .put("lastCompletedSessionDate", lastCompletedSessionDate.toString());
        var payload = mapper.createObjectNode().put("asOf", latestAsOf.toString()).set("price", price);
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

    private List<UUID> riskEvents() {
        return jdbc.queryForList("""
                SELECT id FROM monitoring_state_history
                 WHERE user_id = ? AND scope = 'THESIS_REVIEW' ORDER BY observed_at, id
                """, UUID.class, USER_ID);
    }

    private UUID riskEventId(int index) {
        return riskEvents().get(index);
    }

    private JsonNode riskEventEvidence(int index) throws Exception {
        var evidence = jdbc.queryForObject("""
                SELECT evidence::text FROM monitoring_state_history
                 WHERE user_id = ? AND scope = 'THESIS_REVIEW' ORDER BY observed_at, id
                 OFFSET ? LIMIT 1
                """, String.class, USER_ID, index);
        return mapper.readTree(evidence);
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
