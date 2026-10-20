package com.jmj.trade.investment;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.account.BrokerSurfaceService;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.connection.BrokerSurfaceResponse;
import com.jmj.trade.connector.ConnectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InvestmentSellReadinessIntegrationTest extends PostgresIntegrationTest {

    private static final String TICKER = "LUNR";
    private static final Instant NOW = Instant.parse("2026-10-20T14:00:00Z");
    private static final Instant BOOK_AS_OF = NOW.minusSeconds(2);
    private static final Instant OBSERVED_AT = NOW.minusSeconds(1);
    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private UUID userId;
    private UUID connectionId;
    private PortfolioReadService portfolios;
    private BrokerSurfaceService brokerSurface;
    private ConnectorService connector;
    private MockEnvironment environment;
    private InvestmentSellReadinessService service;
    private long revisionSequence;

    @BeforeEach
    void prepareDatabaseAndService() throws Exception {
        freshMigratedSchema();
        dataSource = pooledTestDataSource();
        jdbc = new JdbcTemplate(dataSource);
        userId = UUID.randomUUID();
        connectionId = insertUserAndConnection(userId);
        revisionSequence = 0;

        portfolios = mock(PortfolioReadService.class);
        brokerSurface = mock(BrokerSurfaceService.class);
        connector = mock(ConnectorService.class);
        environment = new MockEnvironment();
        environment.setProperty("investment.execution.readiness.max-spread-bps", "50");
        environment.setProperty("investment.execution.readiness.price-increments." + TICKER, "0.01");
        environment.setProperty("investment.execution.readiness.price-increment-source",
                "https://example.test/us-equity-price-increments");
        environment.setProperty("investment.execution.readiness.price-increment-verified", "true");
        when(connector.orders(userId, connectionId, "OPEN")).thenReturn(List.of());
        when(brokerSurface.orderBook(eq(userId), eq(connectionId), eq(TICKER)))
                .thenReturn(freshOrderBook("10.36"));
        when(brokerSurface.marketCalendar(eq(userId), eq(connectionId), eq("US"), any(LocalDate.class)))
                .thenReturn(freshMarketCalendar());

        service = new InvestmentSellReadinessService(jdbc, portfolios, brokerSurface, connector, environment,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void closeDataSource() {
        if (dataSource instanceof com.zaxxer.hikari.HikariDataSource pool) pool.close();
    }

    @Test
    void returnsReadyFromTheVerifiedAccountPositionDespitePartialCombinedSnapshot() throws Exception {
        var decision = seedDecision("REDUCE", bd("22"), connectionId, NOW.minusSeconds(60), OBSERVED_AT,
                "INTRADAY_BREACH");
        stubAccountPosition("42", "42", OBSERVED_AT, false, false);
        insertPartialCombinedSnapshot();

        var result = service.evaluate(userId, connectionId, decision.decisionId(), decision.eventId());

        assertThat(result.actionStatus()).isEqualTo("READY");
        assertThat(result.sizingStatus()).isEqualTo("READY");
        assertThat(result.pricingStatus()).isEqualTo("READY");
        assertThat(result.prepareStatus()).isEqualTo("READY");
        assertThat(result.quantity()).isEqualByComparingTo("20");
        assertThat(result.targetRemainingQuantity()).isEqualByComparingTo("22");
        assertThat(result.thesisRevisionId()).isEqualTo(decision.revisionId());
        assertThat(result.bid()).isEqualByComparingTo("10.35");
        assertThat(result.ask()).isEqualByComparingTo("10.36");
        assertThat(result.limitPrice()).isEqualByComparingTo("10.35");
        assertThat(result.spreadBps()).isNotNull().isPositive();
        assertThat(result.priceSession()).isEqualTo("LIVE_REGULAR");
        assertThat(result.priceSource()).isEqualTo("TOSS");
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.orderBookAsOf()).isEqualTo(BOOK_AS_OF);
        assertThat(result.orderBookObservedAt()).isEqualTo(OBSERVED_AT);
        assertThat(result.marketCalendarSource()).isEqualTo("TOSS/api/v1/market-calendar/US");
        assertThat(result.priceIncrement()).isEqualByComparingTo("0.01");
        assertThat(result.blockers()).isEmpty();
        verify(portfolios).read(userId, connectionId);
        verify(brokerSurface).orderBook(userId, connectionId, TICKER);
        verify(brokerSurface).marketCalendar(userId, connectionId, "US", LocalDate.parse("2026-10-20"));
    }

    @Test
    void blocksMissingPolicyWithoutReadingPositionOrExecutionData() throws Exception {
        var decision = seedDecision("REDUCE", null, null, NOW.minusSeconds(60), OBSERVED_AT,
                "INTRADAY_BREACH");

        var result = service.evaluate(userId, connectionId, decision.decisionId(), decision.eventId());

        assertThat(result.actionStatus()).isEqualTo("BLOCKED");
        assertThat(result.blockers()).contains("POLICY_NOT_CONFIGURED");
        verifyNoInteractions(portfolios, brokerSurface, connector);
    }

    @Test
    void blocksFractionalDayLimitQuantityBeforeFetchingExecutableQuotes() throws Exception {
        var decision = seedDecision("REDUCE", bd("22"), connectionId, NOW.minusSeconds(60), OBSERVED_AT,
                "INTRADAY_BREACH");
        stubAccountPosition("42.423074", "42.423074", OBSERVED_AT, false, false);

        var result = service.evaluate(userId, connectionId, decision.decisionId(), decision.eventId());

        assertThat(result.actionStatus()).isEqualTo("READY");
        assertThat(result.sizingStatus()).isEqualTo("BLOCKED");
        assertThat(result.blockers()).containsExactly("FRACTIONAL_LIMIT_SELL_UNSUPPORTED");
        verifyNoInteractions(brokerSurface, connector);
    }

    @Test
    void blocksRecoveredRiskEvent() throws Exception {
        var recovered = seedDecision("REDUCE", bd("22"), connectionId, NOW.minusSeconds(60), OBSERVED_AT,
                "RECOVERED");
        var recoveredResult = service.evaluate(userId, connectionId, recovered.decisionId(), recovered.eventId());
        assertThat(recoveredResult.blockers()).contains("RISK_EVENT_NOT_CURRENT");
        verifyNoInteractions(portfolios, brokerSurface);
    }

    @Test
    void blocksStaleRiskEvent() throws Exception {
        var stale = seedDecision("REDUCE", bd("22"), connectionId, NOW.minusSeconds(60),
                NOW.minusSeconds(16 * 60), "INTRADAY_BREACH");
        var staleResult = service.evaluate(userId, connectionId, stale.decisionId(), stale.eventId());
        assertThat(staleResult.blockers()).contains("RISK_EVENT_STALE_OR_INVALID");
        verifyNoInteractions(portfolios, brokerSurface);
    }

    @Test
    void blocksSupersededRiskDecision() throws Exception {
        var superseded = seedDecision("REDUCE", bd("22"), connectionId, NOW.minusSeconds(60), OBSERVED_AT,
                "INTRADAY_BREACH");
        insertNewerDecision(userId, NOW.minusSeconds(1));
        var supersededResult = service.evaluate(
                userId, connectionId, superseded.decisionId(), superseded.eventId());
        assertThat(supersededResult.blockers()).contains("DECISION_SUPERSEDED");
        verifyNoInteractions(portfolios, brokerSurface);
    }

    @Test
    void blocksUnownedDecisionEventPairs() throws Exception {
        var decision = seedDecision("REDUCE", bd("22"), connectionId, NOW.minusSeconds(60), OBSERVED_AT,
                "INTRADAY_BREACH");
        var unowned = service.evaluate(userId, connectionId, UUID.randomUUID(), decision.eventId());
        assertThat(unowned.blockers()).containsExactly("OWNED_DECISION_EVENT_LINK_NOT_FOUND");
        var unlinkedEvent = service.evaluate(userId, connectionId, decision.decisionId(), UUID.randomUUID());
        assertThat(unlinkedEvent.blockers()).containsExactly("OWNED_DECISION_EVENT_LINK_NOT_FOUND");
        verifyNoInteractions(portfolios, brokerSurface);
    }

    @Test
    void blocksRiskEventWithMissingPriceSourceEvidence() throws Exception {
        var decision = seedDecision("REDUCE", bd("22"), connectionId, NOW.minusSeconds(60), OBSERVED_AT,
                "INTRADAY_BREACH", EvidenceVariant.MISSING_SOURCE_AS_OF);

        var result = service.evaluate(userId, connectionId, decision.decisionId(), decision.eventId());

        assertThat(result.actionStatus()).isEqualTo("BLOCKED");
        assertThat(result.blockers()).contains("RISK_EVENT_EVIDENCE_UNVERIFIED");
        verifyNoInteractions(portfolios, brokerSurface, connector);
    }

    @Test
    void blocksRiskEventLinkedToDifferentThesisRevision() throws Exception {
        var decision = seedDecision("REDUCE", bd("22"), connectionId, NOW.minusSeconds(60), OBSERVED_AT,
                "INTRADAY_BREACH", EvidenceVariant.MISMATCHED_THESIS_REVISION);

        var result = service.evaluate(userId, connectionId, decision.decisionId(), decision.eventId());

        assertThat(result.actionStatus()).isEqualTo("BLOCKED");
        assertThat(result.blockers()).contains("RISK_EVENT_EVIDENCE_UNVERIFIED");
        verifyNoInteractions(portfolios, brokerSurface, connector);
    }

    @Test
    void blocksDecisionForDifferentAccount() throws Exception {
        var decision = seedDecision("REDUCE", bd("22"), connectionId, NOW.minusSeconds(60), OBSERVED_AT,
                "INTRADAY_BREACH");
        var accountMismatch = service.evaluate(userId, UUID.randomUUID(), decision.decisionId(), decision.eventId());
        assertThat(accountMismatch.blockers()).contains("DECISION_ACCOUNT_MISMATCH");
        verifyNoInteractions(portfolios, brokerSurface);
    }

    @Test
    void blocksWhenTheLinkedThesisRevisionIsNoLongerCurrent() throws Exception {
        var decision = seedDecision("REDUCE", bd("22"), connectionId, NOW.minusSeconds(60), OBSERVED_AT,
                "INTRADAY_BREACH");
        insertRevision(UUID.randomUUID(), userId, TICKER, ++revisionSequence);
        var staleRevision = service.evaluate(userId, connectionId, decision.decisionId(), decision.eventId());
        assertThat(staleRevision.blockers()).contains("THESIS_REVISION_STALE");
        verifyNoInteractions(portfolios, brokerSurface);
    }

    @Test
    void blocksUnknownTickAndSpreadAbovePolicyDespiteFreshBookAndCalendar() throws Exception {
        var decision = seedDecision("REDUCE", bd("22"), connectionId, NOW.minusSeconds(60), OBSERVED_AT,
                "INTRADAY_BREACH");
        stubAccountPosition("42", "42", OBSERVED_AT, false, false);
        environment.setProperty("investment.execution.readiness.price-increment-verified", "false");

        var unknownTick = service.evaluate(userId, connectionId, decision.decisionId(), decision.eventId());
        assertThat(unknownTick.pricingStatus()).isEqualTo("BLOCKED");
        assertThat(unknownTick.blockers()).contains("PRICE_INCREMENT_UNVERIFIED");

        environment.setProperty("investment.execution.readiness.price-increment-verified", "true");
        environment.setProperty("investment.execution.readiness.max-spread-bps", "5");
        when(brokerSurface.orderBook(eq(userId), eq(connectionId), eq(TICKER)))
                .thenReturn(freshOrderBook("10.36"));
        var wideSpread = service.evaluate(userId, connectionId, decision.decisionId(), decision.eventId());
        assertThat(wideSpread.pricingStatus()).isEqualTo("BLOCKED");
        assertThat(wideSpread.blockers()).contains("SPREAD_EXCEEDS_POLICY");
    }

    @Test
    void blocksBookWithZeroOrMissingVolumeAtBestBid() throws Exception {
        var decision = seedDecision("REDUCE", bd("22"), connectionId, NOW.minusSeconds(60), OBSERVED_AT,
                "INTRADAY_BREACH");
        stubAccountPosition("42", "42", OBSERVED_AT, false, false);
        when(brokerSurface.orderBook(eq(userId), eq(connectionId), eq(TICKER)))
                .thenReturn(freshOrderBook("10.36", "0", "100"));

        var zeroVolume = service.evaluate(userId, connectionId, decision.decisionId(), decision.eventId());
        assertThat(zeroVolume.pricingStatus()).isEqualTo("BLOCKED");
        assertThat(zeroVolume.blockers()).contains("VALID_BID_ASK_MISSING");

        when(brokerSurface.orderBook(eq(userId), eq(connectionId), eq(TICKER)))
                .thenReturn(freshOrderBook("10.36", null, "100"));
        var missingVolume = service.evaluate(userId, connectionId, decision.decisionId(), decision.eventId());
        assertThat(missingVolume.pricingStatus()).isEqualTo("BLOCKED");
        assertThat(missingVolume.blockers()).contains("VALID_BID_ASK_MISSING");
    }

    private record DecisionFixture(UUID decisionId, UUID eventId, UUID revisionId) {
    }

    private enum EvidenceVariant {
        VALID,
        MISSING_SOURCE_AS_OF,
        MISMATCHED_THESIS_REVISION
    }

    private DecisionFixture seedDecision(
            String action, BigDecimal targetRemaining, UUID targetConnection, Instant decisionAsOf,
            Instant eventObservedAt, String eventState
    ) throws Exception {
        return seedDecision(action, targetRemaining, targetConnection, decisionAsOf, eventObservedAt,
                eventState, EvidenceVariant.VALID);
    }

    private DecisionFixture seedDecision(
            String action, BigDecimal targetRemaining, UUID targetConnection, Instant decisionAsOf,
            Instant eventObservedAt, String eventState, EvidenceVariant evidenceVariant
    ) throws Exception {
        var decisionId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var revisionId = UUID.randomUUID();
        insertRevision(revisionId, userId, TICKER, ++revisionSequence);
        var previousRiskState = "RECOVERED".equals(eventState) ? "INTRADAY_BREACH" : "NORMAL";
        jdbc.update("""
                INSERT INTO investment_decision_ledger (
                    decision_id, user_id, as_of, asset, action, reference_price, price_session,
                    horizon, alpha_thesis, invalidation, next_review_trigger, confidence,
                    risk_policy_check, created_at, target_remaining_quantity, target_broker_connection_id
                ) VALUES (?, ?, ?, ?, ?, 10.35, 'LIVE_REGULAR', '12M', 'risk thesis',
                          'risk invalidation', 'review trigger', 0.8, '{}'::jsonb, ?, ?, ?)
                """, decisionId, userId, offset(decisionAsOf), TICKER, action, offset(NOW), targetRemaining,
                targetConnection);
        var evidence = riskEvidence(eventId, revisionId, revisionSequence, eventObservedAt, eventState,
                evidenceVariant);
        jdbc.update("""
                INSERT INTO monitoring_state_history (
                    id, user_id, scope, subject_key, previous_state, new_state, source_type,
                    source_event_id, evidence, observed_at, created_at
                ) VALUES (?, ?, 'THESIS_REVIEW', ?, ?, ?, 'CONFIRMED_THESIS_PRICE_TRIGGER', ?,
                          CAST(? AS jsonb), ?, ?)
                """, eventId, userId, TICKER, previousRiskState, eventState,
                riskEventDedupKey(eventId, eventState, eventObservedAt), evidence,
                offset(eventObservedAt), offset(eventObservedAt));
        jdbc.update("""
                INSERT INTO monitoring_current_states (
                    user_id, scope, subject_key, state, evidence, observed_at, updated_at
                ) VALUES (?, 'THESIS_REVIEW', ?, ?, CAST(? AS jsonb), ?, ?)
                ON CONFLICT (user_id, scope, subject_key) DO UPDATE
                    SET state = EXCLUDED.state, evidence = EXCLUDED.evidence,
                        observed_at = EXCLUDED.observed_at, updated_at = EXCLUDED.updated_at
                """, userId, TICKER, eventState, evidence, offset(eventObservedAt), offset(eventObservedAt));
        jdbc.update("""
                INSERT INTO investment_risk_event_decision_links (
                    id, user_id, ticker, event_id, decision_id, thesis_revision_id, linked_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), userId, TICKER, eventId, decisionId, revisionId, offset(NOW));
        return new DecisionFixture(decisionId, eventId, revisionId);
    }

    private void insertNewerDecision(UUID ownerId, Instant asOf) {
        jdbc.update("""
                INSERT INTO investment_decision_ledger (
                    decision_id, user_id, as_of, asset, action, reference_price, price_session,
                    horizon, alpha_thesis, invalidation, next_review_trigger, confidence,
                    risk_policy_check, created_at
                ) VALUES (?, ?, ?, ?, 'HOLD', 10.35, 'LIVE_REGULAR', '12M', 'newer thesis',
                          'newer invalidation', 'newer review', 0.7, '{}'::jsonb, ?)
                """, UUID.randomUUID(), ownerId, offset(asOf), TICKER, offset(NOW));
    }

    private void insertRevision(UUID revisionId, UUID ownerId, String ticker, long revision) {
        jdbc.update("""
                INSERT INTO investment_thesis_revisions (
                    id, user_id, ticker, revision, previous_status, new_status,
                    previous_trigger_price, new_trigger_price, thesis_snapshot,
                    actor_type, actor_user_id, recorded_at
                ) VALUES (?, ?, ?, ?, NULL, 'CONFIRMED', NULL, 11.1883, '{}'::jsonb,
                          'USER_SESSION', ?, ?)
                """, revisionId, ownerId, ticker, revision, ownerId, offset(NOW));
    }

    private UUID insertUserAndConnection(UUID ownerId) {
        jdbc.update("INSERT INTO users (id) VALUES (?)", ownerId);
        return insertConnection(ownerId);
    }

    private UUID insertConnection(UUID ownerId) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0)
                """, id, ownerId, new byte[32], new byte[12], offset(NOW), offset(NOW));
        return id;
    }

    private void insertPartialCombinedSnapshot() {
        jdbc.update("""
                INSERT INTO investment_os_portfolio_snapshots (
                    id, user_id, attempt_status, attempted_at, payload, created_at
                ) VALUES (?, ?, 'PARTIAL', ?, CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), userId, offset(NOW),
                "{\"status\":\"COMBINED_PARTIAL\",\"accounts\":{\"ACCOUNT_1\":\"CURRENT\",\"ACCOUNT_2\":\"STALE\"}}",
                offset(NOW));
    }

    private String riskEventDedupKey(UUID eventId, String eventState, Instant observedAt) {
        var transition = "RECOVERED".equals(eventState)
                ? "INTRADAY_BREACH_TO_RECOVERED" : "NORMAL_TO_" + eventState;
        return String.join("|", userId.toString(), TICKER, eventId.toString(), transition, observedAt.toString());
    }

    private void stubAccountPosition(
            String quantity, String sellable, Instant observedAt, boolean stale, boolean partial
    ) {
        var position = new PortfolioReadService.PositionView(
                TICKER, "Lunar", "US", bd(quantity), "USD", bd("9.5"), bd("10.35"),
                null, null, null, null, null, null, null, null, null, null, null,
                bd(sellable), observedAt);
        var portfolio = new PortfolioReadService.PortfolioView(UUID.randomUUID(), OBSERVED_AT,
                stale, null, partial, List.of(), List.of(), null, List.of(position), Map.of());
        when(portfolios.read(userId, connectionId)).thenReturn(portfolio);
    }

    private BrokerSurfaceResponse<BrokerSurfaceResponse.OrderBookView> freshOrderBook(String ask) {
        return freshOrderBook(ask, "100", "100");
    }

    private BrokerSurfaceResponse<BrokerSurfaceResponse.OrderBookView> freshOrderBook(
            String ask, String bidVolume, String askVolume
    ) {
        var data = new BrokerSurfaceResponse.OrderBookView(TICKER, BOOK_AS_OF, "USD",
                List.of(new BrokerSurfaceResponse.LevelView(bd(ask), bd(askVolume))),
                List.of(new BrokerSurfaceResponse.LevelView(bd("10.35"), bidVolume == null ? null : bd(bidVolume))));
        var source = new BrokerSurfaceResponse.ProviderProvenance(
                "TOSS", "/api/v1/orderbook", "USD", BOOK_AS_OF, OBSERVED_AT);
        return BrokerSurfaceResponse.available(data, List.of(source));
    }

    private String riskEvidence(
            UUID eventId, UUID revisionId, long revision, Instant observedAt, String eventState,
            EvidenceVariant evidenceVariant
    ) throws Exception {
        var recovered = "RECOVERED".equals(eventState);
        var previousState = recovered ? "INTRADAY_BREACH" : "NORMAL";
        var transition = recovered ? "INTRADAY_BREACH_TO_RECOVERED" : "NORMAL_TO_" + eventState;
        var severity = switch (eventState) {
            case "NEAR" -> "WARNING";
            case "RECOVERED" -> "INFO";
            default -> "CRITICAL";
        };
        var reasonCode = switch (eventState) {
            case "NEAR" -> "APPROVED_TRIGGER_NEAR";
            case "PRICE_RISK_BREACH" -> "APPROVED_TRIGGER_CLOSE_BREACHED";
            case "RECOVERED" -> "PRICE_CLEARED_NEAR_BAND";
            default -> "APPROVED_TRIGGER_INTRADAY_BREACHED";
        };
        var priceBasis = "PRICE_RISK_BREACH".equals(eventState) ? "REGULAR_CLOSE" : "LIVE_REGULAR";
        var priceSource = "TOSS";
        var observedPrice = recovered ? bd("12.00") : bd("10.35");
        var sessionDate = observedAt.atZone(java.time.ZoneId.of("America/New_York")).toLocalDate().toString();
        var observation = new LinkedHashMap<String, Object>();
        observation.put("sourceAsOf", observedAt.toString());
        observation.put("priceSource", priceSource);
        observation.put("priceSession", priceBasis);
        observation.put("priceBasis", priceBasis);
        observation.put("observedPrice", observedPrice);
        observation.put("triggerPrice", bd("11.1883"));
        observation.put("regularCloseSessionDate", "PRICE_RISK_BREACH".equals(eventState) ? sessionDate : null);
        observation.put("riskMarkStatus", null);
        observation.put("riskMarkReason", null);
        observation.put("thesisRevisionId", revisionId.toString());
        observation.put("thesisRevision", revision);
        observation.put("invalidationStatus", "CONFIRMED");
        observation.put("reasonCode", reasonCode);
        observation.put("sessionDate", sessionDate);

        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("eventId", eventId.toString());
        evidence.put("incidentId", eventId.toString());
        evidence.put("ticker", TICKER);
        evidence.put("ruleId", "CONFIRMED_THESIS_PRICE_TRIGGER");
        evidence.put("eventType", eventState);
        evidence.put("transition", transition);
        evidence.put("severity", severity);
        evidence.put("previousState", previousState);
        evidence.put("currentState", eventState);
        evidence.put("observedAt", observedAt.toString());
        evidence.put("occurredAt", observedAt.toString());
        evidence.put("resolvedAt", recovered ? observedAt.toString() : null);
        evidence.put("persistedAt", NOW.toString());
        evidence.put("dedupKey", riskEventDedupKey(eventId, eventState, observedAt));
        evidence.putAll(observation);
        evidence.put("latestObservation", observation);
        if (evidenceVariant == EvidenceVariant.MISSING_SOURCE_AS_OF) {
            observation.remove("sourceAsOf");
            evidence.remove("sourceAsOf");
        } else if (evidenceVariant == EvidenceVariant.MISMATCHED_THESIS_REVISION) {
            var wrongRevisionId = UUID.randomUUID().toString();
            observation.put("thesisRevisionId", wrongRevisionId);
            evidence.put("thesisRevisionId", wrongRevisionId);
        }
        return JSON.writeValueAsString(evidence);
    }

    private BrokerSurfaceResponse<BrokerSurfaceResponse.MarketCalendarView> freshMarketCalendar() throws Exception {
        var payload = new tools.jackson.databind.ObjectMapper().readTree("""
                {"today":{"date":"2026-10-20","dayMarket":null,"preMarket":null,
                 "regularMarket":{"startTime":"2026-10-20T09:30:00-04:00",
                                  "endTime":"2026-10-20T16:00:00-04:00"},
                 "afterMarket":null}}
                """);
        var data = new BrokerSurfaceResponse.MarketCalendarView("US", payload);
        var source = new BrokerSurfaceResponse.ProviderProvenance(
                "TOSS", "/api/v1/market-calendar/US", null, null, OBSERVED_AT);
        return BrokerSurfaceResponse.available(data, List.of(source));
    }

    private static OffsetDateTime offset(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
