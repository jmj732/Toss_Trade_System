package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.account.FreshPortfolioReadService;
import com.jmj.trade.account.BrokerSurfaceService;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerOrderGroup;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Quote;
import com.jmj.trade.broker.connection.BrokerSurfaceResponse;
import com.jmj.trade.connector.ConnectorService;
import com.jmj.trade.investment.InvestmentSellReadinessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpDefensivePrepareIntegrationTest extends PostgresIntegrationTest {
    private static final String TICKER = "AVT";
    private static final BigDecimal QUANTITY = new BigDecimal("2");
    private static final BigDecimal LIMIT = new BigDecimal("180.00");
    private static final Instant NOW = Instant.parse("2026-10-20T14:00:00Z");

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private UUID userId;
    private UUID connectionId;
    private UUID accountId;
    private LiveOrderActivationService activation;
    private BrokerAdapter quotes;
    private PreTradeRiskEngine risk;
    private FreshPortfolioReadService portfolios;
    private LiveOrderSafetyLedger safety;
    private InvestmentSellReadinessService readinessService;
    private BrokerOrderPort orders;
    private OrderIntentRepository intents;
    private McpOrderExecutionService service;
    private final Map<UUID, OrderIntent> proposedIntents = new ConcurrentHashMap<>();
    private final AtomicInteger proposalCount = new AtomicInteger();
    private ProposalGate proposalGate;

    @BeforeEach
    void prepareDatabaseAndExecutionService() {
        freshMigratedSchema();
        dataSource = pooledTestDataSource();
        ((com.zaxxer.hikari.HikariDataSource) dataSource).setMaximumPoolSize(4);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        userId = UUID.randomUUID();
        connectionId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        insertOwnedAccount();

        activation = mock(LiveOrderActivationService.class);
        risk = mock(PreTradeRiskEngine.class);
        portfolios = mock(FreshPortfolioReadService.class);
        quotes = mock(BrokerAdapter.class);
        orders = mock(BrokerOrderPort.class);
        safety = mock(LiveOrderSafetyLedger.class);
        readinessService = mock(InvestmentSellReadinessService.class);
        intents = mock(OrderIntentRepository.class);
        var brokerOrders = mock(BrokerOrderRepository.class);
        var attempts = mock(SubmissionAttemptRepository.class);
        var submissions = mock(OrderSubmissionService.class);

        when(safety.resolve(userId, connectionId, accountId))
                .thenReturn(new BrokerAccountRef(connectionId, "01", "LIVE", "****1234"));
        when(portfolios.read(userId, connectionId)).thenReturn(freshPortfolio());
        when(quotes.getQuote(any(BrokerConnectionRef.class), eq(TICKER))).thenReturn(new BrokerResponse<>(
                new Quote(new BrokerConnectionRef(connectionId), TICKER, Currency.USD,
                        LIMIT, LIMIT.subtract(new BigDecimal("0.01")), LIMIT,
                        null, NOW), BrokerOrderPort.localMetadata()));
        when(orders.getOrders(any(BrokerAccountRef.class), eq(BrokerOrderGroup.OPEN)))
                .thenReturn(new BrokerResponse<>(List.of(), BrokerOrderPort.localMetadata()));
        when(risk.preview(any())).thenReturn(new PreTradeRiskEngine.Decision(UUID.randomUUID(),
                PreTradeRiskEngine.Phase.APPROVAL, true, List.of(), UUID.randomUUID(), LIMIT,
                Currency.USD, 1, NOW));
        when(intents.findOwnedById(any(UUID.class), eq(userId), eq(connectionId)))
                .thenAnswer(invocation -> Optional.ofNullable(proposedIntents.get(invocation.getArgument(0))));
        when(intents.saveAndFlush(any(OrderIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            var proposal = (LiveOrderActivationService.Proposal) invocation.getArgument(1);
            var ttl = (Duration) invocation.getArgument(2);
            var proposalId = UUID.randomUUID();
            persistProposedIntent(proposalId, proposal, ttl);
            var ordinal = proposalCount.incrementAndGet();
            if (proposalGate != null && ordinal == 1) {
                proposalGate.inserted().countDown();
                if (!proposalGate.releaseFirst().await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("first proposal gate timed out");
                }
            }
            return proposalId;
        }).when(activation).propose(eq(userId), any(LiveOrderActivationService.Proposal.class), any(Duration.class));

        service = new McpOrderExecutionService(activation, risk, portfolios, quotes, orders, safety,
                intents, brokerOrders, attempts, jdbc, mock(UnknownAttemptReconciler.class), submissions,
                readinessService, transactions);
    }

    @AfterEach
    void closeDataSource() {
        if (dataSource instanceof com.zaxxer.hikari.HikariDataSource pool) pool.close();
    }

    @Test
    void concurrentPrepareCallsPersistOnlyOneProposalForAnOwnedConnectionAndTicker() throws Exception {
        proposalGate = new ProposalGate(new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Future<PrepareOutcome> first = executor.submit(() -> {
                start.await();
                return tryPrepare(new McpOrderExecutionService.PrepareCommand(
                        TICKER, "BUY", "LIMIT", QUANTITY, LIMIT));
            });
            Future<PrepareOutcome> second = executor.submit(() -> {
                start.await();
                proposalGate.secondStarted().countDown();
                return tryPrepare(new McpOrderExecutionService.PrepareCommand(
                        TICKER, "BUY", "LIMIT", QUANTITY, LIMIT));
            });

            start.countDown();
            assertThat(proposalGate.inserted().await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(proposalGate.secondStarted().await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitBrokerConnectionLockWait()).isTrue();
            proposalGate.releaseFirst().countDown();

            var outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(outcome -> outcome.result() != null).hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> outcome.failure() != null).hasSize(1);
            assertThat(outcomes.stream().map(PrepareOutcome::failure).filter(java.util.Objects::nonNull)
                    .findFirst().orElseThrow())
                    .isInstanceOf(LiveOrderActivationException.class)
                    .hasMessageContaining("active prepared order already exists");
        }

        assertThat(countPreparedOrders()).isEqualTo(1);
        verify(orders, never()).placeOrder(any(), any(), any());
    }

    @Test
    void defensivePreparePersistsDecisionAndEventLinksAndReturnsProposalId() {
        var decisionId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        insertDecisionAndEvent(decisionId, eventId);
        var readiness = new InvestmentSellReadinessService.Readiness(
                "READY", "READY", "READY", "READY", decisionId, eventId, TICKER, "REDUCE", NOW,
                UUID.randomUUID(), QUANTITY, new BigDecimal("8"), NOW, LIMIT.subtract(new BigDecimal("0.01")), LIMIT,
                LIMIT.subtract(new BigDecimal("0.01")), new BigDecimal("0.01"), new BigDecimal("0.55"),
                "LIVE_REGULAR", "TOSS", "USD", NOW.minusSeconds(2), NOW.minusSeconds(1),
                "TOSS/api/v1/market-calendar/US", "https://example.test/increments", new BigDecimal("0.01"),
                List.of(), NOW);
        when(readinessService.evaluate(userId, connectionId, decisionId, eventId)).thenReturn(readiness);
        when(portfolios.read(userId, connectionId)).thenReturn(freshPortfolioWithPosition());
        when(safety.resolve(userId, connectionId, accountId))
                .thenReturn(new BrokerAccountRef(connectionId, "01", "LIVE", "****1234"));
        when(risk.preview(any())).thenReturn(
                new PreTradeRiskEngine.Decision(UUID.randomUUID(), PreTradeRiskEngine.Phase.APPROVAL,
                        true, List.of(), UUID.randomUUID(), LIMIT, Currency.USD, 1, NOW));

        var result = service.prepareDefensive(userId, connectionId, decisionId, eventId,
                TICKER, QUANTITY, LIMIT.subtract(new BigDecimal("0.01")));

        assertThat(result.proposalId()).startsWith("ordp_");
        assertThat(result.decisionId()).isEqualTo(decisionId);
        assertThat(result.eventId()).isEqualTo(eventId);
        assertThat(result.readiness()).isEqualTo(readiness);
        var linkedOrder = jdbc.queryForMap("""
                SELECT investment_decision_id, investment_risk_event_id
                  FROM order_intents WHERE id = ?
                """, UUID.fromString(result.proposalId().substring("ordp_".length())));
        assertThat(linkedOrder.get("investment_decision_id")).isEqualTo(decisionId);
        assertThat(linkedOrder.get("investment_risk_event_id")).isEqualTo(eventId);
        verify(orders, never()).placeOrder(any(), any(), any());
    }

    @Test
    void currentRiskDecisionAndLiveQuoteFlowThroughRealReadinessIntoLinkedProposal() throws Exception {
        var fixture = insertCurrentRiskDecision(TICKER, BigDecimal.ZERO);
        var bookAsOf = NOW.minusSeconds(2);
        var observedAt = NOW.minusSeconds(1);
        var accountPortfolio = freshPortfolioFor(TICKER, new BigDecimal("2"), new BigDecimal("2"), observedAt);
        when(portfolios.read(userId, connectionId)).thenReturn(accountPortfolio);

        var snapshotPortfolios = mock(PortfolioReadService.class);
        when(snapshotPortfolios.read(userId, connectionId)).thenReturn(accountPortfolio);
        var brokerSurface = mock(BrokerSurfaceService.class);
        var connector = mock(ConnectorService.class);
        when(connector.orders(userId, connectionId, "OPEN")).thenReturn(List.of());
        when(brokerSurface.orderBook(userId, connectionId, TICKER))
                .thenReturn(freshExecutableBook(TICKER, bookAsOf, observedAt));
        when(brokerSurface.marketCalendar(userId, connectionId, "US", LocalDate.parse("2026-10-20")))
                .thenReturn(freshRegularSessionCalendar(observedAt));
        var environment = new MockEnvironment()
                .withProperty("investment.execution.readiness.max-spread-bps", "50")
                .withProperty("investment.execution.readiness.price-increments." + TICKER, "0.01")
                .withProperty("investment.execution.readiness.price-increment-source",
                        "https://example.test/us-equity-price-increments")
                .withProperty("investment.execution.readiness.price-increment-verified", "true");
        readinessService = realReadinessService(snapshotPortfolios, brokerSurface, connector, environment);
        service = new McpOrderExecutionService(activation, risk, portfolios, quotes, orders, safety,
                intents, mock(BrokerOrderRepository.class), mock(SubmissionAttemptRepository.class), jdbc,
                mock(UnknownAttemptReconciler.class), mock(OrderSubmissionService.class),
                readinessService, transactions);

        var result = service.prepareDefensive(userId, connectionId, fixture.decisionId(), fixture.eventId(),
                TICKER, new BigDecimal("2"), new BigDecimal("10.35"));

        assertThat(result.proposalId()).startsWith("ordp_");
        assertThat(result.readiness().actionStatus()).isEqualTo("READY");
        assertThat(result.readiness().sizingStatus()).isEqualTo("READY");
        assertThat(result.readiness().pricingStatus()).isEqualTo("READY");
        assertThat(result.readiness().quantity()).isEqualByComparingTo("2");
        assertThat(result.readiness().limitPrice()).isEqualByComparingTo("10.35");
        assertThat(result.decisionId()).isEqualTo(fixture.decisionId());
        assertThat(result.eventId()).isEqualTo(fixture.eventId());
        var linkedOrder = jdbc.queryForMap("""
                SELECT investment_decision_id, investment_risk_event_id
                  FROM order_intents WHERE id = ?
                """, UUID.fromString(result.proposalId().substring("ordp_".length())));
        assertThat(linkedOrder.get("investment_decision_id")).isEqualTo(fixture.decisionId());
        assertThat(linkedOrder.get("investment_risk_event_id")).isEqualTo(fixture.eventId());
        assertV64RejectsCrossUserLinks(UUID.fromString(result.proposalId().substring("ordp_".length())));
        verify(brokerSurface).orderBook(userId, connectionId, TICKER);
        verify(brokerSurface).marketCalendar(userId, connectionId, "US", LocalDate.parse("2026-10-20"));
        verify(orders, never()).placeOrder(any(), any(), any());
    }

    private void insertOwnedAccount() {
        jdbc.update("INSERT INTO users (id) VALUES (?)", userId);
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0)
                """, connectionId, userId, new byte[32], new byte[12], offset(NOW), offset(NOW));
        jdbc.update("INSERT INTO broker_accounts (id) VALUES (?)", accountId);
        jdbc.update("""
                INSERT INTO real_order_account_allowlist (
                    id, user_id, broker_connection_id, broker_account_id, toss_account_seq,
                    display_account_number, enabled, daily_limit_krw, daily_limit_usd, created_at
                ) VALUES (?, ?, ?, ?, '01', '****1234', TRUE, 1000000, 100000, ?)
                """, UUID.randomUUID(), userId, connectionId, accountId, offset(NOW));
    }

    private void persistProposedIntent(UUID id, LiveOrderActivationService.Proposal proposal, Duration ttl) {
        var created = Instant.now();
        var expires = created.plus(ttl);
        var reference = proposal.side() == OrderSide.BUY
                ? LIMIT : LIMIT.subtract(new BigDecimal("0.01"));
        var intent = OrderIntent.proposedLive(id, accountId, userId, connectionId, proposal.side(), proposal.type(),
                proposal.symbol(), proposal.quantity(), proposal.limitPrice(), proposal.currency());
        intent.stampProposal(created, expires);
        intent.stampProposalReferencePrice(reference);
        proposedIntents.put(id, intent);
        jdbc.update("""
                INSERT INTO order_intents (
                    id, broker_account_id, quantity, status, side, order_type, symbol, limit_price,
                    trading_currency, user_id, broker_connection_id, execution_mode, created_at,
                    expires_at, proposal_reference_price
                ) VALUES (?, ?, ?, 'PROPOSED', ?, ?, ?, ?, ?, ?, ?, 'LIVE', ?, ?, ?)
                """, id, accountId, proposal.quantity(), proposal.side().name(), proposal.type().name(),
                proposal.symbol(), proposal.limitPrice(), proposal.currency().name(), userId, connectionId,
                offset(created), offset(expires), reference);
    }

    private void insertDecisionAndEvent(UUID decisionId, UUID eventId) {
        jdbc.update("""
                INSERT INTO investment_decision_ledger (
                    decision_id, user_id, as_of, asset, action, reference_price, price_session, horizon,
                    alpha_thesis, invalidation, next_review_trigger, confidence, risk_policy_check, created_at,
                    target_remaining_quantity, target_broker_connection_id
                ) VALUES (?, ?, ?, ?, 'REDUCE', ?, 'LIVE_REGULAR', '12M', 'thesis', 'invalidation',
                          'review', 0.8, '{}'::jsonb, ?, ?, ?)
                """, decisionId, userId, offset(NOW), TICKER, LIMIT, offset(NOW),
                new BigDecimal("8"), connectionId);
        jdbc.update("""
                INSERT INTO monitoring_state_history (
                    id, user_id, scope, subject_key, previous_state, new_state, source_type,
                    source_event_id, evidence, observed_at, created_at
                ) VALUES (?, ?, 'THESIS_REVIEW', ?, 'NORMAL', 'INTRADAY_BREACH',
                          'CONFIRMED_THESIS_PRICE_TRIGGER', ?, '{}'::jsonb, ?, ?)
                """, eventId, userId, TICKER, UUID.randomUUID().toString(), offset(NOW), offset(NOW));
    }

    private RiskDecisionFixture insertCurrentRiskDecision(String ticker, BigDecimal targetRemaining) throws Exception {
        var decisionId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var revisionId = UUID.randomUUID();
        var eventObservedAt = NOW.minusSeconds(30);
        jdbc.update("""
                INSERT INTO investment_thesis_revisions (
                    id, user_id, ticker, revision, previous_status, new_status,
                    previous_trigger_price, new_trigger_price, thesis_snapshot,
                    actor_type, actor_user_id, recorded_at
                ) VALUES (?, ?, ?, 1, NULL, 'CONFIRMED', NULL, 11.1883, '{}'::jsonb,
                          'USER_SESSION', ?, ?)
                """, revisionId, userId, ticker, userId, offset(NOW));
        jdbc.update("""
                INSERT INTO investment_decision_ledger (
                    decision_id, user_id, as_of, asset, action, reference_price, price_session, horizon,
                    alpha_thesis, invalidation, next_review_trigger, confidence, risk_policy_check, created_at,
                    target_remaining_quantity, target_broker_connection_id
                ) VALUES (?, ?, ?, ?, 'EXIT', 10.35, 'LIVE_REGULAR', '12M', 'risk thesis',
                          'risk invalidation', 'review trigger', 0.8, '{}'::jsonb, ?, ?, ?)
                """, decisionId, userId, offset(NOW.minusSeconds(60)), ticker, offset(NOW), targetRemaining,
                connectionId);

        var observation = new LinkedHashMap<String, Object>();
        observation.put("sourceAsOf", eventObservedAt.toString());
        observation.put("priceSource", "TOSS");
        observation.put("priceSession", "LIVE_REGULAR");
        observation.put("priceBasis", "LIVE_REGULAR");
        observation.put("observedPrice", new BigDecimal("10.35"));
        observation.put("triggerPrice", new BigDecimal("11.1883"));
        observation.put("regularCloseSessionDate", null);
        observation.put("riskMarkStatus", null);
        observation.put("riskMarkReason", null);
        observation.put("thesisRevisionId", revisionId.toString());
        observation.put("thesisRevision", 1);
        observation.put("invalidationStatus", "CONFIRMED");
        observation.put("reasonCode", "APPROVED_TRIGGER_INTRADAY_BREACHED");
        observation.put("sessionDate", "2026-10-20");
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("eventId", eventId.toString());
        evidence.put("ticker", ticker);
        evidence.put("currentState", "INTRADAY_BREACH");
        evidence.put("latestObservation", observation);
        var encodedEvidence = new tools.jackson.databind.ObjectMapper().writeValueAsString(evidence);
        jdbc.update("""
                INSERT INTO monitoring_state_history (
                    id, user_id, scope, subject_key, previous_state, new_state, source_type,
                    source_event_id, evidence, observed_at, created_at
                ) VALUES (?, ?, 'THESIS_REVIEW', ?, 'NORMAL', 'INTRADAY_BREACH',
                          'CONFIRMED_THESIS_PRICE_TRIGGER', ?, CAST(? AS jsonb), ?, ?)
                """, eventId, userId, ticker, eventId.toString(), encodedEvidence,
                offset(eventObservedAt), offset(eventObservedAt));
        jdbc.update("""
                INSERT INTO monitoring_current_states (
                    user_id, scope, subject_key, state, evidence, observed_at, updated_at
                ) VALUES (?, 'THESIS_REVIEW', ?, 'INTRADAY_BREACH', CAST(? AS jsonb), ?, ?)
                """, userId, ticker, encodedEvidence, offset(eventObservedAt), offset(eventObservedAt));
        jdbc.update("""
                INSERT INTO investment_risk_event_decision_links (
                    id, user_id, ticker, event_id, decision_id, thesis_revision_id, linked_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), userId, ticker, eventId, decisionId, revisionId, offset(NOW));
        return new RiskDecisionFixture(decisionId, eventId);
    }

    private InvestmentSellReadinessService realReadinessService(PortfolioReadService portfolioRead,
                                                                 BrokerSurfaceService brokerSurface,
                                                                 ConnectorService connector,
                                                                 MockEnvironment environment) throws Exception {
        var constructor = InvestmentSellReadinessService.class.getDeclaredConstructor(
                JdbcTemplate.class, PortfolioReadService.class, BrokerSurfaceService.class,
                ConnectorService.class, org.springframework.core.env.Environment.class, Clock.class);
        constructor.setAccessible(true);
        return constructor.newInstance(jdbc, portfolioRead, brokerSurface, connector, environment,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PortfolioReadService.PortfolioView freshPortfolioFor(String ticker, BigDecimal quantity,
                                                                   BigDecimal sellable, Instant observedAt) {
        var position = new PortfolioReadService.PositionView(ticker, "Avista", "US", quantity, "USD",
                new BigDecimal("10.00"), new BigDecimal("10.35"), null, null, null, null, null, null,
                null, null, null, null, null, sellable, observedAt);
        return new PortfolioReadService.PortfolioView(UUID.randomUUID(), NOW, false, null, false,
                List.of(), List.of(), null, List.of(position), Map.of("USD",
                new PortfolioReadService.BuyingPowerView(new BigDecimal("10000"), NOW)));
    }

    private BrokerSurfaceResponse<BrokerSurfaceResponse.OrderBookView> freshExecutableBook(String ticker,
                                                                                              Instant bookAsOf,
                                                                                              Instant observedAt) {
        var data = new BrokerSurfaceResponse.OrderBookView(ticker, bookAsOf, "USD",
                List.of(new BrokerSurfaceResponse.LevelView(new BigDecimal("10.36"), new BigDecimal("100"))),
                List.of(new BrokerSurfaceResponse.LevelView(new BigDecimal("10.35"), new BigDecimal("100"))));
        var source = new BrokerSurfaceResponse.ProviderProvenance(
                "TOSS", "/api/v1/orderbook", "USD", bookAsOf, observedAt);
        return BrokerSurfaceResponse.available(data, List.of(source));
    }

    private BrokerSurfaceResponse<BrokerSurfaceResponse.MarketCalendarView> freshRegularSessionCalendar(
            Instant observedAt) throws Exception {
        var date = NOW.atZone(ZoneId.of("America/New_York")).toLocalDate();
        var offset = date.atTime(LocalTime.NOON).atZone(ZoneId.of("America/New_York")).getOffset();
        var start = OffsetDateTime.of(date, LocalTime.of(9, 30), offset);
        var end = OffsetDateTime.of(date, LocalTime.of(16, 0), offset);
        var payload = new tools.jackson.databind.ObjectMapper().readTree("""
                {"today":{"date":"%s","dayMarket":null,"preMarket":null,
                 "regularMarket":{"startTime":"%s","endTime":"%s"},
                 "afterMarket":null}}
                """.formatted(date, start, end));
        var data = new BrokerSurfaceResponse.MarketCalendarView("US", payload);
        var source = new BrokerSurfaceResponse.ProviderProvenance(
                "TOSS", "/api/v1/market-calendar/US", null, null, observedAt);
        return BrokerSurfaceResponse.available(data, List.of(source));
    }

    private void assertV64RejectsCrossUserLinks(UUID proposalId) {
        var foreignUserId = UUID.randomUUID();
        var foreignDecisionId = UUID.randomUUID();
        var foreignEventId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id) VALUES (?)", foreignUserId);
        jdbc.update("""
                INSERT INTO investment_decision_ledger (
                    decision_id, user_id, as_of, asset, action, reference_price, price_session, horizon,
                    alpha_thesis, invalidation, next_review_trigger, confidence, risk_policy_check, created_at
                ) VALUES (?, ?, ?, ?, 'HOLD', 10.35, 'LIVE_REGULAR', '12M', 'foreign thesis',
                          'foreign invalidation', 'review', 0.7, '{}'::jsonb, ?)
                """, foreignDecisionId, foreignUserId, offset(NOW), TICKER, offset(NOW));
        jdbc.update("""
                INSERT INTO monitoring_state_history (
                    id, user_id, scope, subject_key, previous_state, new_state, source_type,
                    source_event_id, evidence, observed_at, created_at
                ) VALUES (?, ?, 'THESIS_REVIEW', ?, 'NORMAL', 'INTRADAY_BREACH',
                          'FOREIGN_FIXTURE', ?, '{}'::jsonb, ?, ?)
                """, foreignEventId, foreignUserId, TICKER, foreignEventId.toString(),
                offset(NOW), offset(NOW));

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE order_intents SET investment_decision_id = ? WHERE id = ?
                """, foreignDecisionId, proposalId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE order_intents SET investment_risk_event_id = ? WHERE id = ?
                """, foreignEventId, proposalId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private int countPreparedOrders() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM order_intents
                 WHERE user_id = ? AND broker_connection_id = ? AND upper(symbol) = ?
                   AND status = 'PROPOSED' AND expires_at > now()
                """, Integer.class, userId, connectionId, TICKER);
    }

    private boolean awaitBrokerConnectionLockWait() throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var waiting = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity
                         WHERE wait_event_type = 'Lock'
                           AND query LIKE 'SELECT id FROM broker_connections%'
                    )
                    """, Boolean.class);
            if (Boolean.TRUE.equals(waiting)) return true;
            Thread.sleep(10);
        }
        return false;
    }

    private PrepareOutcome tryPrepare(McpOrderExecutionService.PrepareCommand command) {
        try {
            return new PrepareOutcome(service.prepare(userId, connectionId, command), null);
        } catch (RuntimeException failure) {
            return new PrepareOutcome(null, failure);
        }
    }

    private PortfolioReadService.PortfolioView freshPortfolio() {
        return new PortfolioReadService.PortfolioView(UUID.randomUUID(), NOW, false, null, false,
                List.of(), List.of(), null, List.of(), Map.of("USD",
                new PortfolioReadService.BuyingPowerView(new BigDecimal("10000"), NOW)));
    }

    private PortfolioReadService.PortfolioView freshPortfolioWithPosition() {
        var position = new PortfolioReadService.PositionView(TICKER, "Apple", "US", new BigDecimal("10"), "USD",
                LIMIT, LIMIT, null, null, null, null, null, null, null, null, null, null, null,
                new BigDecimal("10"), NOW);
        return new PortfolioReadService.PortfolioView(UUID.randomUUID(), NOW, false, null, false,
                List.of(), List.of(), null, List.of(position), Map.of("USD",
                new PortfolioReadService.BuyingPowerView(new BigDecimal("10000"), NOW)));
    }

    private static OffsetDateTime offset(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private record PrepareOutcome(McpOrderExecutionService.PrepareResult result, RuntimeException failure) {
    }

    private record RiskDecisionFixture(UUID decisionId, UUID eventId) {
    }

    private record ProposalGate(CountDownLatch inserted, CountDownLatch releaseFirst, CountDownLatch secondStarted) {
    }
}
