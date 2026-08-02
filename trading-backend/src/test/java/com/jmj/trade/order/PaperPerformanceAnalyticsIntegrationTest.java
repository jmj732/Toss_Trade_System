package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.broker.Currency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TradingBackendApplication.class)
@TestPropertySource(properties = {
        "paper-trading.commission-rate=0.001",
        "paper-trading.tax-rate=0.002",
        "paper-trading.amount-scale=2"
})
class PaperPerformanceAnalyticsIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderIntentRepository orderIntentRepository;

    @Autowired
    private OrderIntentTransitionService transitionService;

    @Autowired
    private PaperTradingBroker broker;

    private MockMvc mockMvc;
    private UUID brokerAccountId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        jdbc.execute("""
                TRUNCATE paper_order_workflow_commands, pre_trade_risk_decisions,
                         order_submission_outbox_events, order_submission_audit_logs,
                         reconciliation_checks, submission_attempts, submission_idempotency_keys,
                         order_intent_outbox_events, order_intent_audit_logs, execution_snapshots,
                         broker_orders, real_order_daily_reservations, real_order_account_allowlist, order_intents, broker_accounts, broker_connections, users
                CASCADE
                """);
        brokerAccountId = UUID.randomUUID();
        jdbc.update("INSERT INTO broker_accounts (id) VALUES (?)", brokerAccountId);
    }

    @Test
    void unavailableWhenConnectionHasNoFillsEver() throws Exception {
        var connectionId = insertConnection(USER_ID);

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/paper-performance", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unavailable").value(true))
                .andExpect(jsonPath("$.unavailableReason").value("PAPER_PERFORMANCE_NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void hidesAnotherUsersConnectionPerformance() throws Exception {
        var connectionId = insertConnection(USER_ID);
        trade(USER_ID, connectionId, OrderSide.BUY, "AAPL", "10", "100", Currency.USD, T0);

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/paper-performance", connectionId)
                        .with(user(OTHER_USER_ID.toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BROKER_CONNECTION_NOT_FOUND"));
    }

    @Test
    void rejectsMaxPointsOutsideTheAllowedRange() throws Exception {
        var connectionId = insertConnection(USER_ID);
        trade(USER_ID, connectionId, OrderSide.BUY, "AAPL", "10", "100", Currency.USD, T0);

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/paper-performance", connectionId)
                        .with(user(USER_ID.toString()))
                        .param("maxPoints", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAPER_PERFORMANCE_INPUT_INVALID"));
    }

    @Test
    void rejectsFromAfterTo() throws Exception {
        var connectionId = insertConnection(USER_ID);
        trade(USER_ID, connectionId, OrderSide.BUY, "AAPL", "10", "100", Currency.USD, T0);

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/paper-performance", connectionId)
                        .with(user(USER_ID.toString()))
                        .param("from", T0.plusSeconds(5).toString())
                        .param("to", T0.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAPER_PERFORMANCE_INPUT_INVALID"));
    }

    @Test
    void computesFifoRealizedPnlNetOfBuyAndSellFeesAndSellTax() throws Exception {
        var connectionId = insertConnection(USER_ID);
        trade(USER_ID, connectionId, OrderSide.BUY, "AAPL", "10", "100", Currency.USD, T0);
        trade(USER_ID, connectionId, OrderSide.SELL, "AAPL", "10", "120", Currency.USD, T0.plusSeconds(60));

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/paper-performance", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unavailable").value(false))
                .andExpect(jsonPath("$.data.byCurrency.USD.realizedPnl").value(195.4))
                .andExpect(jsonPath("$.data.byCurrency.USD.totalFees").value(2.2))
                .andExpect(jsonPath("$.data.byCurrency.USD.totalTax").value(2.4))
                .andExpect(jsonPath("$.data.byCurrency.USD.turnover").value(2200))
                .andExpect(jsonPath("$.data.byCurrency.USD.closedTradeCount").value(1))
                .andExpect(jsonPath("$.data.byCurrency.USD.winningTradeCount").value(1))
                .andExpect(jsonPath("$.data.byCurrency.USD.winRate").value(1.0))
                .andExpect(jsonPath("$.data.byCurrency.USD.unrealizedPnl").value(0))
                .andExpect(jsonPath("$.data.byCurrency.USD.maxDrawdown").value(0))
                .andExpect(jsonPath("$.data.byCurrency.KRW").doesNotExist());
    }

    @Test
    void marksOpenLotsToTheLatestFillPriceForUnrealizedPnl() throws Exception {
        var connectionId = insertConnection(USER_ID);
        trade(USER_ID, connectionId, OrderSide.BUY, "AAPL", "5", "50", Currency.USD, T0);
        trade(USER_ID, connectionId, OrderSide.BUY, "AAPL", "5", "60", Currency.USD, T0.plusSeconds(60));

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/paper-performance", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byCurrency.USD.unrealizedPnl").value(49.45))
                .andExpect(jsonPath("$.data.byCurrency.USD.realizedPnl").value(0))
                .andExpect(jsonPath("$.data.byCurrency.USD.closedTradeCount").value(0))
                .andExpect(jsonPath("$.data.byCurrency.USD.winRate").doesNotExist());
    }

    @Test
    void keepsKrwAndUsdFullySeparate() throws Exception {
        var connectionId = insertConnection(USER_ID);
        trade(USER_ID, connectionId, OrderSide.BUY, "AAPL", "10", "100", Currency.USD, T0);
        trade(USER_ID, connectionId, OrderSide.SELL, "AAPL", "10", "120", Currency.USD, T0.plusSeconds(60));
        trade(USER_ID, connectionId, OrderSide.BUY, "005930", "10", "70000", Currency.KRW, T0);
        trade(USER_ID, connectionId, OrderSide.SELL, "005930", "10", "60000", Currency.KRW, T0.plusSeconds(60));

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/paper-performance", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byCurrency.USD.realizedPnl").value(195.4))
                .andExpect(jsonPath("$.data.byCurrency.KRW.realizedPnl").value(-102500.0))
                .andExpect(jsonPath("$.data.byCurrency.KRW.winningTradeCount").value(0));
    }

    @Test
    void periodFiltersClosedTradesButFifoStillMatchesAgainstBuysBeforeTheWindow() throws Exception {
        var connectionId = insertConnection(USER_ID);
        var buyTime = T0;
        var sellTime = T0.plusSeconds(3600);
        var from = T0.plusSeconds(1800);
        trade(USER_ID, connectionId, OrderSide.BUY, "AAPL", "10", "100", Currency.USD, buyTime);
        trade(USER_ID, connectionId, OrderSide.SELL, "AAPL", "10", "120", Currency.USD, sellTime);

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/paper-performance", connectionId)
                        .with(user(USER_ID.toString()))
                        .param("from", from.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byCurrency.USD.realizedPnl").value(195.4))
                .andExpect(jsonPath("$.data.byCurrency.USD.closedTradeCount").value(1))
                .andExpect(jsonPath("$.data.byCurrency.USD.turnover").value(1200))
                .andExpect(jsonPath("$.data.byCurrency.USD.totalFees").value(1.2));
    }

    @Test
    void realizedPnlOnlyCountsClosesWhoseOwnSellFallsInsideTheRequestedPeriod() throws Exception {
        var connectionId = insertConnection(USER_ID);
        trade(USER_ID, connectionId, OrderSide.BUY, "AAPL", "10", "100", Currency.USD, T0);
        trade(USER_ID, connectionId, OrderSide.SELL, "AAPL", "10", "120", Currency.USD, T0.plusSeconds(60));
        trade(USER_ID, connectionId, OrderSide.BUY, "AAPL", "10", "100", Currency.USD, T0.plusSeconds(120));
        trade(USER_ID, connectionId, OrderSide.SELL, "AAPL", "10", "130", Currency.USD, T0.plusSeconds(180));

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/paper-performance", connectionId)
                        .with(user(USER_ID.toString()))
                        .param("from", T0.plusSeconds(150).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byCurrency.USD.realizedPnl").value(295.1))
                .andExpect(jsonPath("$.data.byCurrency.USD.closedTradeCount").value(1))
                .andExpect(jsonPath("$.data.byCurrency.USD.equityCurve.length()").value(1))
                .andExpect(jsonPath("$.data.byCurrency.USD.equityCurve[0].cumulativeRealizedPnl").value(295.1));
    }

    @Test
    void currencyStaysPresentWithZeroValuesWhenToPrecedesItsOnlyFill() throws Exception {
        var connectionId = insertConnection(USER_ID);
        trade(USER_ID, connectionId, OrderSide.BUY, "AAPL", "10", "100", Currency.USD, T0.plusSeconds(1000));

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/paper-performance", connectionId)
                        .with(user(USER_ID.toString()))
                        .param("to", T0.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unavailable").value(false))
                .andExpect(jsonPath("$.data.byCurrency.USD.realizedPnl").value(0))
                .andExpect(jsonPath("$.data.byCurrency.USD.unrealizedPnl").value(0))
                .andExpect(jsonPath("$.data.byCurrency.USD.closedTradeCount").value(0));
    }

    @Test
    void unrealizedAsOfToIgnoresFillsAfterTheCutoff() throws Exception {
        var connectionId = insertConnection(USER_ID);
        trade(USER_ID, connectionId, OrderSide.BUY, "AAPL", "5", "50", Currency.USD, T0);
        trade(USER_ID, connectionId, OrderSide.BUY, "AAPL", "5", "80", Currency.USD, T0.plusSeconds(864000));

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/paper-performance", connectionId)
                        .with(user(USER_ID.toString()))
                        .param("to", T0.plusSeconds(100).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byCurrency.USD.unrealizedPnl").value(0));
    }

    @Test
    void sellWithNoMatchingLotIsSilentlyClampedAndNotCountedAsAClosedTrade() throws Exception {
        var connectionId = insertConnection(USER_ID);
        trade(USER_ID, connectionId, OrderSide.SELL, "AAPL", "10", "50", Currency.USD, T0);

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/paper-performance", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byCurrency.USD.realizedPnl").value(0))
                .andExpect(jsonPath("$.data.byCurrency.USD.closedTradeCount").value(0));
    }

    @Test
    void computesMaxDrawdownOverThePeriodLocalEquityCurve() throws Exception {
        var connectionId = insertConnection(USER_ID);
        trade(USER_ID, connectionId, OrderSide.BUY, "AAA", "10", "100", Currency.USD, T0);
        trade(USER_ID, connectionId, OrderSide.SELL, "AAA", "10", "120", Currency.USD, T0.plusSeconds(60));
        trade(USER_ID, connectionId, OrderSide.BUY, "BBB", "10", "100", Currency.USD, T0.plusSeconds(120));
        trade(USER_ID, connectionId, OrderSide.SELL, "BBB", "10", "80", Currency.USD, T0.plusSeconds(180));

        mockMvc.perform(get("/api/v1/broker-connections/{connectionId}/paper-performance", connectionId)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byCurrency.USD.closedTradeCount").value(2))
                .andExpect(jsonPath("$.data.byCurrency.USD.winningTradeCount").value(1))
                .andExpect(jsonPath("$.data.byCurrency.USD.winRate").value(0.5))
                .andExpect(jsonPath("$.data.byCurrency.USD.maxDrawdown").value(203.4))
                .andExpect(jsonPath("$.data.byCurrency.USD.equityCurve.length()").value(2));
    }

    private UUID insertConnection(UUID userId) {
        var connectionId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0)
                """, connectionId, userId, new byte[17], new byte[12], offset(T0), offset(T0));
        return connectionId;
    }

    private void trade(
            UUID userId,
            UUID connectionId,
            OrderSide side,
            String symbol,
            String quantity,
            String price,
            Currency currency,
            Instant occurredAt
    ) {
        var intentId = UUID.randomUUID();
        orderIntentRepository.saveAndFlush(OrderIntent.proposed(
                intentId,
                brokerAccountId,
                userId,
                connectionId,
                side,
                OrderType.MARKET,
                symbol,
                new BigDecimal(quantity),
                null,
                currency));
        transitionService.approve(intentId, "test");
        transitionService.startRevalidation(intentId, "test");
        transitionService.markSubmissionPending(intentId, "test");
        broker.submit(new PaperTradingBroker.SubmitCommand(
                intentId, UUID.randomUUID().toString(), new BigDecimal(price), new BigDecimal(quantity),
                PaperTradingBroker.DispatchOutcome.ACKNOWLEDGED, occurredAt, "test"));
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
