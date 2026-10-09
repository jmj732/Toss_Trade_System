package com.jmj.trade.investment;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.marketdata.DataProviderRole;
import com.jmj.trade.marketdata.ProviderCatalog;
import com.jmj.trade.marketdata.ProviderRequest;
import com.jmj.trade.marketdata.ProviderValue;
import com.jmj.trade.marketdata.StockDataProvider;
import com.jmj.trade.marketdata.StockDataProviderId;
import com.jmj.trade.marketdata.StockDataProviderRegistry;
import com.jmj.trade.marketdata.StockAnalysisInput;
import com.jmj.trade.monitoring.MonitoringWatchlistService;
import com.jmj.trade.risk.RiskPolicyService;
import com.jmj.trade.sheets.InvestmentOsSheetModel;
import com.jmj.trade.sheets.InvestmentOsSheetProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CanonicalInvestmentContextIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("3e2fb11b-86e7-4db7-86b9-3bc127e51e76");
    private static final Set<String> TOSS_FIELDS = Set.of(
            "price.latestPrice", "price.regularClose", "price.session");
    private static final Set<String> SEC_FIELDS = Set.of(
            "fundamental.fiscalPeriod", "fundamental.reportedAt", "fundamental.fiscalYear",
            "fundamental.fiscalPeriodCode", "fundamental.cash", "fundamental.debt",
            "fundamental.basicShares", "fundamental.basicSharesBasis", "fundamental.dilutedShares",
            "fundamental.revenueTTM", "fundamental.ebitdaTTM", "fundamental.eps",
            "fundamental.fcfTTM", "fundamental.currency");
    private static final Set<String> ALPHA_FIELDS = Set.of(
            "consensus.horizon", "consensus.epsConsensus", "consensus.revenueConsensus",
            "consensus.currency", "consensus.observations", "fundamental.dilutedShares");

    private JdbcTemplate jdbc;
    private HikariDataSource dataSource;
    private DataSourceTransactionManager transactions;
    private ObjectMapper mapper;

    @BeforeEach
    void migrateAndSeed() {
        freshMigratedSchema();
        dataSource = pooledTestDataSource();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        mapper = new ObjectMapper();

        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO users (id) VALUES (?)", USER_ID);
        jdbc.update("""
                INSERT INTO monitoring_watchlist (id, user_id, symbol, status, levels, evidence,
                                                  observed_at, created_at, updated_at)
                VALUES (?, ?, 'AAPL', 'WATCH',
                        '{"prepare":{"min":1,"max":1},"confirm":{"min":2,"max":2},
                          "pullback":{"min":3,"max":3},"invalidate":{"min":0,"max":0}}'::jsonb,
                        '{}'::jsonb, ?, ?, ?)
                """, UUID.randomUUID(), USER_ID, now, now, now);
    }

    @AfterEach
    void closeTestPool() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void configuredSheetOwnerUsesThePersistedCombinedTossAndManualPortfolio() throws Exception {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var today = LocalDate.now(ZoneOffset.UTC).toString();
        var accountState = accountStateWithManualRows(today, now);
        var aggregate = InvestmentOsSheetModel.aggregate(accountState, now);
        var metrics = InvestmentOsSheetModel.portfolioMetrics(
                new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.metricsHeaders(), List.of()),
                accountState, now);
        var payload = mapper.createObjectNode();
        payload.set("aggregate", mapper.valueToTree(Map.of(
                "headers", aggregate.headers(), "rows", aggregate.rows())));
        payload.set("metrics", mapper.valueToTree(Map.of(
                "headers", metrics.headers(), "rows", metrics.rows())));
        payload.set("accountState", mapper.valueToTree(Map.of(
                "headers", accountState.headers(), "rows", accountState.rows())));
        payload.put("manualStatus", "OK");
        payload.put("manualAsOf", today);
        payload.put("manualReadAt", now.toString());
        payload.put("account1AsOf", now.toString());
        payload.put("source", "TOSS_API+MANUAL_SHEET");
        jdbc.update("""
                INSERT INTO investment_os_portfolio_snapshots (
                    id, user_id, attempt_status, attempted_at, error_code, payload, created_at
                ) VALUES (?, ?, 'SUCCEEDED', ?, NULL, ?::jsonb, ?)
                """, UUID.randomUUID(), USER_ID, OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                mapper.writeValueAsString(payload), OffsetDateTime.ofInstant(now, ZoneOffset.UTC));

        var portfolios = mock(PortfolioReadService.class);
        var contextService = service(new StockDataProviderRegistry(List.of()), portfolios, "");
        ReflectionTestUtils.setField(contextService, "investmentOsSheetProperties",
                new InvestmentOsSheetProperties(true, "sheet-id", USER_ID,
                        UUID.randomUUID(), InvestmentOsSheetModel.ACCOUNT_1,
                        Duration.ofMinutes(5), Duration.ZERO, Duration.ofMinutes(10)));

        var context = contextService.context(USER_ID);

        assertThat(context.portfolio().positions()).extracting(InvestmentContextService.PositionView::ticker)
                .containsExactly("AAPL", "MSFT");
        var overlap = context.portfolio().positions().getFirst();
        assertThat(overlap.quantity()).isEqualByComparingTo("5");
        assertThat(overlap.marketValue()).isEqualByComparingTo("500.00");
        assertThat(context.portfolio().positions().get(1).quantity()).isEqualByComparingTo("1");
        assertThat(overlap.weight()).isEqualByComparingTo(
                "0.7462686567164179104477611940298507");
        verifyNoInteractions(portfolios);
    }

    @Test
    void staleManualDateKeepsCombinedRiskValuesAndComputesPortfolioStress() throws Exception {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var oldManualDate = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(8);
        persistCombinedSnapshot(oldManualDate, now);
        insertConfirmedThesis("AAPL");
        insertConfirmedThesis("MSFT");
        insertRiskPriceSnapshot("AAPL", now);
        insertRiskPriceSnapshot("MSFT", now);
        insertCorrelatedCloseHistory(List.of("AAPL", "MSFT"), now);

        var service = configuredSheetContextService();
        var context = service.context(USER_ID);

        assertThat(context.portfolio().status()).isEqualTo("STALE");
        assertThat(context.portfolio().manualStale()).isTrue();
        assertThat(context.portfolio().riskNumbersAvailable()).isTrue();
        assertThat(context.portfolio().positions()).allSatisfy(position -> {
            assertThat(position.quantity()).isNotNull();
            assertThat(position.weight()).isNotNull();
        });
        assertThat(context.portfolio().positions().stream().filter(position -> position.ticker().equals("AAPL"))
                .findFirst().orElseThrow().accountsIncluded()).contains("ACCOUNT_1", "ACCOUNT_2");
        assertThat(context.portfolio().positions().stream().filter(position -> position.ticker().equals("MSFT"))
                .findFirst().orElseThrow().accountsIncluded()).isEqualTo("ACCOUNT_2");
        var aapl = context.securities().stream().filter(security -> security.ticker().equals("AAPL"))
                .findFirst().orElseThrow().risk();
        assertThat(aapl.status()).isEqualTo(InvestmentDataCalculator.DataStatus.STALE);
        assertThat(aapl.portfolioWeight()).isNotNull();
        assertThat(aapl.invalidationDownside()).isEqualByComparingTo("0.20000000");
        assertThat(aapl.plannedLossContribution()).isNotNull();
        assertThat(aapl.thesisFailureStressStatus()).isEqualTo(InvestmentDataCalculator.DataStatus.STALE);
        assertThat(aapl.thesisFailureStress()).isNotNull();
        assertThat(aapl.top2CorrelatedStatus()).isEqualTo(InvestmentDataCalculator.DataStatus.STALE);
        assertThat(aapl.top2CorrelatedStress()).isNotNull();
        assertThat(aapl.sizingEligible()).isFalse();
    }

    @Test
    void cashRowsWithCurrencyTickersAreNotExposedAsSecurityPositions() throws Exception {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var today = LocalDate.now(ZoneOffset.UTC).toString();
        var original = accountStateWithManualRows(today, now);
        var rows = new ArrayList<>(original.rows());
        for (var index = 0; index < rows.size(); index++) {
            var row = new ArrayList<>(rows.get(index));
            if ("ACCOUNT_2".equals(row.getFirst()) && "CASH_USD".equals(row.get(1))) {
                row.set(1, "USD");
                row.set(8, "");
                rows.set(index, row);
            }
        }
        var accountState = new InvestmentOsSheetModel.SheetTable(original.headers(), rows);
        var aggregate = InvestmentOsSheetModel.aggregate(accountState, now);
        var metrics = InvestmentOsSheetModel.portfolioMetrics(
                new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.metricsHeaders(), List.of()),
                accountState, now);
        var payload = mapper.createObjectNode();
        payload.set("aggregate", mapper.valueToTree(Map.of(
                "headers", aggregate.headers(), "rows", aggregate.rows())));
        payload.set("metrics", mapper.valueToTree(Map.of(
                "headers", metrics.headers(), "rows", metrics.rows())));
        payload.set("accountState", mapper.valueToTree(Map.of(
                "headers", accountState.headers(), "rows", accountState.rows())));
        payload.put("manualStatus", "OK");
        payload.put("manualAsOf", today);
        payload.put("manualReadAt", now.toString());
        payload.put("account1AsOf", now.toString());
        payload.put("source", "TOSS_API+MANUAL_SHEET");
        jdbc.update("""
                INSERT INTO investment_os_portfolio_snapshots (
                    id, user_id, attempt_status, attempted_at, error_code, payload, created_at
                ) VALUES (?, ?, 'SUCCEEDED', ?, NULL, ?::jsonb, ?)
                """, UUID.randomUUID(), USER_ID, OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                mapper.writeValueAsString(payload), OffsetDateTime.ofInstant(now, ZoneOffset.UTC));

        var positions = configuredSheetContextService().context(USER_ID).portfolio().positions();

        assertThat(positions).extracting(InvestmentContextService.PositionView::ticker)
                .containsExactly("AAPL", "MSFT");
    }

    @Test
    void staleAndUnconfiguredHeldRiskInputsKeepTopTwoStressUnknownWithoutThrowing() throws Exception {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        persistCombinedSnapshot(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(8), now);
        insertConfirmedThesis("AAPL");
        insertRiskPriceSnapshot("AAPL", now);
        insertRiskPriceSnapshot("MSFT", now);
        insertCorrelatedCloseHistory(List.of("AAPL", "MSFT"), now);

        var context = configuredSheetContextService().context(USER_ID);
        var aapl = context.securities().stream().filter(security -> security.ticker().equals("AAPL"))
                .findFirst().orElseThrow().risk();
        var msft = context.securities().stream().filter(security -> security.ticker().equals("MSFT"))
                .findFirst().orElseThrow().risk();

        assertThat(aapl.status()).isEqualTo(InvestmentDataCalculator.DataStatus.STALE);
        assertThat(aapl.plannedLossContribution()).isNotNull();
        assertThat(msft.status()).isEqualTo(InvestmentDataCalculator.DataStatus.NOT_CONFIGURED);
        assertThat(aapl.thesisFailureStress()).isNull();
        assertThat(aapl.thesisFailureStressStatus()).isEqualTo(InvestmentDataCalculator.DataStatus.STALE);
        assertThat(aapl.top2CorrelatedStress()).isNull();
        assertThat(aapl.top2CorrelatedStatus()).isEqualTo(InvestmentDataCalculator.DataStatus.STALE);
    }

    @Test
    void topTwoStressReportsInsufficientHistoryWhenNoPairHasThirtyPairedReturns() throws Exception {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var context = freshTwoPositionRiskContext(now);

        var risk = context.securities().stream().filter(security -> security.ticker().equals("AAPL"))
                .findFirst().orElseThrow().risk();

        assertThat(risk.top2CorrelatedStress()).isNull();
        assertThat(risk.top2CorrelatedStatus()).isEqualTo(InvestmentDataCalculator.DataStatus.INSUFFICIENT_HISTORY);
    }

    @Test
    void topTwoStressIsNotApplicableWhenEveryEvaluablePairIsNonpositive() throws Exception {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        freshTwoPositionRiskContext(now);
        var aaplCloses = alternatingCloses(100, true);
        var msftCloses = alternatingCloses(200, false);
        assertThat(InvestmentDataCalculator.correlation(dailyReturns(aaplCloses), dailyReturns(msftCloses)))
                .isNegative();
        insertCloseHistory("AAPL", aaplCloses, now);
        insertCloseHistory("MSFT", msftCloses, now);

        var risk = configuredSheetContextService().context(USER_ID).securities().stream()
                .filter(security -> security.ticker().equals("AAPL"))
                .findFirst().orElseThrow().risk();

        assertThat(risk.top2CorrelatedStress()).isNull();
        assertThat(risk.top2CorrelatedStatus()).isEqualTo(InvestmentDataCalculator.DataStatus.NOT_APPLICABLE);
    }

    @Test
    void topTwoStressIsUnverifiedWhenCorrelationIsUndefinedDespiteEnoughHistory() throws Exception {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        freshTwoPositionRiskContext(now);
        insertCloseHistory("AAPL", closes(100, 1), now);
        insertCloseHistory("MSFT", java.util.Collections.nCopies(31, BigDecimal.valueOf(100)), now);

        var risk = configuredSheetContextService().context(USER_ID).securities().stream()
                .filter(security -> security.ticker().equals("AAPL"))
                .findFirst().orElseThrow().risk();

        assertThat(risk.top2CorrelatedStress()).isNull();
        assertThat(risk.top2CorrelatedStatus()).isEqualTo(InvestmentDataCalculator.DataStatus.UNVERIFIED);
    }

    private InvestmentOsSheetModel.SheetTable accountStateWithManualRows(String today, Instant now) {
        var headers = new ArrayList<>(InvestmentOsSheetModel.accountHeaders());
        headers.add("asOf");
        var account1 = List.of("ACCOUNT_1", "AAPL", "HOLDING", "USD", "3", "80", "100", "300",
                "", "TOSS_API", "HIGH", now.toString(), "TOSS_QUOTE_API", now.toString(), "HELD", today);
        var account2 = List.of("ACCOUNT_2", "AAPL", "HOLDING", "USD", "2", "90", "100", "200",
                "", "MANUAL", "HIGH", "2026-10-04T08:00:00Z", "TOSS_QUOTE_API", now.toString(), "HELD", today);
        var manualOnly = List.of("ACCOUNT_2", "MSFT", "HOLDING", "USD", "1", "15", "20", "20",
                "", "MANUAL", "HIGH", "2026-10-04T08:00:00Z", "TOSS_QUOTE_API", now.toString(), "HELD", today);
        var account1Cash = List.of("ACCOUNT_1", "CASH_USD", "CASH", "USD", "", "", "", "", "100",
                "TOSS_API", "HIGH", now.toString(), "", "", "CASH", today);
        var account2Cash = List.of("ACCOUNT_2", "CASH_USD", "CASH", "USD", "", "", "", "", "50",
                "MANUAL", "HIGH", "2026-10-04T08:00:00Z", "", "", "CASH", today);
        return new InvestmentOsSheetModel.SheetTable(headers,
                List.of(account1, account2, manualOnly, account1Cash, account2Cash));
    }

    private void persistCombinedSnapshot(LocalDate manualAsOf, Instant now) throws Exception {
        var accountState = accountStateWithManualRows(manualAsOf.toString(), now);
        var aggregate = InvestmentOsSheetModel.aggregate(accountState, now);
        var metrics = InvestmentOsSheetModel.portfolioMetrics(
                new InvestmentOsSheetModel.SheetTable(InvestmentOsSheetModel.metricsHeaders(), List.of()),
                accountState, now);
        var payload = mapper.createObjectNode();
        payload.set("aggregate", mapper.valueToTree(Map.of("headers", aggregate.headers(), "rows", aggregate.rows())));
        payload.set("metrics", mapper.valueToTree(Map.of("headers", metrics.headers(), "rows", metrics.rows())));
        payload.set("accountState", mapper.valueToTree(Map.of("headers", accountState.headers(), "rows", accountState.rows())));
        payload.put("manualStatus", "OK");
        payload.put("manualAsOf", manualAsOf.toString());
        payload.put("manualReadAt", now.toString());
        payload.put("account1AsOf", now.toString());
        payload.put("source", "TOSS_API+MANUAL_SHEET");
        jdbc.update("""
                INSERT INTO investment_os_portfolio_snapshots (
                    id, user_id, attempt_status, attempted_at, error_code, payload, created_at
                ) VALUES (?, ?, 'SUCCEEDED', ?, NULL, ?::jsonb, ?)
                """, UUID.randomUUID(), USER_ID, OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                mapper.writeValueAsString(payload), OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    private InvestmentContextService configuredSheetContextService() {
        var service = service(new StockDataProviderRegistry(List.of()), null, "");
        ReflectionTestUtils.setField(service, "investmentOsSheetProperties",
                new InvestmentOsSheetProperties(true, "sheet-id", USER_ID,
                        UUID.randomUUID(), InvestmentOsSheetModel.ACCOUNT_1,
                        Duration.ofMinutes(5), Duration.ZERO, Duration.ofMinutes(10)));
        return service;
    }

    private InvestmentContextService.ContextView freshTwoPositionRiskContext(Instant now) throws Exception {
        persistCombinedSnapshot(LocalDate.now(ZoneId.of("Asia/Seoul")), now);
        for (var ticker : List.of("AAPL", "MSFT")) {
            insertConfirmedThesis(ticker);
            insertRiskPriceSnapshot(ticker, now);
        }
        return configuredSheetContextService().context(USER_ID);
    }

    private static List<BigDecimal> closes(long start, long step) {
        var result = new ArrayList<BigDecimal>();
        for (var index = 0; index <= 30; index++) {
            result.add(BigDecimal.valueOf(start + index * step));
        }
        return result;
    }

    private static List<BigDecimal> alternatingCloses(long start, boolean gainsFirst) {
        var result = new ArrayList<BigDecimal>();
        var close = BigDecimal.valueOf(start);
        var gain = new BigDecimal("1.10");
        var decline = new BigDecimal("0.90");
        result.add(close);
        for (var index = 0; index < 30; index++) {
            var positiveReturn = (index % 2 == 0) == gainsFirst;
            close = close.multiply(positiveReturn ? gain : decline);
            result.add(close);
        }
        return result;
    }

    private static List<BigDecimal> dailyReturns(List<BigDecimal> closes) {
        var result = new ArrayList<BigDecimal>();
        for (var index = 1; index < closes.size(); index++) {
            var prior = closes.get(index - 1);
            result.add(closes.get(index).subtract(prior).divide(prior, MathContext.DECIMAL128));
        }
        return result;
    }

    private void insertCloseHistory(String ticker, List<BigDecimal> closes, Instant now) {
        var inputSnapshotId = ensureInputSnapshot(ticker);
        for (var index = 0; index < closes.size(); index++) {
            var asOf = now.minus(Duration.ofDays(closes.size() - 1L - index));
            var date = OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC);
            jdbc.update("""
                    INSERT INTO investment_price_snapshots (
                        id, user_id, input_snapshot_id, ticker, as_of, session,
                        regular_close, regular_close_as_of, source, observed_at
                    ) VALUES (?, ?, ?, ?, ?, 'REGULAR_CLOSE', ?, ?, 'SYNTHETIC_TEST', ?)
                    """, UUID.randomUUID(), USER_ID, inputSnapshotId, ticker, date, closes.get(index), date, date);
        }
    }

    private void insertConfirmedThesis(String ticker) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO investment_thesis_states (
                    user_id, ticker, core_thesis, price_risk_trigger, price_risk_trigger_price,
                    invalidation_status, classification, updated_at
                ) VALUES (?, ?, 'Confirmed test thesis', 'Breaks below support', 80,
                          'CONFIRMED', 'COMPOUNDER', ?)
                """, USER_ID, ticker, now);
    }

    @Test
    void outsideIntervalQuoteIsNotEscalatedByTheQuoteWindowBeforeTheNextDeclaredInterval() throws Exception {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        insertStoredPrice("AAPL", now, """
                {"latestPrice":101,"latestPriceAsOf":"%s","regularClose":100,
                 "regularCloseAsOf":"2026-10-02T04:00:00Z","session":null,"source":"TOSS","status":"PARTIAL",
                 "sessionReason":"TOSS_QUOTE_OUTSIDE_DECLARED_INTERVALS","nextDeclaredIntervalStartsAt":"%s",
                 "regularCloseSessionDate":"2026-10-02","lastCompletedSessionDate":"2026-10-02",
                 "regularCloseValidUntil":"%s","regularCloseStatus":"OK"}
                """.formatted(now.minus(Duration.ofHours(3)), now.plus(Duration.ofHours(2)),
                now.plus(Duration.ofHours(20))));

        var security = service(new StockDataProviderRegistry(List.of()), null, "").context(USER_ID)
                .securities().getFirst();

        var price = security.price();
        assertThat(price.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(price.path("regularCloseStatus").asText()).isEqualTo("OK");
        assertThat(price.path("session").isNull()).isTrue();
        assertThat(price.path("sessionReason").asText()).isEqualTo("TOSS_QUOTE_OUTSIDE_DECLARED_INTERVALS");
        assertThat(price.path("latestPrice").decimalValue()).isEqualByComparingTo("101");
        assertThat(price.path("regularClose").decimalValue()).isEqualByComparingTo("100");
        assertThat(security.readiness().path("priceStatus").asText()).isEqualTo("PARTIAL");
    }

    @Test
    void storedSessionFactsExpireOnlyAtTheirDeclaredBoundaries() throws Exception {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        insertStoredPrice("AAPL", now, """
                {"latestPrice":101,"latestPriceAsOf":"%s","regularClose":100,
                 "regularCloseAsOf":"2026-10-02T04:00:00Z","session":null,"source":"TOSS","status":"PARTIAL",
                 "sessionReason":"TOSS_QUOTE_OUTSIDE_DECLARED_INTERVALS","nextDeclaredIntervalStartsAt":"%s",
                 "regularCloseSessionDate":"2026-10-02","lastCompletedSessionDate":"2026-10-02",
                 "regularCloseValidUntil":"%s","regularCloseStatus":"OK"}
                """.formatted(now.minus(Duration.ofHours(3)), now.minus(Duration.ofMinutes(1)),
                now.minus(Duration.ofMinutes(1))));

        var price = service(new StockDataProviderRegistry(List.of()), null, "").context(USER_ID)
                .securities().getFirst().price();

        assertThat(price.path("status").asText()).isEqualTo("STALE");
        assertThat(price.path("regularCloseStatus").asText()).isEqualTo("STALE");
        assertThat(price.path("latestPrice").decimalValue()).isEqualByComparingTo("101");
    }

    @Test
    void liveSessionQuoteKeepsTheQuoteWindowWhileTheRegularCloseIsJudgedBySessionDate() throws Exception {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        insertStoredPrice("AAPL", now, """
                {"latestPrice":101,"latestPriceAsOf":"%s","regularClose":100,
                 "regularCloseAsOf":"2026-10-02T04:00:00Z","session":"AFTER_HOURS","source":"TOSS","status":"OK",
                 "regularCloseSessionDate":"2026-10-02","lastCompletedSessionDate":"2026-10-02",
                 "regularCloseValidUntil":"%s","regularCloseStatus":"OK"}
                """.formatted(now.minus(Duration.ofHours(3)), now.plus(Duration.ofHours(20))));

        var price = service(new StockDataProviderRegistry(List.of()), null, "").context(USER_ID)
                .securities().getFirst().price();

        assertThat(price.path("status").asText()).isEqualTo("STALE");
        assertThat(price.path("regularCloseStatus").asText()).isEqualTo("OK");
        assertThat(price.path("latestPrice").decimalValue()).isEqualByComparingTo("101");
    }

    @Test
    void storedPriceWithoutSessionFactsIsUnverifiedNotWallClockStale() throws Exception {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        insertStoredPrice("AAPL", now, """
                {"latestPrice":101,"latestPriceAsOf":"%s","regularClose":100,
                 "regularCloseAsOf":"2026-10-02T04:00:00Z","session":null,"source":"TOSS","status":"PARTIAL"}
                """.formatted(now.minus(Duration.ofHours(3))));

        var price = service(new StockDataProviderRegistry(List.of()), null, "").context(USER_ID)
                .securities().getFirst().price();

        assertThat(price.path("status").asText()).isEqualTo("UNVERIFIED");
        assertThat(price.path("regularCloseStatus").asText()).isEqualTo("UNVERIFIED");
        assertThat(price.path("regularCloseSessionDate").asText()).isEqualTo("2026-10-02");
    }

    @Test
    void sessionFactsNeverOverrideSourceConflictOrRevalidateVeryOldQuotes() throws Exception {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        insertStoredPrice("AAPL", now, """
                {"latestPrice":101,"latestPriceAsOf":"%s","session":null,"source":"TOSS",
                 "status":"SOURCE_CONFLICT","sessionReason":"TOSS_QUOTE_OUTSIDE_DECLARED_INTERVALS",
                 "nextDeclaredIntervalStartsAt":"%s","regularCloseStatus":"DATA_MISSING"}
                """.formatted(now.minus(Duration.ofHours(3)), now.plus(Duration.ofHours(2))));
        var contextService = service(new StockDataProviderRegistry(List.of()), null, "");

        var conflicted = contextService.context(USER_ID).securities().getFirst().price();
        assertThat(conflicted.path("status").asText()).isEqualTo("SOURCE_CONFLICT");
        assertThat(conflicted.path("regularCloseStatus").asText()).isEqualTo("DATA_MISSING");

        insertStoredPrice("AAPL", now.plusSeconds(1), """
                {"latestPrice":101,"latestPriceAsOf":"%s","session":null,"source":"TOSS","status":"PARTIAL"}
                """.formatted(now.minus(Duration.ofDays(8))));
        assertThat(contextService.context(USER_ID).securities().getFirst().price().path("status").asText())
                .isEqualTo("STALE");
    }

    private void insertStoredPrice(String ticker, Instant asOf, String priceJson) throws Exception {
        var payload = mapper.createObjectNode();
        payload.put("asOf", asOf.toString());
        payload.set("price", mapper.readTree(priceJson));
        jdbc.update("""
                INSERT INTO investment_security_snapshots (id, user_id, ticker, as_of, payload, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                """, UUID.randomUUID(), USER_ID, ticker, OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC),
                mapper.writeValueAsString(payload), OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC));
    }

    private void insertRiskPriceSnapshot(String ticker, Instant now) throws Exception {
        var payload = mapper.createObjectNode();
        payload.put("asOf", now.toString());
        payload.set("price", mapper.readTree("""
                {"latestPrice":100,"latestPriceAsOf":"%s","regularClose":100,
                 "regularCloseAsOf":"%s","session":"REGULAR_CLOSE","status":"OK"}
                """.formatted(now, now)));
        jdbc.update("""
                INSERT INTO investment_security_snapshots (id, user_id, ticker, as_of, payload, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                """, UUID.randomUUID(), USER_ID, ticker, OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                mapper.writeValueAsString(payload), OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    private void insertCorrelatedCloseHistory(List<String> tickers, Instant now) {
        var inputIds = tickers.stream().collect(java.util.stream.Collectors.toMap(
                Function.identity(), this::ensureInputSnapshot));
        for (int day = 0; day <= 30; day++) {
            var asOf = now.minus(Duration.ofDays(30L - day));
            var date = OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC);
            for (var ticker : tickers) {
                var close = BigDecimal.valueOf(100L + day);
                jdbc.update("""
                        INSERT INTO investment_price_snapshots (
                            id, user_id, input_snapshot_id, ticker, as_of, session,
                            regular_close, regular_close_as_of, source, observed_at
                        ) VALUES (?, ?, ?, ?, ?, 'REGULAR_CLOSE', ?, ?, 'SYNTHETIC_TEST', ?)
                        """, UUID.randomUUID(), USER_ID, inputIds.get(ticker), ticker, date, close, date, date);
            }
        }
    }

    @Test
    void typedConsensusPersistsAllHorizonsAndSelectsNearestAnnualIndependentOfInputOrder() throws Exception {
        var today = LocalDate.now(ZoneOffset.UTC);
        var nearAnnual = today.plusDays(120);
        var farAnnual = today.plusDays(480);
        var nearQuarter = today.plusDays(45);
        var farQuarter = today.plusDays(135);
        var estimates = List.of(
                estimate("ANNUAL", farAnnual, "25", "2500"),
                estimate("QUARTERLY", nearQuarter, "4", "400"),
                estimate("ANNUAL", nearAnnual, "15", "1500"),
                estimate("QUARTERLY", farQuarter, "5", "500"));
        var order = new AtomicReference<>(estimates);
        var contextService = service(canonicalProviders(order), null, "");
        insertCompounderThesis("AAPL");

        assertThat(contextService.capture(USER_ID)).isEqualTo(1);
        var first = snapshot(contextService, "AAPL").path("consensus");
        assertThat(first.path("estimateType").asText()).isEqualTo("ANNUAL");
        assertThat(first.path("horizon").asText()).isEqualTo(nearAnnual.toString());
        assertThat(first.path("epsConsensus").decimalValue()).isEqualByComparingTo("15");
        assertThat(first.path("revenueConsensus").decimalValue()).isEqualByComparingTo("1500");
        assertThat(first.path("observations").size()).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM consensus_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' AND source = 'ALPHA_VANTAGE'
                   AND as_of = (SELECT max(as_of) FROM consensus_snapshots
                                 WHERE user_id = ? AND ticker = 'AAPL' AND source = 'ALPHA_VANTAGE')
                """, Integer.class, USER_ID, USER_ID)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT estimate_type) FROM consensus_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' AND source = 'ALPHA_VANTAGE'
                """, Integer.class, USER_ID)).isEqualTo(2);

        order.set(estimates.reversed());
        assertThat(contextService.capture(USER_ID)).isEqualTo(1);
        var second = snapshot(contextService, "AAPL").path("consensus");
        assertThat(second.path("estimateType").asText()).isEqualTo("ANNUAL");
        assertThat(second.path("horizon").asText()).isEqualTo(nearAnnual.toString());
        assertThat(second.path("epsConsensus").decimalValue()).isEqualByComparingTo("15");
        assertThat(second.path("revenueConsensus").decimalValue()).isEqualByComparingTo("1500");
        assertThat(horizonKeys(second.path("observations"))).containsExactly(
                "ANNUAL:" + nearAnnual, "ANNUAL:" + farAnnual,
                "QUARTERLY:" + nearQuarter, "QUARTERLY:" + farQuarter);
    }

    @Test
    void revisionsStayInSameAlphaHorizonAndTypeEvenWhenCurrencyIsUnknown() throws Exception {
        var today = LocalDate.now(ZoneOffset.UTC);
        var nearAnnual = today.plusDays(120);
        var nextAnnual = today.plusDays(480);
        var currentReference = Instant.now();
        insertConsensusHistory("AAPL", nearAnnual, "ANNUAL", "ALPHA_VANTAGE",
                currentReference.minus(Duration.ofDays(35)), "100", "10");
        insertConsensusHistory("AAPL", nearAnnual, "ANNUAL", "ALPHA_VANTAGE",
                currentReference.minus(Duration.ofDays(95)), "50", "5");
        insertConsensusHistory("AAPL", nearAnnual, "QUARTERLY", "ALPHA_VANTAGE",
                currentReference.minus(Duration.ofDays(33)), "900", "90");
        insertConsensusHistory("AAPL", nearAnnual, "QUARTERLY", "ALPHA_VANTAGE",
                currentReference.minus(Duration.ofDays(93)), "700", "70");
        insertConsensusHistory("AAPL", nextAnnual, "ANNUAL", "ALPHA_VANTAGE",
                currentReference.minus(Duration.ofDays(32)), "800", "80");
        insertConsensusHistory("AAPL", nextAnnual, "ANNUAL", "ALPHA_VANTAGE",
                currentReference.minus(Duration.ofDays(92)), "900", "90");
        insertConsensusHistory("AAPL", nearAnnual, "ANNUAL", "FMP",
                currentReference.minus(Duration.ofDays(31)), "600", "60");
        insertConsensusHistory("AAPL", nearAnnual, "ANNUAL", "FMP",
                currentReference.minus(Duration.ofDays(91)), "650", "65");
        insertCompounderThesis("AAPL");

        var provider = canonicalProviders(new AtomicReference<>(List.of(
                estimate("ANNUAL", nearAnnual, "15", "150"),
                estimate("ANNUAL", nextAnnual, "18", "180"),
                estimate("QUARTERLY", today.plusDays(45), "3", "30"))));
        var contextService = service(provider, null, "");
        assertThat(contextService.capture(USER_ID)).isEqualTo(1);

        var snapshot = snapshot(contextService, "AAPL");
        var consensus = snapshot.path("consensus");
        var revision = snapshot.path("revision");
        assertThat(consensus.path("currency").isNull()).isTrue();
        assertThat(revision.path("revenueRevision30D").path("value").decimalValue())
                .isEqualByComparingTo("50.0000");
        assertThat(revision.path("revenueRevision90D").path("value").decimalValue())
                .isEqualByComparingTo("200.0000");
        assertThat(revision.path("epsRevision30D").path("value").decimalValue())
                .isEqualByComparingTo("50.0000");
        assertThat(revision.path("epsRevision90D").path("value").decimalValue())
                .isEqualByComparingTo("200.0000");
        var valuation = snapshot.path("valuation");
        assertThat(valuation.path("evSalesForward").isNull()).isTrue();
        assertThat(valuation.path("evEbitdaForward").isNull()).isTrue();
        assertThat(valuation.path("forwardPE").isNull()).isTrue();
        assertThat(valuation.path("fcfYieldForward").isNull()).isTrue();
    }

    @Test
    void insufficientHistoryDiffersFromMissingCurrentConsensusMetric() throws Exception {
        var today = LocalDate.now(ZoneOffset.UTC);
        var nearestAnnual = today.plusDays(120);
        var provider = canonicalProviders(new AtomicReference<>(List.of(
                estimate("ANNUAL", nearestAnnual, null, "150"))));

        var contextService = service(provider, null, "");
        assertThat(contextService.capture(USER_ID)).isEqualTo(1);

        var snapshot = snapshot(contextService, "AAPL");
        var revision = snapshot.path("revision");
        assertThat(revision.path("revenueRevision30D").path("status").asText())
                .isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(revision.path("epsRevision30D").path("status").asText())
                .isEqualTo("DATA_MISSING");
        assertThat(revision.path("epsRevision30D").path("value").isNull()).isTrue();
    }

    @Test
    void futureLegacyFmpConsensusDoesNotFillMissingAlphaCurrentData() throws Exception {
        var futureHorizon = LocalDate.now(ZoneOffset.UTC).plusDays(120);
        var observedAt = Instant.now().minusSeconds(60);
        insertConsensusHistory("AAPL", futureHorizon, "ANNUAL", "FMP", observedAt, "777", "77");
        var contextService = service(canonicalProviders(new AtomicReference<>(List.of())), null, "");

        assertThat(contextService.capture(USER_ID)).isEqualTo(1);

        var consensus = snapshot(contextService, "AAPL").path("consensus");
        assertThat(consensus.path("status").asText()).isEqualTo("DATA_MISSING");
        assertThat(consensus.path("source").isNull()).isTrue();
        assertThat(consensus.path("revenueConsensus").isNull()).isTrue();
        assertThat(consensus.path("epsConsensus").isNull()).isTrue();
        assertThat(jdbc.queryForList("""
                SELECT source, revenue_consensus, eps_consensus FROM consensus_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' AND source = 'FMP'
                """, USER_ID)).singleElement().satisfies(row -> {
            assertThat(row.get("source")).isEqualTo("FMP");
            assertThat((BigDecimal) row.get("revenue_consensus")).isEqualByComparingTo("777");
            assertThat((BigDecimal) row.get("eps_consensus")).isEqualByComparingTo("77");
        });
    }

    @Test
    void bareAlphaFailuresAreVisibleWithoutChangingFourPriorConsensusSnapshots() throws Exception {
        var today = LocalDate.now(ZoneOffset.UTC);
        var nearAnnual = today.plusDays(120);
        var farAnnual = today.plusDays(480);
        var nearQuarter = today.plusDays(45);
        var farQuarter = today.plusDays(135);
        var observedAt = Instant.now().minus(Duration.ofDays(30));
        insertConsensusHistory("AAPL", nearAnnual, "ANNUAL", "ALPHA_VANTAGE",
                observedAt, "100", "10");
        insertConsensusHistory("AAPL", farAnnual, "ANNUAL", "ALPHA_VANTAGE",
                observedAt, "200", "20");
        insertConsensusHistory("AAPL", nearQuarter, "QUARTERLY", "ALPHA_VANTAGE",
                observedAt, "30", "3");
        insertConsensusHistory("AAPL", farQuarter, "QUARTERLY", "ALPHA_VANTAGE",
                observedAt, "40", "4");
        var priorSnapshots = jdbc.queryForList("""
                SELECT horizon, estimate_type, revenue_consensus, eps_consensus
                  FROM consensus_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' AND source = 'ALPHA_VANTAGE'
                 ORDER BY estimate_type, horizon
                """, USER_ID);
        var alphaFailureReason = new AtomicReference<>("DAILY_QUOTA_EXHAUSTED");
        var alphaQuotaFailure = provider(StockDataProviderId.ALPHA_VANTAGE, ALPHA_FIELDS, request -> {
            var capturedAt = Instant.now().minusSeconds(1);
            return List.of(
                    missing("consensus.epsConsensus", alphaFailureReason.get(), capturedAt),
                    missing("consensus.revenueConsensus", alphaFailureReason.get(), capturedAt));
        });
        var contextService = service(new StockDataProviderRegistry(
                List.of(tossProvider(), secProvider(), alphaQuotaFailure)), null, "");

        assertThat(contextService.capture(USER_ID)).isEqualTo(1);

        var security = contextService.context(USER_ID).securities().getFirst();
        assertThat(security.consensus().path("missingReason").asText())
                .isEqualTo("DAILY_QUOTA_EXHAUSTED");
        assertThat(security.readiness().path("missingFields").toString())
                .contains("consensus.provider.DAILY_QUOTA_EXHAUSTED");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM investment_pipeline_state
                 WHERE user_id = ? AND pipeline = 'SECURITY_DATA'
                """, String.class, USER_ID)).isEqualTo("PARTIAL");
        assertThat(jdbc.queryForObject("""
                SELECT last_error FROM investment_pipeline_state
                 WHERE user_id = ? AND pipeline = 'SECURITY_DATA'
                """, String.class, USER_ID)).isEqualTo("PROVIDER_DAILY_QUOTA_EXHAUSTED");
        assertThat(jdbc.queryForList("""
                SELECT horizon, estimate_type, revenue_consensus, eps_consensus
                  FROM consensus_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' AND source = 'ALPHA_VANTAGE'
                 ORDER BY estimate_type, horizon
                """, USER_ID)).containsExactlyElementsOf(priorSnapshots);

        alphaFailureReason.set("INVALID_API_KEY");
        assertThat(contextService.capture(USER_ID)).isEqualTo(1);
        security = contextService.context(USER_ID).securities().getFirst();
        assertThat(security.consensus().path("missingReason").asText()).isEqualTo("INVALID_API_KEY");
        assertThat(security.readiness().path("missingFields").toString())
                .contains("consensus.provider.INVALID_API_KEY");
        assertThat(jdbc.queryForObject("""
                SELECT last_error FROM investment_pipeline_state
                 WHERE user_id = ? AND pipeline = 'SECURITY_DATA'
                """, String.class, USER_ID)).isEqualTo("PROVIDER_INVALID_API_KEY");
        assertThat(jdbc.queryForList("""
                SELECT horizon, estimate_type, revenue_consensus, eps_consensus
                  FROM consensus_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' AND source = 'ALPHA_VANTAGE'
                 ORDER BY estimate_type, horizon
                """, USER_ID)).containsExactlyElementsOf(priorSnapshots);
    }

    @Test
    void providerFailureNormalizationIgnoresOrdinaryBareMissingData() {
        assertThat(InvestmentContextService.providerFailure(
                inputWithMissingReason(StockDataProviderId.SEC, "fundamental.cash", "DATA_NOT_PRESENT")))
                .isNull();
        assertThat(InvestmentContextService.providerFailure(
                inputWithMissingReason(StockDataProviderId.ALPHA_VANTAGE,
                        "consensus.epsConsensus", "DATA_NOT_PRESENT")))
                .isNull();
        assertThat(InvestmentContextService.providerFailure(
                inputWithMissingReason(StockDataProviderId.ALPHA_VANTAGE,
                        "consensus.epsConsensus", "DAILY_QUOTA_EXHAUSTED")))
                .isEqualTo("PROVIDER_DAILY_QUOTA_EXHAUSTED");
        assertThat(InvestmentContextService.providerFailure(
                inputWithMissingReason(StockDataProviderId.ALPHA_VANTAGE,
                        "consensus.epsConsensus", "INVALID_API_KEY")))
                .isEqualTo("PROVIDER_INVALID_API_KEY");
        for (var code : List.of("API_KEY_UNAVAILABLE", "RATE_LIMITED", "PREMIUM_ENDPOINT")) {
            assertThat(InvestmentContextService.providerFailure(
                    inputWithMissingReason(StockDataProviderId.ALPHA_VANTAGE,
                            "consensus.epsConsensus", code)))
                    .isEqualTo("PROVIDER_" + code);
        }
        assertThat(InvestmentContextService.providerFailure(
                inputWithMissingReason(StockDataProviderId.SEC, "fundamental.cash", "PROVIDER_HTTP_402")))
                .isEqualTo("PROVIDER_HTTP_402");
    }

    @Test
    void marketCapsUseRegularCloseAndShareDatesIndependentlyAndKeepDilutedValueSeparate() {
        var provider = canonicalProviders(new AtomicReference<>(List.of(
                estimate("ANNUAL", LocalDate.now(ZoneOffset.UTC).plusDays(120), "15", "150"))));
        assertThat(service(provider, null, "").capture(USER_ID)).isEqualTo(1);

        var row = jdbc.queryForMap("""
                SELECT market_cap, market_cap_as_of, basic_shares, diluted_shares,
                       fully_diluted_market_cap, fully_diluted_market_cap_as_of,
                       enterprise_value, enterprise_value_as_of, balance_sheet_as_of,
                       field_provenance::text
                  FROM fundamental_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' AND source = 'SEC'
                """, USER_ID);
        assertThat((BigDecimal) row.get("market_cap")).isEqualByComparingTo("100000");
        assertThat((BigDecimal) row.get("basic_shares")).isEqualByComparingTo("1000");
        assertThat((BigDecimal) row.get("diluted_shares")).isEqualByComparingTo("1500");
        assertThat((BigDecimal) row.get("fully_diluted_market_cap")).isEqualByComparingTo("150000");
        assertThat((BigDecimal) row.get("enterprise_value")).isEqualByComparingTo("100150");
        var marketCapAsOf = instant(row.get("market_cap_as_of"));
        var enterpriseValueAsOf = instant(row.get("enterprise_value_as_of"));
        var balanceSheetAsOf = instant(row.get("balance_sheet_as_of"));
        assertThat(marketCapAsOf).isEqualTo(closeAsOf());
        assertThat(balanceSheetAsOf).isAfter(marketCapAsOf);
        assertThat(enterpriseValueAsOf).isEqualTo(balanceSheetAsOf);
        var provenance = mapper.readTree(row.get("field_provenance").toString());
        assertThat(provenance.path("marketCap").path("priceSource").asText()).isEqualTo("TOSS");
        assertThat(provenance.path("marketCap").path("sharesIdentifier").asText())
                .isEqualTo("EntityCommonStockSharesOutstanding");
        assertThat(Instant.parse(provenance.path("marketCap").path("sharesAsOf").asText()))
                .isEqualTo(basicSharesAsOf());
        assertThat(Instant.parse(provenance.path("fullyDilutedMarketCap").path("sharesAsOf").asText()))
                .isEqualTo(LocalDate.now(ZoneOffset.UTC).minusDays(5).atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    @Test
    void valuationCalculatesAvailableTrailingMetricsWithoutThesisClassification() {
        var provider = canonicalProviders(new AtomicReference<>(List.of()));
        var contextService = service(provider, null, "");

        assertThat(contextService.capture(USER_ID)).isEqualTo(1);

        var valuation = contextService.context(USER_ID).securities().getFirst().valuation();
        assertThat(valuation.path("classification").isNull()).isTrue();
        assertThat(valuation.path("evSalesTTM").decimalValue()).isEqualByComparingTo("100.1500");
        assertThat(valuation.path("evEbitdaTTM").decimalValue()).isEqualByComparingTo("500.7500");
        assertThat(valuation.path("fcfYieldTTM").decimalValue()).isEqualByComparingTo("0.00100000");
        assertThat(valuation.path("status").asText()).isNotEqualTo("NOT_APPLICABLE");
    }

    @Test
    void valuationKeepsNegativeEnterpriseValueMultiplesAndNegativeFcfYield() {
        var estimates = new AtomicReference<>(List.<Estimate>of());
        var providers = new StockDataProviderRegistry(List.of(
                tossProvider(), secProvider("200000", "200", "-100"), alphaProvider(estimates)));
        var contextService = service(providers, null, "");

        assertThat(contextService.capture(USER_ID)).isEqualTo(1);

        var valuation = contextService.context(USER_ID).securities().getFirst().valuation();
        assertThat(valuation.path("evSalesTTM").decimalValue()).isEqualByComparingTo("-99.8000");
        assertThat(valuation.path("evEbitdaTTM").decimalValue()).isEqualByComparingTo("-499.0000");
        assertThat(valuation.path("fcfYieldTTM").decimalValue()).isEqualByComparingTo("-0.00100000");
    }

    @Test
    void missingEbitdaDoesNotHideAvailableTtmValuationAndHasFieldReason() {
        var providers = new StockDataProviderRegistry(List.of(
                tossProvider(), secProvider("50", "200", "100", null),
                alphaProvider(new AtomicReference<>(List.of()))));
        var contextService = service(providers, null, "");
        assertThat(contextService.capture(USER_ID)).isEqualTo(1);

        var security = contextService.context(USER_ID).securities().getFirst();
        var valuation = security.valuation();
        assertThat(valuation.path("evSalesTTM").decimalValue()).isEqualByComparingTo("100.1500");
        assertThat(valuation.path("metricStatuses").path("evEbitdaTTM").asText()).isEqualTo("DATA_MISSING");
        assertThat(valuation.path("metricReasons").path("evEbitdaTTM").asText()).isEqualTo("EBITDA_TTM_MISSING");
        assertThat(StreamSupport.stream(security.readiness().path("missingFields").spliterator(), false)
                .map(JsonNode::asText)).contains("fundamentals.ebitdaTTM.DATA_NOT_PRESENT");
        assertThat(valuation.path("metricProvenance").path("evSalesTTM").path("inputs")
                .path("revenueTTM").path("source").asText()).isEqualTo("SEC");
        assertThat(valuation.path("metricProvenance").path("evSalesTTM").path("formula").asText())
                .isEqualTo("enterpriseValue / revenueTTM");
        assertThat(security.readiness().path("consensusStatus").asText()).isEqualTo("DATA_MISSING");
        assertThat(security.risk().status()).isEqualTo(InvestmentDataCalculator.DataStatus.NOT_CONFIGURED);
        assertThat(security.readiness().path("riskStatus").asText()).isEqualTo("NOT_CONFIGURED");
        assertThat(security.readiness().path("overallDataStatus").asText()).isEqualTo("PARTIAL");
    }

    @Test
    void staleTtmInputChangesOnlyItsMetricAndRetainsReasonAcrossContextReads() throws Exception {
        var contextService = service(canonicalProviders(new AtomicReference<>(List.of())), null, "");
        assertThat(contextService.capture(USER_ID)).isEqualTo(1);

        var stored = jdbc.queryForObject("""
                SELECT payload::text FROM investment_security_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' ORDER BY as_of DESC LIMIT 1
                """, String.class, USER_ID);
        var payload = (tools.jackson.databind.node.ObjectNode) mapper.readTree(stored);
        var valuation = (tools.jackson.databind.node.ObjectNode) payload.get("valuation");
        var metricProvenance = (tools.jackson.databind.node.ObjectNode) valuation.get("metricProvenance");
        var evSalesInputs = (tools.jackson.databind.node.ObjectNode) metricProvenance
                .path("evSalesTTM").path("inputs");
        var revenueInput = (tools.jackson.databind.node.ObjectNode) evSalesInputs.get("revenueTTM");
        revenueInput.put("asOf", LocalDate.now(ZoneOffset.UTC).minusDays(300)
                .atStartOfDay().toInstant(ZoneOffset.UTC).toString());
        var readAsOf = OffsetDateTime.now(ZoneOffset.UTC);
        payload.put("asOf", readAsOf.toInstant().toString());
        jdbc.update("""
                INSERT INTO investment_security_snapshots (id, user_id, ticker, as_of, payload, created_at)
                VALUES (?, ?, 'AAPL', ?, CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), USER_ID, readAsOf, mapper.writeValueAsString(payload), readAsOf);

        for (int read = 0; read < 2; read++) {
            var security = contextService.context(USER_ID).securities().getFirst();
            assertThat(security.valuation().path("metricStatuses").path("evSalesTTM").asText())
                    .isEqualTo("STALE");
            assertThat(security.valuation().path("metricReasons").path("evSalesTTM").asText())
                    .isEqualTo("INPUTS_STALE");
            assertThat(security.valuation().path("metricStatuses").path("evEbitdaTTM").asText())
                    .isEqualTo("OK");
        }
    }

    @Test
    void nonpositiveEbitdaDenominatorIsNotApplicableWithExplicitReason() {
        var providers = new StockDataProviderRegistry(List.of(
                tossProvider(), secProvider("50", "200", "100", "0"),
                alphaProvider(new AtomicReference<>(List.of()))));
        var contextService = service(providers, null, "");
        assertThat(contextService.capture(USER_ID)).isEqualTo(1);

        var valuation = contextService.context(USER_ID).securities().getFirst().valuation();
        assertThat(valuation.path("evEbitdaTTM").isNull()).isTrue();
        assertThat(valuation.path("metricStatuses").path("evEbitdaTTM").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(valuation.path("metricReasons").path("evEbitdaTTM").asText())
                .isEqualTo("NON_POSITIVE_DENOMINATOR");
    }

    @Test
    void issuerCurrencyIsDisplayOnlyAndDoesNotSubstituteForMissingEstimateCurrency() {
        var estimates = new AtomicReference<>(List.of(
                estimate("ANNUAL", LocalDate.now(ZoneOffset.UTC).plusDays(120), "15", "1500")));
        var contextService = service(canonicalProviders(estimates), null, "");
        assertThat(contextService.capture(USER_ID)).isEqualTo(1);

        var valuation = contextService.context(USER_ID).securities().getFirst().valuation();
        assertThat(valuation.path("displayCurrency").asText()).isEqualTo("USD");
        assertThat(valuation.path("displayCurrencySource").asText()).isEqualTo("SEC");
        assertThat(valuation.path("displayCurrencySourceAsOf").isTextual()).isTrue();
        assertThat(valuation.path("forwardCurrency").isNull()).isTrue();
        assertThat(valuation.path("evSalesForward").isNull()).isTrue();
        assertThat(valuation.path("metricReasons").path("evSalesForward").asText())
                .isEqualTo("CONSENSUS_CURRENCY_UNVERIFIED");
        assertThat(contextService.context(USER_ID).securities().getFirst().consensus().path("currency").isNull())
                .isTrue();
    }

    @Test
    void captureAndContextRetainFourHeldPositionsAndAddTwoUnheldSymbols() {
        var symbols = List.of("AAPL", "MSFT", "NVDA", "AMZN");
        var connectionId = seedHeldPositions(symbols);
        var portfolio = mock(PortfolioReadService.class);
        var positionViews = symbols.stream().map(symbol -> portfolioPosition(symbol, Instant.now())).toList();
        var account = new PortfolioReadService.AccountView("CASH", "TEST",
                Map.of("USD", new BigDecimal("400"), "KRW", BigDecimal.ZERO),
                Map.of("USD", new BigDecimal("400"), "KRW", BigDecimal.ZERO),
                Map.of("USD", new BigDecimal("4000")), Map.of("USD", BigDecimal.ZERO),
                Map.of("USD", BigDecimal.ZERO), Map.of("USD", BigDecimal.ZERO),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Instant.now());
        when(portfolio.read(USER_ID, connectionId)).thenReturn(new PortfolioReadService.PortfolioView(
                UUID.randomUUID(), Instant.now(), false, null, false, List.of(), List.of(), account,
                positionViews, Map.of()));

        var contextService = service(canonicalProviders(new AtomicReference<>(List.of(
                estimate("ANNUAL", LocalDate.now(ZoneOffset.UTC).plusDays(120), "15", "150")))),
                portfolio, "GOOGL,VST");
        assertThat(contextService.capture(USER_ID)).isEqualTo(6);

        var context = contextService.context(USER_ID);
        assertThat(context.securities()).extracting(InvestmentContextService.SecurityView::ticker)
                .containsExactly("AAPL", "AMZN", "GOOGL", "MSFT", "NVDA", "VST");
        assertThat(context.securities().stream().filter(security -> security.position() != null)
                .map(InvestmentContextService.SecurityView::ticker))
                .containsExactlyInAnyOrderElementsOf(symbols);
        assertThat(context.securities().stream().filter(security -> security.position() == null)
                .map(InvestmentContextService.SecurityView::ticker))
                .containsExactlyInAnyOrder("GOOGL", "VST");
        assertThat(context.securities().stream().filter(security -> security.position() != null)
                .map(security -> security.position().weight()))
                .containsOnly(new BigDecimal("0.25"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM investment_security_snapshots
                 WHERE user_id = ? AND ticker IN ('AAPL','AMZN','GOOGL','MSFT','NVDA','VST')
                """, Integer.class, USER_ID)).isEqualTo(6);
    }

    @Test
    void portfolioWeightRemainsMissingWhenCurrencyBucketsDoNotReconcileToHeldPositions() {
        var symbols = List.of("AAPL", "MSFT");
        var connectionId = seedHeldPositions(symbols);
        var portfolio = mock(PortfolioReadService.class);
        var positionViews = List.of(portfolioPosition("AAPL", "USD", Instant.now()),
                portfolioPosition("MSFT", "KRW", Instant.now()));
        var totals = Map.of("USD", new BigDecimal("100"), "KRW", new BigDecimal("80"));
        var account = new PortfolioReadService.AccountView("CASH", "TEST",
                totals, totals, totals, Map.of(), Map.of(), Map.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Instant.now());
        when(portfolio.read(USER_ID, connectionId)).thenReturn(new PortfolioReadService.PortfolioView(
                UUID.randomUUID(), Instant.now(), false, null, false, List.of(), List.of(), account,
                positionViews, Map.of()));
        var contextService = service(canonicalProviders(new AtomicReference<>(List.of())), portfolio, "");

        assertThat(contextService.context(USER_ID).securities()).filteredOn(security -> security.position() != null)
                .extracting(security -> security.position().weight())
                .containsOnlyNulls();
    }

    private StockDataProviderRegistry canonicalProviders(AtomicReference<List<Estimate>> estimates) {
        return new StockDataProviderRegistry(List.of(tossProvider(), secProvider(), alphaProvider(estimates)));
    }

    private StockDataProvider tossProvider() {
        var closeDate = LocalDate.now(ZoneOffset.UTC).minusDays(2);
        var closeAsOf = closeDate.atTime(20, 0).toInstant(ZoneOffset.UTC);
        return provider(StockDataProviderId.TOSS, TOSS_FIELDS, request -> {
            var observedAt = Instant.now().minusSeconds(1);
            return List.of(
                    decimal("price.latestPrice", "105", "USD", observedAt, "LAST"),
                    decimal("price.regularClose", "100", "USD", closeAsOf, "REGULAR_CLOSE"),
                    text("price.session", "REGULAR_CLOSE", observedAt));
        });
    }

    private StockDataProvider secProvider() {
        return secProvider("50", "200", "100");
    }

    private StockDataProvider secProvider(String cashValue, String debtValue, String fcfValue) {
        return secProvider(cashValue, debtValue, fcfValue, "200");
    }

    private StockDataProvider secProvider(String cashValue, String debtValue, String fcfValue, String ebitdaValue) {
        var period = LocalDate.now(ZoneOffset.UTC).minusDays(3);
        var sharesAsOf = LocalDate.now(ZoneOffset.UTC).minusDays(4);
        var balanceAsOf = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);
        return provider(StockDataProviderId.SEC, SEC_FIELDS, request -> List.of(
                text("fundamental.fiscalPeriod", period.toString(), period.atStartOfDay().toInstant(ZoneOffset.UTC)),
                text("fundamental.reportedAt", balanceAsOf.toString(), balanceAsOf),
                text("fundamental.fiscalYear", Integer.toString(period.getYear()),
                        period.atStartOfDay().toInstant(ZoneOffset.UTC)),
                text("fundamental.fiscalPeriodCode", "Q3", period.atStartOfDay().toInstant(ZoneOffset.UTC)),
                decimal("fundamental.cash", cashValue, "USD", balanceAsOf, "CashAndCashEquivalentsAtCarryingValue"),
                decimal("fundamental.debt", debtValue, "USD", balanceAsOf, "DebtCurrent+LongTermDebtNoncurrent"),
                decimal("fundamental.basicShares", "1000", "shares",
                        sharesAsOf.atStartOfDay().toInstant(ZoneOffset.UTC), "EntityCommonStockSharesOutstanding"),
                text("fundamental.basicSharesBasis", "EntityCommonStockSharesOutstanding_INSTANT",
                        sharesAsOf.atStartOfDay().toInstant(ZoneOffset.UTC)),
                missing("fundamental.dilutedShares", "DATA_NOT_PRESENT", balanceAsOf),
                decimal("fundamental.revenueTTM", "1000", "USD", balanceAsOf,
                        "RevenueFromContractWithCustomerExcludingAssessedTax"),
                ebitdaValue == null ? missing("fundamental.ebitdaTTM", "DATA_NOT_PRESENT", balanceAsOf)
                        : decimal("fundamental.ebitdaTTM", ebitdaValue, "USD", balanceAsOf, "CALCULATED_EBITDA"),
                decimal("fundamental.eps", "5", "USD/share", balanceAsOf, "EarningsPerShareDiluted"),
                decimal("fundamental.fcfTTM", fcfValue, "USD", balanceAsOf, "CALCULATED_FCF"),
                factText("fundamental.currency", "USD", period.toString(),
                        "RevenueFromContractWithCustomerExcludingAssessedTax", balanceAsOf)));
    }

    private StockDataProvider alphaProvider(AtomicReference<List<Estimate>> estimates) {
        return provider(StockDataProviderId.ALPHA_VANTAGE, ALPHA_FIELDS, request -> {
            var observedAt = Instant.now().minusSeconds(1);
            var rows = estimates.get();
            var nearestAnnual = rows.stream().filter(row -> row.type().equals("ANNUAL"))
                    .min(Comparator.comparing(Estimate::periodEnd)).orElse(null);
            var values = new ArrayList<ProviderValue>();
            values.add(consensusObservations(rows, observedAt));
            if (nearestAnnual == null) {
                values.add(missing("consensus.horizon", "NO_FUTURE_FISCAL_YEAR", observedAt));
                values.add(missing("consensus.epsConsensus", "NO_FUTURE_FISCAL_YEAR", observedAt));
                values.add(missing("consensus.revenueConsensus", "NO_FUTURE_FISCAL_YEAR", observedAt));
            } else {
                values.add(text("consensus.horizon", nearestAnnual.periodEnd().toString(), observedAt));
                values.add(decimalOrMissing("consensus.epsConsensus", nearestAnnual.eps(), observedAt));
                values.add(decimalOrMissing("consensus.revenueConsensus", nearestAnnual.revenue(), observedAt));
            }
            values.add(missing("consensus.currency", "CURRENCY_UNAVAILABLE", observedAt));
            var dilutedSharesAsOf = LocalDate.now(ZoneOffset.UTC).minusDays(5)
                    .atStartOfDay().toInstant(ZoneOffset.UTC);
            values.add(decimal("fundamental.dilutedShares", "1500", "shares",
                    dilutedSharesAsOf, "shares_outstanding_diluted"));
            return values;
        });
    }

    private StockDataProvider provider(
            StockDataProviderId id, Set<String> fields, Function<ProviderRequest, List<ProviderValue>> values
    ) {
        return new StockDataProvider() {
            @Override
            public StockDataProviderId id() {
                return id;
            }

            @Override
            public DataProviderRole role() {
                return ProviderCatalog.roleOf(id);
            }

            @Override
            public Set<String> fields() {
                return fields;
            }

            @Override
            public List<ProviderValue> fetch(ProviderRequest request) {
                return values.apply(request);
            }

            @Override
            public List<ProviderValue> fetch(ProviderRequest request, Set<String> selectedFields) {
                return values.apply(request).stream()
                        .filter(value -> selectedFields.contains(value.field())).toList();
            }
        };
    }

    private ProviderValue consensusObservations(List<Estimate> estimates, Instant observedAt) {
        var observations = mapper.createArrayNode();
        estimates.forEach(estimate -> {
            var row = observations.addObject();
            row.put("horizon", estimate.type() + ":" + estimate.periodEnd());
            row.put("estimateType", estimate.type());
            row.put("sourceEstimateType", estimate.type().equals("ANNUAL") ? "fiscal year" : "fiscal quarter");
            row.put("periodEnd", estimate.periodEnd().toString());
            putDecimal(row, "epsConsensus", estimate.eps());
            putDecimal(row, "revenueConsensus", estimate.revenue());
            row.put("epsAnalystCount", 7);
            row.put("revenueAnalystCount", 9);
            row.putNull("currency");
            row.put("observedAt", observedAt.toString());
        });
        return new ProviderValue("consensus.observations", observations, null, "annual and quarterly",
                null, observedAt, List.of(), com.jmj.trade.marketdata.StockAnalysisInput.AsOfBasis.OBSERVED_AT);
    }

    private void putDecimal(tools.jackson.databind.node.ObjectNode target, String field, String value) {
        if (value == null) target.putNull(field);
        else target.put(field, new BigDecimal(value));
    }

    private void insertConsensusHistory(
            String ticker, LocalDate horizon, String type, String source,
            Instant asOf, String revenue, String eps
    ) {
        var inputId = ensureInputSnapshot(ticker);
        jdbc.update("""
                INSERT INTO consensus_snapshots (
                    id, user_id, input_snapshot_id, ticker, as_of, horizon, revenue_consensus,
                    eps_consensus, source, estimate_type, estimate_label, period_end, currency, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)
                """, UUID.randomUUID(), USER_ID, inputId, ticker,
                OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC), horizon.toString(),
                new BigDecimal(revenue), new BigDecimal(eps), source, type,
                type.equals("ANNUAL") ? "fiscal year" : "fiscal quarter", horizon,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private UUID ensureInputSnapshot(String ticker) {
        var existing = jdbc.query("""
                SELECT id FROM analysis_input_snapshots WHERE user_id = ? AND symbol = ? LIMIT 1
                """, (resultSet, rowNum) -> resultSet.getObject(1, UUID.class), USER_ID, ticker)
                .stream().findFirst().orElse(null);
        if (existing != null) return existing;
        var id = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO analysis_input_snapshots (
                    id, user_id, symbol, schema_version, payload, payload_hash, collected_at, created_at
                ) VALUES (?, ?, ?, '1', '{}'::jsonb, ?, ?, ?)
                """, id, USER_ID, ticker, "0".repeat(64), now, now);
        return id;
    }

    private UUID seedHeldPositions(List<String> symbols) {
        var connectionId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, created_at, updated_at, version, credential_revision
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, ?, ?, 0, 1)
                """, connectionId, USER_ID, new byte[32], new byte[12], now, now);
        jdbc.update("""
                INSERT INTO account_sync_runs (
                    id, user_id, broker_connection_id, credential_revision, status, started_at, completed_at
                ) VALUES (?, ?, ?, 1, 'SUCCEEDED', ?, ?)
                """, runId, USER_ID, connectionId, now.minusMinutes(1), now);
        for (var symbol : symbols) {
            jdbc.update("""
                    INSERT INTO position_snapshots (
                        id, sync_run_id, user_id, broker_connection_id, symbol, name, market_country,
                        quantity, currency, average_price, last_price, purchase_amount, market_value_amount,
                        market_value_after_cost, profit_loss_amount, profit_loss_after_cost,
                        profit_loss_rate, profit_loss_rate_after_cost, daily_profit_loss_amount,
                        daily_profit_loss_rate, commission, tax, observed_at, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 'US', 1, 'USD', 100, 100, 100, 100, 100,
                              0, 0, 0, 0, 0, 0, 0, 0, ?, ?)
                    """, UUID.randomUUID(), runId, USER_ID, connectionId, symbol, symbol, now, now);
        }
        return connectionId;
    }

    private PortfolioReadService.PositionView portfolioPosition(String symbol, Instant asOf) {
        return portfolioPosition(symbol, "USD", asOf);
    }

    private PortfolioReadService.PositionView portfolioPosition(String symbol, String currency, Instant asOf) {
        return new PortfolioReadService.PositionView(symbol, symbol, "US", BigDecimal.ONE, currency,
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, asOf);
    }

    private InvestmentContextService service(
            StockDataProviderRegistry providers, PortfolioReadService portfolios, String extraSymbols
    ) {
        var risk = mock(RiskPolicyService.class);
        when(risk.current(USER_ID)).thenReturn(new RiskPolicyService.RiskPolicySnapshot(
                0, new BigDecimal("10000000"), new BigDecimal("10000"),
                new BigDecimal("100"), new BigDecimal("0.25"), false));
        var watchlist = mock(MonitoringWatchlistService.class);
        var now = Instant.now();
        when(watchlist.list(USER_ID)).thenReturn(List.of(new MonitoringWatchlistService.WatchlistEntry(
                UUID.randomUUID(), "AAPL", "WATCH", new MonitoringWatchlistService.WatchlistLevels(
                new MonitoringWatchlistService.Range(BigDecimal.ONE, BigDecimal.ONE),
                new MonitoringWatchlistService.Range(BigDecimal.ONE, BigDecimal.ONE),
                new MonitoringWatchlistService.Range(BigDecimal.ONE, BigDecimal.ONE),
                new MonitoringWatchlistService.Range(BigDecimal.ONE, BigDecimal.ONE)),
                Map.of(), now, now, now)));
        var brokerSurface = mock(ObjectProvider.class);
        when(brokerSurface.getIfAvailable()).thenReturn(null);
        var service = new InvestmentContextService(jdbc, mapper, transactions, providers, brokerSurface,
                portfolios == null ? mock(PortfolioReadService.class) : portfolios, watchlist, risk,
                Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofDays(210), Duration.ofDays(10));
        ReflectionTestUtils.setField(service, "additionalSymbols", extraSymbols);
        return service;
    }

    private JsonNode snapshot(InvestmentContextService service, String ticker) throws Exception {
        var security = service.context(USER_ID).securities().stream()
                .filter(candidate -> candidate.ticker().equals(ticker)).findFirst()
                .orElseThrow(() -> new AssertionError("missing security in current context: " + ticker));
        var snapshot = mapper.createObjectNode();
        snapshot.set("consensus", security.consensus());
        snapshot.set("revision", security.revision());
        snapshot.set("valuation", security.valuation());
        return snapshot;
    }

    private List<String> horizonKeys(JsonNode observations) {
        return StreamSupport.stream(observations.spliterator(), false)
                .map(row -> row.path("estimateType").asText() + ":" + row.path("horizon").asText()).toList();
    }

    private void insertCompounderThesis(String ticker) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO investment_thesis_states (
                    user_id, ticker, core_thesis, invalidation_status, classification, updated_at
                ) VALUES (?, ?, 'Compounder test thesis', 'NOT_REVIEWED', 'COMPOUNDER', ?)
                """, USER_ID, ticker, now);
    }

    private ProviderValue decimalOrMissing(String field, String value, Instant asOf) {
        return value == null ? missing(field, "DATA_NOT_PRESENT", asOf)
                : decimal(field, value, null, asOf, null);
    }

    private ProviderValue decimal(String field, String value, String unit, Instant asOf, String identifier) {
        return new ProviderValue(field, mapper.valueToTree(new BigDecimal(value)), unit, null, identifier,
                asOf, List.of());
    }

    private ProviderValue text(String field, String value, Instant asOf) {
        return new ProviderValue(field, mapper.valueToTree(value), null, null, null, asOf, List.of());
    }

    private ProviderValue factText(String field, String value, String period, String identifier, Instant asOf) {
        return new ProviderValue(field, mapper.valueToTree(value), null, period, identifier, asOf, List.of());
    }

    private ProviderValue missing(String field, String reason, Instant asOf) {
        return new ProviderValue(field, null, null, null, null, asOf, List.of(reason));
    }

    private StockAnalysisInput inputWithMissingReason(
            StockDataProviderId provider, String field, String reason
    ) {
        var now = Instant.now();
        return new StockAnalysisInput(UUID.randomUUID(), "AAPL", "1", now, List.of(
                new StockAnalysisInput.Observation(field, null, null, null, null,
                        provider, now, now, List.of(reason))));
    }

    private Estimate estimate(String type, LocalDate periodEnd, String eps, String revenue) {
        return new Estimate(type, periodEnd, eps, revenue);
    }

    private Instant closeAsOf() {
        return LocalDate.now(ZoneOffset.UTC).minusDays(2).atTime(20, 0).toInstant(ZoneOffset.UTC);
    }

    private Instant basicSharesAsOf() {
        return LocalDate.now(ZoneOffset.UTC).minusDays(4).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private Instant instant(Object value) {
        if (value instanceof OffsetDateTime dateTime) return dateTime.toInstant();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        throw new AssertionError("unexpected database timestamp type: " + value.getClass());
    }

    private record Estimate(String type, LocalDate periodEnd, String eps, String revenue) {
    }
}
