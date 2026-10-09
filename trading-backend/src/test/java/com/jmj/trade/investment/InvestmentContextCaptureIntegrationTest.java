package com.jmj.trade.investment;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.account.BrokerSurfaceService;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.connection.BrokerSurfaceResponse;
import com.jmj.trade.marketdata.DataProviderRole;
import com.jmj.trade.marketdata.ProviderCatalog;
import com.jmj.trade.marketdata.ProviderRequest;
import com.jmj.trade.marketdata.ProviderUnavailableException;
import com.jmj.trade.marketdata.ProviderValue;
import com.jmj.trade.marketdata.StockDataProvider;
import com.jmj.trade.marketdata.StockDataProviderId;
import com.jmj.trade.marketdata.StockDataProviderRegistry;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator;
import com.jmj.trade.investment.tactical.TacticalOverlayAggregationCalculator;
import com.jmj.trade.investment.tactical.TacticalOverlayProperties;
import com.jmj.trade.investment.tactical.TacticalOverlayService;
import com.jmj.trade.monitoring.MonitoringWatchlistService;
import com.jmj.trade.risk.RiskPolicyService;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class InvestmentContextCaptureIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("ca4a8d4b-bafe-4a0a-9239-11cad9a0b390");
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
                INSERT INTO monitoring_watchlist (id, user_id, symbol, levels, evidence, observed_at, created_at, updated_at)
                VALUES (?, ?, 'AAPL', '{"prepare":1,"confirm":2,"pullback":3,"invalidate":0}'::jsonb,
                        '{}'::jsonb, ?, ?, ?)
                """, UUID.randomUUID(), USER_ID, now, now, now);
    }

    @AfterEach
    void closeTestPool() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void providerFailureKeepsSnapshotAndLastSuccessfulCollectionTime() {
        var completed = service(canonicalProviders()).capture(USER_ID);
        var lastSuccess = jdbc.queryForObject("""
                SELECT last_success_at FROM investment_pipeline_state
                 WHERE user_id = ? AND pipeline = 'SECURITY_DATA'
                """, OffsetDateTime.class, USER_ID);
        assertThat(completed).isEqualTo(1);
        assertThat(lastSuccess).isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT status FROM investment_pipeline_state
                 WHERE user_id = ? AND pipeline = 'SECURITY_DATA'
                """, String.class, USER_ID)).isEqualTo("PARTIAL");

        var noProviders = service(new StockDataProviderRegistry(List.of()));
        assertThat(noProviders.capture(USER_ID)).isEqualTo(1);
        assertPipelineFailure("NO_DATA_COLLECTED", lastSuccess);

        var unavailable = service(provider(true));
        assertThat(unavailable.capture(USER_ID)).isEqualTo(1);
        var unavailableState = jdbc.queryForMap("""
                SELECT status, last_success_at, last_error FROM investment_pipeline_state
                 WHERE user_id = ? AND pipeline = 'SECURITY_DATA'
                """, USER_ID);
        assertThat(unavailableState.get("status")).isEqualTo("PARTIAL");
        assertThat(unavailableState.get("last_success_at")).isNotNull();
        assertThat(unavailableState.get("last_error").toString()).contains("PROVIDER_HTTP_402");
        assertThat(unavailable.context(USER_ID).pipeline().lastError())
                .contains("PROVIDER_HTTP_402");
        var latestInput = jdbc.queryForObject("""
                SELECT payload::text FROM analysis_input_snapshots
                 WHERE user_id = ? ORDER BY created_at DESC LIMIT 1
                """, String.class, USER_ID);
        assertThat(latestInput).contains("PROVIDER_UNAVAILABLE", "PROVIDER_HTTP_402");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM analysis_input_snapshots WHERE user_id = ?", Integer.class, USER_ID))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM investment_security_snapshots WHERE user_id = ?", Integer.class, USER_ID))
                .isEqualTo(3);

        assertThat(unavailable.captureAll()).isEqualTo(1);
    }

    @Test
    void unavailableOptionalProviderIsPartialWhenTossAndSecCanonicalInputsArePresent() {
        var fiscalPeriod = LocalDate.now(ZoneOffset.UTC).minusDays(35);
        var asOf = Instant.parse("2026-10-02T20:00:00Z");
        var toss = providerWithValues(List.of(
                decimal("price.regularClose", "100", asOf),
                observedText("price.session", "REGULAR_CLOSE", asOf)), StockDataProviderId.TOSS);
        var sec = providerWithValues(List.of(
                text("fundamental.fiscalPeriod", fiscalPeriod.toString(), fiscalPeriod),
                text("fundamental.reportedAt", fiscalPeriod.plusDays(10) + "T12:00:00Z", fiscalPeriod),
                decimal("fundamental.cash", "50", fiscalPeriod),
                decimal("fundamental.debt", "40", fiscalPeriod),
                decimal("fundamental.revenueTTM", "1000", fiscalPeriod),
                decimal("fundamental.eps", "5", fiscalPeriod)), StockDataProviderId.SEC);

        assertThat(service(new StockDataProviderRegistry(List.of(toss, sec, provider(true))))
                .capture(USER_ID)).isEqualTo(1);

        var state = jdbc.queryForMap("""
                SELECT status, last_success_at, last_error FROM investment_pipeline_state
                 WHERE user_id = ? AND pipeline = 'SECURITY_DATA'
                """, USER_ID);
        assertThat(state.get("status")).isEqualTo("PARTIAL");
        assertThat(state.get("last_success_at")).isNotNull();
        assertThat(state.get("last_error").toString()).contains("PROVIDER_HTTP_402");
    }

    @Test
    void readinessExposesOnlySafeInlineXbrlReasonsForMissingFieldsAndKeepsCarryForward() {
        var firstPeriod = LocalDate.now(ZoneOffset.UTC).minusDays(35);
        var secondPeriod = firstPeriod.plusDays(3);
        var thirdPeriod = secondPeriod.plusDays(3);
        var quoteAsOf = Instant.now().minusSeconds(30);
        var toss = providerWithValues(List.of(
                decimal("price.regularClose", "100", quoteAsOf),
                observedText("price.session", "REGULAR_CLOSE", quoteAsOf)), StockDataProviderId.TOSS);
        var secValues = new AtomicReference<>(List.<ProviderValue>of(
                text("fundamental.fiscalPeriod", firstPeriod.toString(), firstPeriod),
                text("fundamental.reportedAt", firstPeriod.plusDays(10) + "T12:00:00Z", firstPeriod),
                new ProviderValue("fundamental.cash", null, "USD", firstPeriod.toString(), "CashAndCashEquivalents",
                        firstPeriod.atStartOfDay().toInstant(ZoneOffset.UTC), List.of("INLINE_XBRL_HTTP_429")),
                decimal("fundamental.debt", "40", firstPeriod),
                decimal("fundamental.revenueTTM", "1000", firstPeriod)));
        var context = service(new StockDataProviderRegistry(List.of(
                toss, providerWithValues(secValues, StockDataProviderId.SEC))));

        context.capture(USER_ID);
        var missingReadiness = latestReadiness();
        assertThat(missingReadiness.toString()).contains(
                "fundamentals.cash", "fundamentals.cash.INLINE_XBRL_HTTP_429")
                .doesNotContain("PROVIDER_FAILURE", "HTTP response");

        secValues.set(List.of(
                text("fundamental.fiscalPeriod", secondPeriod.toString(), secondPeriod),
                text("fundamental.reportedAt", secondPeriod.plusDays(10) + "T12:00:00Z", secondPeriod),
                decimal("fundamental.cash", "50", secondPeriod),
                decimal("fundamental.debt", "40", secondPeriod),
                decimal("fundamental.revenueTTM", "1000", secondPeriod)));
        context.capture(USER_ID);

        secValues.set(List.of(
                text("fundamental.fiscalPeriod", thirdPeriod.toString(), thirdPeriod),
                text("fundamental.reportedAt", thirdPeriod.plusDays(10) + "T12:00:00Z", thirdPeriod),
                new ProviderValue("fundamental.cash", null, "USD", thirdPeriod.toString(), "CashAndCashEquivalents",
                        thirdPeriod.atStartOfDay().toInstant(ZoneOffset.UTC), List.of("INLINE_XBRL_HTTP_429")),
                decimal("fundamental.debt", "40", thirdPeriod),
                decimal("fundamental.revenueTTM", "1000", thirdPeriod)));
        context.capture(USER_ID);

        var carriedSnapshot = mapper.readTree(jdbc.queryForObject("""
                SELECT payload::text FROM investment_security_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' ORDER BY created_at DESC LIMIT 1
                """, String.class, USER_ID));
        assertThat(carriedSnapshot.path("fundamentals").path("cash").decimalValue())
                .isEqualByComparingTo("50");
        assertThat(carriedSnapshot.path("readiness").path("missingFields").toString())
                .doesNotContain("fundamentals.cash");
    }

    private String latestReadiness() {
        return jdbc.queryForObject("""
                SELECT (payload -> 'readiness' -> 'missingFields')::text
                  FROM investment_security_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' ORDER BY created_at DESC LIMIT 1
                """, String.class, USER_ID);
    }

    @Test
    void decisionLedgerReplayUsesPostgresPrecisionAndRejectsChangedDecision() {
        var watchlist = mock(MonitoringWatchlistService.class);
        when(watchlist.list(USER_ID)).thenReturn(List.of());
        var riskPolicies = mock(RiskPolicyService.class);
        when(riskPolicies.current(USER_ID)).thenReturn(new RiskPolicyService.RiskPolicySnapshot(
                0, new BigDecimal("10000000"), new BigDecimal("10000"),
                new BigDecimal("100"), new BigDecimal("0.25"), false));
        var service = new InvestmentContextService(jdbc, mapper, transactions,
                new StockDataProviderRegistry(List.of()), mock(ObjectProvider.class), mock(PortfolioReadService.class),
                watchlist, riskPolicies, Duration.ofMinutes(15), Duration.ofDays(7),
                Duration.ofDays(210), Duration.ofDays(10));
        var decisionId = UUID.randomUUID();
        var decisionAsOf = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.SECONDS).plusNanos(123_456_123);

        var created = service.recordDecision(USER_ID, decision(decisionId, decisionAsOf,
                "123.456789121", "0.87654321"));
        var replay = service.recordDecision(USER_ID, decision(decisionId,
                decisionAsOf.plusNanos(666), "123.456789124", "0.87654341"));

        assertThat(created.asOf()).isEqualTo(decisionAsOf.truncatedTo(ChronoUnit.MICROS));
        assertThat(replay.decisionId()).isEqualTo(created.decisionId());
        assertThat(replay.asOf()).isEqualTo(created.asOf());
        assertThat(replay.referencePrice()).isEqualByComparingTo("123.45678912");
        assertThat(replay.confidence()).isEqualByComparingTo("0.876543");
        assertThatThrownBy(() -> service.recordDecision(USER_ID,
                decision(decisionId, decisionAsOf.plusNanos(666), "123.466789124", "0.87654341")))
                .isInstanceOf(InvestmentException.class)
                .hasMessage("CONFLICT");
    }

    @Test
    void fmpFundamentalsNeverPopulateCanonicalSecSnapshot() {
        var period = LocalDate.now(ZoneOffset.UTC).minusDays(45);
        var filingDate = period.plusDays(35);
        var quoteAsOf = Instant.now().minusSeconds(30);
        var provider = providerWithValues(fmpFundamentals(period, period, period,
                filingDate, filingDate, filingDate, quoteAsOf, "1000000", "100", "300",
                "1000", "200", "4.2", "100", "120"));

        assertThat(service(provider).capture(USER_ID)).isEqualTo(1);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fundamental_snapshots WHERE user_id = ? AND ticker = 'AAPL'
                """, Integer.class, USER_ID)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT status FROM investment_pipeline_state
                 WHERE user_id = ? AND pipeline = 'SECURITY_DATA'
                """, String.class, USER_ID)).isEqualTo("FAILED");
        var snapshot = jdbc.queryForMap("""
                SELECT payload -> 'fundamentals' AS fundamentals, payload -> 'readiness' AS readiness
                  FROM investment_security_snapshots WHERE user_id = ? AND ticker = 'AAPL'
                ORDER BY created_at DESC LIMIT 1
                """, USER_ID);
        assertThat(snapshot.get("fundamentals").toString()).contains("\"status\": \"DATA_MISSING\"")
                .doesNotContain("1000000", "1000200");
        assertThat(snapshot.get("readiness").toString()).contains("fundamentals.cash", "fundamentals.revenueTTM");
    }

    @Test
    void secPersistsAnnualWeightedShareBasisWithoutInventingMarketValue() {
        var period = LocalDate.of(2025, 9, 30);
        var provider = providerWithValues(List.of(
                text("fundamental.fiscalPeriod", period.toString(), period),
                text("fundamental.reportedAt", "2025-11-10T12:00:00Z", period),
                text("fundamental.fiscalYear", "2025", period),
                text("fundamental.fiscalPeriodCode", "FY", period),
                decimal("fundamental.cash", "50", period),
                decimal("fundamental.debt", "40", period),
                decimal("fundamental.dilutedShares", "1000000", period),
                text("fundamental.dilutedSharesBasis", "WEIGHTED_AVERAGE_FY", period),
                decimal("fundamental.revenueTTM", "1000", period),
                decimal("fundamental.eps", "5.2", period),
                decimal("fundamental.fcfTTM", "90", period)), StockDataProviderId.SEC);

        assertThat(service(provider).capture(USER_ID)).isEqualTo(1);

        var row = jdbc.queryForMap("""
                SELECT source, fiscal_period, reported_at, as_of, diluted_shares, diluted_shares_basis,
                       market_cap, enterprise_value
                  FROM fundamental_snapshots WHERE user_id = ? AND ticker = 'AAPL'
                """, USER_ID);
        assertThat(row.get("source")).isEqualTo("SEC");
        assertThat(row.get("fiscal_period")).isEqualTo(period.toString());
        assertThat(row.get("as_of").toString()).contains(period.toString());
        assertThat(row.get("reported_at").toString()).contains("2025-11-10");
        assertThat((BigDecimal) row.get("diluted_shares")).isEqualByComparingTo("1000000");
        assertThat(row.get("diluted_shares_basis")).isEqualTo("WEIGHTED_AVERAGE_FY");
        assertThat(row.get("market_cap")).isNull();
        assertThat(row.get("enterprise_value")).isNull();
    }

    @Test
    void missingLatestFilingFieldsCarryForwardWithTheirOriginalProvenance() throws Exception {
        var oldPeriod = LocalDate.of(2026, 6, 30);
        var currentPeriod = LocalDate.of(2026, 9, 30);
        var currentFiledAt = Instant.parse("2026-10-01T12:00:00Z");
        var shareAsOf = Instant.parse("2026-10-02T20:00:00Z");
        var secValues = new AtomicReference<>(List.of(
                text("fundamental.fiscalPeriod", oldPeriod.toString(), oldPeriod),
                text("fundamental.reportedAt", "2026-07-30T12:00:00Z", oldPeriod),
                decimal("fundamental.cash", "50", oldPeriod),
                decimal("fundamental.debt", "40", oldPeriod),
                decimal("fundamental.revenueTTM", "1000", oldPeriod),
                decimal("fundamental.basicShares", "10000", oldPeriod)));
        var toss = providerWithValues(List.of(
                decimal("price.regularClose", "100", shareAsOf),
                observedText("price.session", "REGULAR_CLOSE", shareAsOf)), StockDataProviderId.TOSS);
        var sec = providerWithValues(secValues, StockDataProviderId.SEC);
        var context = service(new StockDataProviderRegistry(List.of(toss, sec)));

        assertThat(context.capture(USER_ID)).isEqualTo(1);
        secValues.set(List.of(
                text("fundamental.fiscalPeriod", currentPeriod.toString(), currentPeriod),
                observedText("fundamental.reportedAt", currentFiledAt.toString(), currentFiledAt),
                decimal("fundamental.basicShares", "12000", shareAsOf)));
        assertThat(context.capture(USER_ID)).isEqualTo(1);

        var row = jdbc.queryForMap("""
                SELECT fiscal_period, cash, debt, revenue_ttm, basic_shares, field_provenance::text
                  FROM fundamental_snapshots WHERE user_id = ? AND ticker = 'AAPL' AND source = 'SEC'
                 ORDER BY fiscal_period DESC LIMIT 1
        """, USER_ID);
        assertThat(row.get("fiscal_period")).isEqualTo(currentPeriod.toString());
        assertThat(jdbc.queryForObject("""
                SELECT as_of FROM fundamental_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' AND source = 'SEC'
                 ORDER BY fiscal_period DESC LIMIT 1
                """, OffsetDateTime.class, USER_ID).toInstant()).isEqualTo(currentFiledAt);
        assertThat((BigDecimal) row.get("cash")).isEqualByComparingTo("50");
        assertThat((BigDecimal) row.get("debt")).isEqualByComparingTo("40");
        assertThat((BigDecimal) row.get("revenue_ttm")).isEqualByComparingTo("1000");
        assertThat((BigDecimal) row.get("basic_shares")).isEqualByComparingTo("12000");
        var provenance = mapper.readTree((String) row.get("field_provenance"));
        assertThat(provenance.path("cash").path("asOf").asText()).isEqualTo("2026-06-30T00:00:00Z");
        assertThat(provenance.path("revenueTTM").path("asOf").asText()).isEqualTo("2026-06-30T00:00:00Z");
        assertThat(provenance.path("basicShares").path("asOf").asText()).isEqualTo(shareAsOf.toString());

        var snapshot = mapper.readTree(jdbc.queryForObject("""
                SELECT payload::text FROM investment_security_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' ORDER BY created_at DESC LIMIT 1
                """, String.class, USER_ID));
        assertThat(snapshot.path("fundamentals").path("asOf").asText()).isEqualTo(currentFiledAt.toString());
        assertThat(snapshot.path("readiness").path("fundamentalStatus").asText()).isEqualTo("PARTIAL");
        assertThat(snapshot.path("readiness").path("balanceSheetStatus").asText()).isEqualTo("OK");
    }

    @Test
    void recentSharesCannotMakeStaleCashOrRevenueFresh() throws Exception {
        var oldPeriod = LocalDate.of(2025, 12, 31);
        var currentPeriod = LocalDate.of(2026, 9, 30);
        var currentFiledAt = Instant.parse("2026-10-01T12:00:00Z");
        var shareAsOf = Instant.parse("2026-10-02T20:00:00Z");
        var secValues = new AtomicReference<>(List.of(
                text("fundamental.fiscalPeriod", oldPeriod.toString(), oldPeriod),
                text("fundamental.reportedAt", "2026-02-15T12:00:00Z", oldPeriod),
                decimal("fundamental.cash", "50", oldPeriod),
                decimal("fundamental.debt", "40", oldPeriod),
                decimal("fundamental.revenueTTM", "1000", oldPeriod),
                decimal("fundamental.basicShares", "10000", oldPeriod)));
        var toss = providerWithValues(List.of(
                decimal("price.regularClose", "100", shareAsOf),
                observedText("price.session", "REGULAR_CLOSE", shareAsOf)), StockDataProviderId.TOSS);
        var sec = providerWithValues(secValues, StockDataProviderId.SEC);
        var context = service(new StockDataProviderRegistry(List.of(toss, sec)));

        assertThat(context.capture(USER_ID)).isEqualTo(1);
        secValues.set(List.of(
                text("fundamental.fiscalPeriod", currentPeriod.toString(), currentPeriod),
                observedText("fundamental.reportedAt", currentFiledAt.toString(), currentFiledAt),
                decimal("fundamental.basicShares", "12000", shareAsOf)));
        assertThat(context.capture(USER_ID)).isEqualTo(1);

        var row = jdbc.queryForMap("""
                SELECT cash, basic_shares, field_provenance::text
                  FROM fundamental_snapshots WHERE user_id = ? AND ticker = 'AAPL' AND source = 'SEC'
                 ORDER BY fiscal_period DESC LIMIT 1
                """, USER_ID);
        assertThat(jdbc.queryForObject("""
                SELECT as_of FROM fundamental_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' AND source = 'SEC'
                 ORDER BY fiscal_period DESC LIMIT 1
                """, OffsetDateTime.class, USER_ID).toInstant()).isEqualTo(currentFiledAt);
        assertThat((BigDecimal) row.get("cash")).isEqualByComparingTo("50");
        assertThat((BigDecimal) row.get("basic_shares")).isEqualByComparingTo("12000");
        var provenance = mapper.readTree((String) row.get("field_provenance"));
        assertThat(provenance.path("cash").path("asOf").asText()).isEqualTo("2025-12-31T00:00:00Z");
        assertThat(provenance.path("basicShares").path("asOf").asText()).isEqualTo(shareAsOf.toString());

        var snapshot = mapper.readTree(jdbc.queryForObject("""
                SELECT payload::text FROM investment_security_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' ORDER BY created_at DESC LIMIT 1
                """, String.class, USER_ID));
        assertThat(snapshot.path("readiness").path("fundamentalStatus").asText()).isEqualTo("STALE");
        assertThat(snapshot.path("readiness").path("balanceSheetStatus").asText()).isEqualTo("STALE");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM investment_pipeline_state
                 WHERE user_id = ? AND pipeline = 'SECURITY_DATA'
                """, String.class, USER_ID)).isEqualTo("FAILED");
    }

    @Test
    void mismatchedFmpStatementsCannotContaminateCanonicalSecReadiness() {
        var period = LocalDate.now(ZoneOffset.UTC).minusDays(45);
        var filingDate = period.plusDays(35);
        var provider = providerWithValues(fmpFundamentals(period, period.plusDays(1), period,
                filingDate, filingDate, filingDate, Instant.now().minusSeconds(30),
                "1000000", "100", "300", "1000", "200", "4.2", "100", "120"));

        assertThat(service(provider).capture(USER_ID)).isEqualTo(1);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fundamental_snapshots WHERE user_id = ? AND ticker = 'AAPL'
                """, Integer.class, USER_ID)).isZero();
        var snapshot = jdbc.queryForMap("""
                SELECT payload -> 'fundamentals' AS fundamentals, payload -> 'readiness' AS readiness,
                       payload -> 'price' AS price
                  FROM investment_security_snapshots WHERE user_id = ? AND ticker = 'AAPL'
                ORDER BY created_at DESC LIMIT 1
                """, USER_ID);
        assertThat(snapshot.get("fundamentals").toString()).contains("\"status\": \"DATA_MISSING\"")
                .doesNotContain("SOURCE_CONFLICT");
        assertThat(snapshot.get("readiness").toString()).contains("fundamentals.cash", "fundamentals.revenueTTM")
                .doesNotContain("fundamentals.statementPeriodConflict");
        assertThat(snapshot.get("price").toString()).doesNotContain("SOURCE_CONFLICT");
    }

    @Test
    void optionalFmpRevenueGrowthCannotOverrideSecFundamentals() {
        var period = LocalDate.now(ZoneOffset.UTC).minusDays(45);
        var sec = providerWithValues(List.of(
                text("fundamental.fiscalPeriod", period.toString(), period),
                text("fundamental.reportedAt", period.plusDays(35).toString(), period),
                text("fundamental.fiscalYear", Integer.toString(period.getYear()), period),
                text("fundamental.fiscalPeriodCode", "Q2", period),
                decimal("fundamental.cash", "100", period),
                decimal("fundamental.debt", "300", period),
                decimal("fundamental.revenueTTM", "1200", period)), StockDataProviderId.SEC);
        var fmp = providerWithValues(List.of(
                decimal("fundamental.revenueTTM", "9999", period),
                decimal("fundamental.revenueGrowthYoY", "0.2", period)), StockDataProviderId.FMP);

        assertThat(service(new StockDataProviderRegistry(List.of(sec, fmp))).capture(USER_ID)).isEqualTo(1);

        var row = jdbc.queryForMap("""
                SELECT source, revenue_ttm, revenue_growth_yoy FROM fundamental_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL'
                """, USER_ID);
        assertThat(row.get("source")).isEqualTo("SEC");
        assertThat((BigDecimal) row.get("revenue_ttm")).isEqualByComparingTo("1200");
        assertThat(row.get("revenue_growth_yoy")).isNull();
    }

    @Test
    void optionalFmpIncomeHistoryCannotJoinToSecRevenue() {
        var period = LocalDate.now(ZoneOffset.UTC).minusDays(45);
        var priorPeriod = period.minusYears(1);
        var filingDate = period.plusDays(35);
        var history = mapper.createArrayNode();
        history.addObject().put("date", period.toString()).put("fiscalYear", Integer.toString(period.getYear()))
                .put("period", "Q2").put("revenue", 1200);
        history.addObject().put("date", priorPeriod.toString())
                .put("fiscalYear", Integer.toString(priorPeriod.getYear()))
                .put("period", "Q1").put("revenue", 5000);
        history.addObject().put("date", priorPeriod.toString()).put("revenue", 7000);
        history.addObject().put("date", period.plusDays(90).toString())
                .put("fiscalYear", Integer.toString(period.getYear() + 1))
                .put("period", "Q2").put("revenue", 9000);
        var sec = providerWithValues(List.of(
                text("fundamental.fiscalPeriod", period.toString(), period),
                text("fundamental.reportedAt", filingDate.toString(), period),
                text("fundamental.fiscalYear", Integer.toString(period.getYear()), period),
                text("fundamental.fiscalPeriodCode", "Q2", period),
                decimal("fundamental.cash", "100", period),
                decimal("fundamental.debt", "300", period),
                decimal("fundamental.revenueTTM", "1200", period)), StockDataProviderId.SEC);
        var fmpValues = new AtomicReference<>(withIncomeHistory(fmpFundamentals(period, period, period,
                filingDate, filingDate, filingDate, Instant.now().minusSeconds(30),
                "1000000", "100", "300", "9999", "200", "4.2", "100", "120"), history, period));
        var service = service(new StockDataProviderRegistry(List.of(sec,
                providerWithValues(fmpValues, StockDataProviderId.FMP))));

        service.capture(USER_ID);
        assertThat(jdbc.queryForObject("""
                SELECT revenue_growth_yoy FROM fundamental_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL'
                """, BigDecimal.class, USER_ID)).isNull();

        history.addObject().put("date", priorPeriod.toString())
                .put("fiscalYear", Integer.toString(priorPeriod.getYear()))
                .put("period", "Q2").put("revenue", 1000);
        fmpValues.set(withIncomeHistory(fmpFundamentals(period, period, period,
                filingDate, filingDate, filingDate, Instant.now().minusSeconds(20),
                "1000000", "100", "300", "9999", "200", "4.2", "100", "120"), history, period));
        service.capture(USER_ID);

        var growthValues = jdbc.queryForList("""
                SELECT revenue_growth_yoy FROM fundamental_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL'
                 ORDER BY created_at
                """, USER_ID);
        assertThat(growthValues).hasSize(2);
        assertThat(growthValues.get(0).get("revenue_growth_yoy")).isNull();
        assertThat(growthValues.get(1).get("revenue_growth_yoy")).isNull();
    }

    @Test
    void fmpIncomeHistoryCannotFillMissingSecFiscalMetadata() {
        var period = LocalDate.now(ZoneOffset.UTC).minusDays(45);
        var priorPeriod = period.minusYears(1);
        var filingDate = period.plusDays(35);
        var history = mapper.createArrayNode();
        history.addObject().put("date", priorPeriod.plusDays(1).toString()).put("revenue", 5000);
        history.addObject().put("date", priorPeriod.toString()).put("revenue", 1000);
        var sec = providerWithValues(List.of(
                text("fundamental.fiscalPeriod", period.toString(), period),
                text("fundamental.reportedAt", filingDate.toString(), period),
                decimal("fundamental.cash", "100", period),
                decimal("fundamental.debt", "300", period),
                decimal("fundamental.revenueTTM", "1200", period)), StockDataProviderId.SEC);
        var currentValues = fmpFundamentals(period, period, period,
                filingDate, filingDate, filingDate, Instant.now().minusSeconds(30),
                "1000000", "100", "300", "9999", "200", "4.2", "100", "120").stream()
                .filter(value -> !value.field().equals("fundamental.fiscalYear")
                        && !value.field().equals("fundamental.fiscalPeriodCode"))
                .toList();
        var provider = new StockDataProviderRegistry(List.of(sec,
                providerWithValues(withIncomeHistory(currentValues, history, period), StockDataProviderId.FMP)));

        assertThat(service(provider).capture(USER_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT revenue_growth_yoy FROM fundamental_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL'
                """, BigDecimal.class, USER_ID)).isNull();

        var partialMetadata = fmpFundamentals(period, period, period,
                filingDate, filingDate, filingDate, Instant.now().minusSeconds(20),
                "1000000", "100", "300", "1200", "200", "4.2", "100", "120").stream()
                .filter(value -> !value.field().equals("fundamental.fiscalPeriodCode"))
                .toList();
        assertThat(service(new StockDataProviderRegistry(List.of(sec,
                providerWithValues(withIncomeHistory(partialMetadata, history, period), StockDataProviderId.FMP))))
                .capture(USER_ID)).isEqualTo(1);
        var growthValues = jdbc.queryForList("""
                SELECT revenue_growth_yoy FROM fundamental_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL'
                 ORDER BY created_at
                """, USER_ID);
        assertThat(growthValues).hasSize(2);
        assertThat(growthValues.get(1).get("revenue_growth_yoy")).isNull();
    }

    @Test
    void observedAtConsensusKeepsFutureForecastHorizonSeparateFromSnapshotAsOf() {
        var horizon = LocalDate.now(ZoneOffset.UTC).plusYears(1).toString();
        var observedAt = Instant.now().minusSeconds(5);
        var provider = providerWithValues(List.of(
                observedText("consensus.horizon", horizon, observedAt),
                observedDecimal("consensus.revenueConsensus", "1200", observedAt),
                observedDecimal("consensus.epsConsensus", "5", observedAt)));

        assertThat(service(provider).capture(USER_ID)).isEqualTo(1);

        var row = jdbc.queryForMap("""
                SELECT as_of, horizon, source FROM consensus_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL'
                """, USER_ID);
        assertThat(row.get("horizon")).isEqualTo(horizon);
        assertThat(row.get("source")).isEqualTo("FMP");
        var snapshotAsOf = ((java.sql.Timestamp) row.get("as_of")).toInstant();
        assertThat(snapshotAsOf).isBefore(Instant.now());
        assertThat(snapshotAsOf).isAfter(observedAt.minusSeconds(1));
    }

    @Test
    void alphaVantageConsensusWithoutCurrencyKeepsEstimatesButSuppressesForwardValuation() throws Exception {
        var period = LocalDate.now(ZoneOffset.UTC).minusDays(45);
        var observedAt = Instant.now().minusSeconds(30);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO investment_thesis_states (
                    user_id, ticker, core_thesis, invalidation_status, classification, updated_at
                ) VALUES (?, 'AAPL', 'Growth thesis', 'NOT_REVIEWED', 'GROWTH', ?)
                """, USER_ID, now);

        var tossValues = List.of(
                usdDecimal("price.regularClose", "100", observedAt),
                observedText("price.session", "REGULAR_CLOSE", observedAt));
        var secValues = List.of(
                text("fundamental.fiscalPeriod", period.toString(), period),
                observedText("fundamental.reportedAt", period.plusDays(35).atStartOfDay()
                        .toInstant(ZoneOffset.UTC).toString(), observedAt),
                usdDecimal("fundamental.cash", "100", period),
                usdDecimal("fundamental.debt", "300", period),
                usdDecimal("fundamental.revenueTTM", "1000", period),
                unitDecimal("fundamental.basicShares", "10000", "shares", period),
                text("fundamental.basicSharesBasis", "ENTITY_COMMON_STOCK_SHARES_OUTSTANDING", period),
                unitDecimal("fundamental.dilutedShares", "12000", "shares", period),
                text("fundamental.dilutedSharesBasis", "WEIGHTED_AVERAGE_FY", period),
                unitDecimal("fundamental.eps", "4.2", "USD/shares", period),
                usdDecimal("fundamental.fcfTTM", "200", period));
        var alphaValues = List.of(
                observedText("consensus.horizon", LocalDate.now(ZoneOffset.UTC).plusYears(1).toString(), observedAt),
                observedDecimal("consensus.revenueConsensus", "2000", observedAt),
                observedDecimal("consensus.epsConsensus", "5", observedAt),
                observedDecimal("consensus.ebitdaConsensus", "500", observedAt),
                observedDecimal("consensus.fcfConsensus", "200", observedAt));

        var providers = new StockDataProviderRegistry(List.of(
                providerWithValues(tossValues, StockDataProviderId.TOSS),
                providerWithValues(secValues, StockDataProviderId.SEC),
                providerWithValues(alphaValues, StockDataProviderId.ALPHA_VANTAGE)));
        assertThat(service(providers).capture(USER_ID))
                .isEqualTo(1);

        var snapshot = mapper.readTree(jdbc.queryForObject("""
                SELECT payload::text FROM investment_security_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL'
                 ORDER BY created_at DESC LIMIT 1
                """, String.class, USER_ID));
        var consensus = snapshot.get("consensus");
        var valuation = snapshot.get("valuation");
        var readiness = snapshot.get("readiness");

        assertThat(consensus.get("source").asText()).isEqualTo("ALPHA_VANTAGE");
        assertThat(consensus.get("revenueConsensus").decimalValue()).isEqualByComparingTo("2000");
        assertThat(consensus.get("epsConsensus").decimalValue()).isEqualByComparingTo("5");
        assertThat(consensus.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(valuation.get("evSalesTTM").decimalValue()).isEqualByComparingTo("1000.2");
        assertThat(valuation.get("evSalesForward").isNull()).isTrue();
        assertThat(valuation.get("evEbitdaForward").isNull()).isTrue();
        assertThat(valuation.get("forwardPE").isNull()).isTrue();
        assertThat(valuation.get("fcfYieldForward").isNull()).isTrue();
        assertThat(valuation.get("metricStatuses").path("evSalesTTM").asText()).isEqualTo("OK");
        assertThat(valuation.get("status").asText()).isEqualTo("OK");
        var readinessStatuses = String.join(",", List.of("priceStatus", "trendStatus", "fundamentalStatus",
                        "consensusStatus", "revisionStatus", "valuationStatus", "balanceSheetStatus")
                .stream().map(field -> field + "=" + readiness.path(field).asText()).toList());
        assertThat(readiness.get("overallDataStatus").asText())
                .withFailMessage("readiness category statuses: %s", readinessStatuses).isEqualTo("PARTIAL");
        assertThat(readiness.get("missingFields").toString()).contains("consensus.currency");
    }

    @Test
    void tossQuoteUsesBrokerTimestampAndMatchingEdtCalendarInterval() throws Exception {
        var connectionId = insertActiveTossConnection();
        var quoteTimestamp = Instant.parse("2026-07-06T13:30:00Z");
        var observedAt = Instant.parse("2026-07-06T13:30:01Z");
        var surface = mock(BrokerSurfaceService.class);
        when(surface.prices(USER_ID, connectionId, "AAPL")).thenReturn(BrokerSurfaceResponse.degraded(
                List.of(new BrokerSurfaceResponse.PriceView("AAPL", new BigDecimal("203.40"), null, null,
                        "USD", observedAt, quoteTimestamp)), false, true,
                List.of("AAPL.bidPrice", "AAPL.askPrice"), "PRICE_PARTIAL"));
        when(surface.marketCalendar(USER_ID, connectionId, "US", LocalDate.parse("2026-07-06")))
                .thenReturn(BrokerSurfaceResponse.available(new BrokerSurfaceResponse.MarketCalendarView(
                        "US", mapper.readTree("""
                                {"today":{"date":"2026-07-06",
                                  "preMarket":{"startTime":"2026-07-06T04:00:00-04:00","endTime":"2026-07-06T09:30:00-04:00"},
                                  "regularMarket":{"startTime":"2026-07-06T09:30:00-04:00","endTime":"2026-07-06T16:00:00-04:00"},
                                  "afterMarket":{"startTime":"2026-07-06T16:00:00-04:00","endTime":"2026-07-06T20:00:00-04:00"}}}
                                """))));

        assertThat(service(new StockDataProviderRegistry(List.of()), surface).capture(USER_ID)).isEqualTo(1);

        var price = jdbc.queryForMap("""
                SELECT source, session, latest_price, latest_price_as_of, regular_close, regular_close_as_of
                  FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL'
                """, USER_ID);
        assertThat(price).containsEntry("source", "TOSS").containsEntry("session", "LIVE_REGULAR");
        assertThat((BigDecimal) price.get("latest_price")).isEqualByComparingTo("203.40");
        assertThat(((java.sql.Timestamp) price.get("latest_price_as_of")).toInstant()).isEqualTo(quoteTimestamp);
        assertThat(price.get("regular_close")).isNull();
        assertThat(price.get("regular_close_as_of")).isNull();
        verify(surface).prices(USER_ID, connectionId, "AAPL");
        verify(surface).marketCalendar(USER_ID, connectionId, "US", LocalDate.parse("2026-07-06"));
    }

    @Test
    void tossQuotePersistsWhenFmpPriceProviderFails() throws Exception {
        var connectionId = insertActiveTossConnection();
        var quoteTimestamp = Instant.parse("2026-07-06T13:30:00Z");
        var surface = mock(BrokerSurfaceService.class);
        when(surface.prices(USER_ID, connectionId, "AAPL")).thenReturn(BrokerSurfaceResponse.degraded(
                List.of(new BrokerSurfaceResponse.PriceView("AAPL", new BigDecimal("203.40"), null, null,
                        "USD", Instant.now(), quoteTimestamp)), false, true,
                List.of("AAPL.bidPrice", "AAPL.askPrice"), "PRICE_PARTIAL"));
        when(surface.marketCalendar(USER_ID, connectionId, "US", LocalDate.parse("2026-07-06")))
                .thenReturn(calendarResponse("US", "2026-07-06",
                        "2026-07-06T09:30:00-04:00", "2026-07-06T16:00:00-04:00", null, null));

        assertThat(service(new StockDataProviderRegistry(List.of(provider(true))), surface).capture(USER_ID))
                .isEqualTo(1);

        var price = jdbc.queryForMap("""
                SELECT source, session, latest_price, latest_price_as_of
                  FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL'
                """, USER_ID);
        assertThat(price).containsEntry("source", "TOSS").containsEntry("session", "LIVE_REGULAR");
        assertThat((BigDecimal) price.get("latest_price")).isEqualByComparingTo("203.40");
        assertThat(((java.sql.Timestamp) price.get("latest_price_as_of")).toInstant()).isEqualTo(quoteTimestamp);
        assertThat(jdbc.queryForObject("""
                SELECT last_error FROM investment_pipeline_state
                 WHERE user_id = ? AND pipeline = 'SECURITY_DATA'
                """, String.class, USER_ID)).isEqualTo("CANONICAL_REQUIRED_DATA_MISSING:PROVIDER_HTTP_402");
    }

    @Test
    void tossConnectionLookupIsScopedToCapturedUser() {
        var otherUser = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id) VALUES (?)", otherUser);
        insertActiveTossConnection(otherUser);
        var surface = mock(BrokerSurfaceService.class);

        service(new StockDataProviderRegistry(List.of()), surface).capture(USER_ID);

        verify(surface, org.mockito.Mockito.never()).prices(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void badWatchlistSymbolDoesNotDiscardOtherTossQuote() throws Exception {
        var connectionId = insertActiveTossConnection();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO monitoring_watchlist (id, user_id, symbol, levels, evidence, observed_at, created_at, updated_at)
                VALUES (?, ?, 'MSFT', '{"prepare":1,"confirm":2,"pullback":3,"invalidate":0}'::jsonb,
                        '{}'::jsonb, ?, ?, ?)
                """, UUID.randomUUID(), USER_ID, now, now, now);
        var quoteTimestamp = Instant.parse("2026-07-06T13:30:00Z");
        var surface = mock(BrokerSurfaceService.class);
        when(surface.prices(USER_ID, connectionId, "AAPL,MSFT"))
                .thenThrow(new IllegalStateException("MSFT quote unavailable"));
        when(surface.prices(USER_ID, connectionId, "AAPL")).thenReturn(BrokerSurfaceResponse.available(List.of(
                new BrokerSurfaceResponse.PriceView("AAPL", new BigDecimal("203.40"), null, null,
                        "USD", Instant.now(), quoteTimestamp))));
        when(surface.prices(USER_ID, connectionId, "MSFT"))
                .thenThrow(new IllegalStateException("MSFT quote unavailable"));
        when(surface.marketCalendar(USER_ID, connectionId, "US", LocalDate.parse("2026-07-06")))
                .thenReturn(calendarResponse("US", "2026-07-06",
                        "2026-07-06T09:30:00-04:00", "2026-07-06T16:00:00-04:00", null, null));

        assertThat(service(new StockDataProviderRegistry(List.of()), surface).capture(USER_ID)).isEqualTo(2);

        assertThat(jdbc.queryForObject("""
                SELECT source FROM investment_price_snapshots WHERE user_id = ? AND ticker = 'AAPL'
                """, String.class, USER_ID)).isEqualTo("TOSS");
        verify(surface).prices(USER_ID, connectionId, "AAPL");
        verify(surface).prices(USER_ID, connectionId, "MSFT");
    }

    @Test
    void tossQuoteWithoutEventTimestampIsDiagnosticOnly() {
        var connectionId = insertActiveTossConnection();
        var surface = mock(BrokerSurfaceService.class);
        when(surface.prices(USER_ID, connectionId, "AAPL")).thenReturn(BrokerSurfaceResponse.available(List.of(
                new BrokerSurfaceResponse.PriceView("AAPL", new BigDecimal("203.40"), null, null,
                        "USD", Instant.now(), null))));

        service(new StockDataProviderRegistry(List.of()), surface).capture(USER_ID);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' AND source = 'TOSS'
                """, Integer.class, USER_ID)).isZero();
        var input = jdbc.queryForObject("""
                SELECT payload::text FROM analysis_input_snapshots
                 WHERE user_id = ? ORDER BY created_at DESC LIMIT 1
                """, String.class, USER_ID);
        assertThat(input).contains("TOSS_TIMESTAMP_MISSING");
        verify(surface, org.mockito.Mockito.never()).marketCalendar(
                org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.eq(connectionId),
                org.mockito.ArgumentMatchers.eq("US"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nonUsdTossQuoteIsNotClassifiedWithUsCalendar() {
        var connectionId = insertActiveTossConnection();
        var surface = mock(BrokerSurfaceService.class);
        when(surface.prices(USER_ID, connectionId, "AAPL")).thenReturn(BrokerSurfaceResponse.available(List.of(
                new BrokerSurfaceResponse.PriceView("AAPL", new BigDecimal("203.40"), null, null,
                        "KRW", Instant.now(), Instant.now().minusSeconds(30)))));

        service(new StockDataProviderRegistry(List.of()), surface).capture(USER_ID);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' AND source = 'TOSS'
                """, Integer.class, USER_ID)).isZero();
        verify(surface, org.mockito.Mockito.never()).marketCalendar(
                org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.eq(connectionId),
                org.mockito.ArgumentMatchers.eq("US"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void futureTossQuoteIsNotUsedAsCurrentPrice() {
        var connectionId = insertActiveTossConnection();
        var surface = mock(BrokerSurfaceService.class);
        when(surface.prices(USER_ID, connectionId, "AAPL")).thenReturn(BrokerSurfaceResponse.available(List.of(
                new BrokerSurfaceResponse.PriceView("AAPL", new BigDecimal("203.40"), null, null,
                        "USD", Instant.now(), Instant.now().plus(Duration.ofHours(1))))));

        service(new StockDataProviderRegistry(List.of()), surface).capture(USER_ID);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' AND source = 'TOSS'
                """, Integer.class, USER_ID)).isZero();
        var input = jdbc.queryForObject("""
                SELECT payload::text FROM analysis_input_snapshots
                 WHERE user_id = ? ORDER BY created_at DESC LIMIT 1
                """, String.class, USER_ID);
        assertThat(input).contains("TOSS_TIMESTAMP_FUTURE");
        verify(surface, org.mockito.Mockito.never()).marketCalendar(
                org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.eq(connectionId),
                org.mockito.ArgumentMatchers.eq("US"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void tossDailyCandlesPersistRegularClosesIndependentlyOfQuoteSessionAndFmpFailure() throws Exception {
        var connectionId = insertActiveTossConnection();
        var quoteTimestamp = Instant.parse("2026-10-02T23:50:00Z");
        var candles = new java.util.ArrayList<>(java.util.stream.IntStream.range(0, 50)
                .mapToObj(index -> {
                    var close = BigDecimal.valueOf(100 + index);
                    return new BrokerSurfaceResponse.CandleView(
                            LocalDate.of(2025, 3, 1).minusDays(index)
                                    .atStartOfDay(java.time.ZoneId.of("America/New_York")).toInstant(),
                            close, close.add(BigDecimal.ONE), close.subtract(BigDecimal.ONE), close,
                            BigDecimal.ZERO, "USD");
                })
                .toList());
        candles.add(new BrokerSurfaceResponse.CandleView(
                LocalDate.of(2025, 3, 1).atStartOfDay(java.time.ZoneId.of("America/New_York"))
                        .toInstant().plusSeconds(60),
                new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("99"),
                new BigDecimal("100"), BigDecimal.ZERO, "USD"));
        var surface = mock(BrokerSurfaceService.class);
        when(surface.prices(USER_ID, connectionId, "AAPL")).thenReturn(BrokerSurfaceResponse.available(List.of(
                new BrokerSurfaceResponse.PriceView("AAPL", new BigDecimal("203.40"), null, null,
                        "USD", quoteTimestamp, quoteTimestamp))));
        when(surface.marketCalendar(USER_ID, connectionId, "US", LocalDate.parse("2026-10-02")))
                .thenReturn(calendarResponse("2026-10-02", "2026-10-02T09:30:00-04:00",
                        "2026-10-02T16:00:00-04:00", "2026-10-02T16:00:00-04:00",
                        "2026-10-02T20:00:00-04:00"));
        when(surface.candles(USER_ID, connectionId, "AAPL", "1d", 100, null, false))
                .thenReturn(BrokerSurfaceResponse.available(new BrokerSurfaceResponse.CandleSeriesView(
                        "AAPL", "1d", false, candles, null)));

        assertThat(service(new StockDataProviderRegistry(List.of(provider(true))), surface).capture(USER_ID))
                .isEqualTo(1);

        var storedBars = jdbc.queryForList("""
                SELECT regular_close, regular_close_as_of, source
                  FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' AND session = 'REGULAR_CLOSE'
                 ORDER BY regular_close_as_of
                """, USER_ID);
        assertThat(storedBars).hasSize(50);
        assertThat(storedBars).allSatisfy(row -> assertThat(row.get("source")).isEqualTo("TOSS"));
        assertThat((BigDecimal) storedBars.getLast().get("regular_close")).isEqualByComparingTo("100");
        assertThat(((java.sql.Timestamp) storedBars.getLast().get("regular_close_as_of")).toInstant())
                .isEqualTo(Instant.parse("2025-03-01T05:00:00Z"));
        var tossQuote = jdbc.queryForMap("""
                SELECT session, latest_price, latest_price_as_of
                  FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' AND source = 'TOSS' AND session = 'AFTER_HOURS'
                """, USER_ID);
        assertThat(tossQuote.get("session")).isEqualTo("AFTER_HOURS");
        assertThat((BigDecimal) tossQuote.get("latest_price")).isEqualByComparingTo("203.40");
        assertThat(((java.sql.Timestamp) tossQuote.get("latest_price_as_of")).toInstant()).isEqualTo(quoteTimestamp);
        var technical = jdbc.queryForObject("""
                SELECT (payload -> 'technical')::text
                  FROM investment_security_snapshots WHERE user_id = ? AND ticker = 'AAPL'
                 ORDER BY created_at DESC LIMIT 1
                """, String.class, USER_ID);
        var technicalPayload = mapper.readTree(technical);
        assertThat(technicalPayload.path("dailyObservations").asInt()).isEqualTo(50);
        assertThat(technicalPayload.path("sma20").isNumber()).isTrue();
        assertThat(technicalPayload.path("sma50").isNumber()).isTrue();
        assertThat(technicalPayload.path("rsi14").isNumber()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT last_error FROM investment_pipeline_state
                 WHERE user_id = ? AND pipeline = 'SECURITY_DATA'
                """, String.class, USER_ID)).isEqualTo("CANONICAL_REQUIRED_DATA_MISSING:PROVIDER_HTTP_402");
        assertThat(jdbc.queryForObject("""
                SELECT payload::text FROM analysis_input_snapshots
                 WHERE user_id = ? ORDER BY created_at DESC LIMIT 1
                """, String.class, USER_ID)).contains("TOSS_CANDLE_TIMESTAMP_INVALID");
        verify(surface).candles(USER_ID, connectionId, "AAPL", "1d", 100, null, false);
        service(new StockDataProviderRegistry(List.of(provider(true))), surface).captureQuoteUpdates(USER_ID);
        verify(surface, org.mockito.Mockito.times(1)).candles(USER_ID, connectionId, "AAPL", "1d", 100, null, false);
    }

    @Test
    void datedTossCandleHistoryWithUnavailableOuterAsOfFeedsTacticalOverlay() throws Exception {
        var connectionId = insertActiveTossConnection();
        var portfolios = mock(PortfolioReadService.class);
        when(portfolios.read(USER_ID, connectionId)).thenReturn(new PortfolioReadService.PortfolioView(
                UUID.randomUUID(), Instant.now(), false, null, false, List.of(), List.of(),
                null, List.of(), Map.of()));
        var date = LocalDate.of(2026, 10, 2);
        var barTimestamp = date.atStartOfDay(java.time.ZoneId.of("America/New_York")).toInstant();
        var candle = new BrokerSurfaceResponse.CandleView(barTimestamp,
                new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("99"),
                new BigDecimal("100"), BigDecimal.ZERO, "USD");
        var quoteTimestamp = Instant.parse("2026-10-02T23:50:00Z");
        var surface = mock(BrokerSurfaceService.class);
        when(surface.prices(USER_ID, connectionId, "AAPL")).thenReturn(BrokerSurfaceResponse.available(List.of(
                new BrokerSurfaceResponse.PriceView("AAPL", new BigDecimal("100"), null, null,
                        "USD", quoteTimestamp, quoteTimestamp))));
        when(surface.marketCalendar(USER_ID, connectionId, "US", date))
                .thenReturn(calendarResponse("2026-10-02", "2026-10-02T09:30:00-04:00",
                        "2026-10-02T16:00:00-04:00", "2026-10-02T16:00:00-04:00",
                        "2026-10-03T00:00:00-04:00"));
        for (var ticker : List.of("AAPL", "SPY")) {
            when(surface.candles(USER_ID, connectionId, ticker, "1d", 100, null, false))
                    .thenReturn(BrokerSurfaceResponse.available(new BrokerSurfaceResponse.CandleSeriesView(
                            ticker, "1d", false, List.of(candle), null)));
        }

        var riskPolicies = mock(RiskPolicyService.class);
        when(riskPolicies.current(USER_ID)).thenReturn(new RiskPolicyService.RiskPolicySnapshot(
                0, new BigDecimal("10000000"), new BigDecimal("10000"),
                new BigDecimal("100"), new BigDecimal("0.25"), false));
        var surfaces = mock(ObjectProvider.class);
        when(surfaces.getIfAvailable()).thenReturn(surface);
        var context = new InvestmentContextService(jdbc, mapper, transactions,
                new StockDataProviderRegistry(List.of()), surfaces, portfolios,
                mock(MonitoringWatchlistService.class), riskPolicies, Duration.ofMinutes(15),
                Duration.ofDays(7), Duration.ofDays(210), Duration.ofDays(10));
        var overlayProperties = TacticalOverlayProperties.defaults();
        context.setTacticalOverlayService(new TacticalOverlayService(jdbc, mapper,
                new TacticalOverlayCalculator(overlayProperties), overlayProperties,
                new TacticalOverlayAggregationCalculator(), "AAPL"));

        assertThat(context.capture(USER_ID)).isEqualTo(1);

        var input = mapper.readTree(jdbc.queryForObject("""
                SELECT payload::text FROM analysis_input_snapshots
                 WHERE user_id = ? AND symbol = 'AAPL' ORDER BY created_at DESC LIMIT 1
                """, String.class, USER_ID));
        var candleHistory = java.util.stream.StreamSupport.stream(
                        input.path("observations").spliterator(), false)
                .filter(observation -> "price.regularCloseHistory".equals(observation.path("field").asText()))
                .filter(observation -> "TOSS".equals(observation.path("provider").asText()))
                .findFirst().orElseThrow();
        assertThat(candleHistory.path("value").isArray()).isTrue();
        assertThat(candleHistory.path("value").path(0).path("timestamp").asText())
                .isEqualTo(barTimestamp.toString());
        assertThat(candleHistory.path("missingData").toString()).isEqualTo("[\"AS_OF_UNAVAILABLE\"]");

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM investment_tactical_overlay_bar_snapshots
                 WHERE user_id = ? AND ticker IN ('AAPL', 'SPY') AND bar_date = ?
                """, Integer.class, USER_ID, date)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM investment_tactical_overlay_bar_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' AND source = 'TOSS'
                   AND source_as_of = ? AND close_price = 100
                """, Integer.class, USER_ID, OffsetDateTime.ofInstant(barTimestamp, ZoneOffset.UTC))).isEqualTo(1);

        var capturedContext = context.context(USER_ID);
        var security = capturedContext.securities().stream()
                .filter(value -> "AAPL".equals(value.ticker())).findFirst().orElseThrow();
        assertThat(security.tacticalOverlay().asOf()).isEqualTo(date);
        assertThat(security.tacticalOverlay().indicators().isArray()).isTrue();
        assertThat(security.tacticalOverlay().indicators().size()).isEqualTo(1);
        assertThat(capturedContext.tacticalOverlay().market().path("benchmarkCoverage").asInt()).isEqualTo(1);
    }

    @Test
    void tossDailyCandleFailuresStayMissingAndExposeReasonsInReadiness() throws Exception {
        var connectionId = insertActiveTossConnection();
        var symbols = List.of("UNAV", "EMPTY", "CURR", "FUT", "DUP", "ZERO");
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        for (var symbol : symbols) {
            jdbc.update("""
                    INSERT INTO monitoring_watchlist (id, user_id, symbol, levels, evidence, observed_at, created_at, updated_at)
                    VALUES (?, ?, ?, '{"prepare":1,"confirm":2,"pullback":3,"invalidate":0}'::jsonb,
                            '{}'::jsonb, ?, ?, ?)
                    """, UUID.randomUUID(), USER_ID, symbol, now, now, now);
        }
        var surface = mock(BrokerSurfaceService.class);
        var date = LocalDate.of(2025, 3, 3);
        var midnight = date.atStartOfDay(java.time.ZoneId.of("America/New_York")).toInstant();
        var valid = new BrokerSurfaceResponse.CandleView(midnight,
                new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("99"),
                new BigDecimal("100"), BigDecimal.ZERO, "USD");
        when(surface.candles(USER_ID, connectionId, "AAPL", "1d", 100, null, false))
                .thenReturn(BrokerSurfaceResponse.available(new BrokerSurfaceResponse.CandleSeriesView(
                        "AAPL", "1d", false, List.of(valid), null)));
        when(surface.candles(USER_ID, connectionId, "UNAV", "1d", 100, null, false))
                .thenReturn(BrokerSurfaceResponse.unavailable("UPSTREAM_UNAVAILABLE"));
        when(surface.candles(USER_ID, connectionId, "EMPTY", "1d", 100, null, false))
                .thenReturn(BrokerSurfaceResponse.available(new BrokerSurfaceResponse.CandleSeriesView(
                        "EMPTY", "1d", false, List.of(), null)));
        when(surface.candles(USER_ID, connectionId, "CURR", "1d", 100, null, false))
                .thenReturn(BrokerSurfaceResponse.available(new BrokerSurfaceResponse.CandleSeriesView(
                        "CURR", "1d", false, List.of(new BrokerSurfaceResponse.CandleView(midnight,
                                valid.openPrice(), valid.highPrice(), valid.lowPrice(), valid.closePrice(),
                                valid.volume(), "JPY")), null)));
        when(surface.candles(USER_ID, connectionId, "FUT", "1d", 100, null, false))
                .thenReturn(BrokerSurfaceResponse.available(new BrokerSurfaceResponse.CandleSeriesView(
                        "FUT", "1d", false, List.of(new BrokerSurfaceResponse.CandleView(
                                LocalDate.now(java.time.ZoneId.of("America/New_York")).plusDays(30)
                                        .atStartOfDay(java.time.ZoneId.of("America/New_York")).toInstant(),
                                valid.openPrice(), valid.highPrice(), valid.lowPrice(), valid.closePrice(),
                                valid.volume(), "USD")), null)));
        when(surface.candles(USER_ID, connectionId, "DUP", "1d", 100, null, false))
                .thenReturn(BrokerSurfaceResponse.available(new BrokerSurfaceResponse.CandleSeriesView(
                        "DUP", "1d", false, List.of(valid, new BrokerSurfaceResponse.CandleView(midnight,
                                new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("99"),
                                new BigDecimal("100.5"), BigDecimal.ZERO, "USD")), null)));
        when(surface.candles(USER_ID, connectionId, "ZERO", "1d", 100, null, false))
                .thenReturn(BrokerSurfaceResponse.available(new BrokerSurfaceResponse.CandleSeriesView(
                        "ZERO", "1d", false, List.of(new BrokerSurfaceResponse.CandleView(midnight,
                                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                                BigDecimal.ZERO, "USD")), null)));

        assertThat(service(new StockDataProviderRegistry(List.of()), surface).capture(USER_ID))
                .isEqualTo(symbols.size() + 1);

        var reasons = Map.of(
                "UNAV", "TOSS_CANDLES_UNAVAILABLE",
                "EMPTY", "TOSS_CANDLES_EMPTY",
                "CURR", "TOSS_CANDLE_CURRENCY_MISMATCH",
                "FUT", "TOSS_CANDLE_FUTURE",
                "DUP", "TOSS_CANDLE_DUPLICATE_CONFLICT",
                "ZERO", "TOSS_CANDLE_PRICE_INVALID");
        for (var entry : reasons.entrySet()) {
            var payload = mapper.readTree(jdbc.queryForObject("""
                    SELECT payload::text FROM investment_security_snapshots
                     WHERE user_id = ? AND ticker = ? ORDER BY created_at DESC LIMIT 1
                    """, String.class, USER_ID, entry.getKey()));
            var missing = new java.util.ArrayList<String>();
            payload.path("readiness").path("missingFields").forEach(item -> missing.add(item.asText()));
            assertThat(missing).contains("price.regularClose", "price.regularClose." + entry.getValue());
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM investment_price_snapshots
                 WHERE user_id = ? AND session = 'REGULAR_CLOSE'
                """, Integer.class, USER_ID)).isEqualTo(1);
        var validWithoutQuote = mapper.readTree(jdbc.queryForObject("""
                SELECT payload::text FROM investment_security_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' ORDER BY created_at DESC LIMIT 1
                """, String.class, USER_ID));
        assertThat(validWithoutQuote.path("price").path("regularClose").decimalValue())
                .isEqualByComparingTo("100");
        assertThat(validWithoutQuote.path("price").path("latestPrice").isNull()).isTrue();
    }

    @Test
    void tossCandleFinalityRequiresOfficialCalendarEndAndUnambiguousTradeDate() throws Exception {
        var tradeDate = LocalDate.parse("2026-10-02");
        var regular = mapper.readTree("""
                {"today":{"date":"2026-10-02","regularMarket":{
                  "startTime":"2026-10-02T22:30:00.000+09:00",
                  "endTime":"2026-10-03T05:00:00.000+09:00"}}}
                """);
        var end = Instant.parse("2026-10-02T20:00:00Z");
        assertThat(InvestmentContextService.tossCandleFinalityReason(tradeDate, regular, end.minusNanos(1)))
                .isEqualTo("TOSS_CANDLE_NOT_FINAL");
        assertThat(InvestmentContextService.tossCandleFinalityReason(tradeDate, regular, end)).isNull();
        assertThat(InvestmentContextService.tossCandleFinalityReason(tradeDate, null, end))
                .isEqualTo("TOSS_CANDLE_SESSION_UNVERIFIED");
        var mismatched = mapper.readTree("""
                {"today":{"date":"2026-10-03","regularMarket":{
                  "startTime":"2026-10-03T09:30:00-04:00","endTime":"2026-10-03T16:00:00-04:00"}}}
                """);
        assertThat(InvestmentContextService.tossCandleFinalityReason(tradeDate, mismatched, end))
                .isEqualTo("TOSS_CANDLE_SESSION_UNVERIFIED");
        var ambiguous = mapper.readTree("""
                {"today":{"date":"2026-10-02","regularMarket":{
                  "startTime":"2026-10-02T09:30:00-04:00","endTime":"2026-10-02T16:00:00-04:00"}},
                 "previousBusinessDay":{"date":"2026-10-02","regularMarket":{
                  "startTime":"2026-10-02T09:30:00-04:00","endTime":"2026-10-02T16:00:00-04:00"}}}
                """);
        assertThat(InvestmentContextService.tossCandleFinalityReason(tradeDate, ambiguous, end))
                .isEqualTo("TOSS_CANDLE_SESSION_UNVERIFIED");
    }

    @Test
    void tossCalendarDateMismatchKeepsQuotePartialWithoutPersistingAClassifiedPrice() throws Exception {
        var connectionId = insertActiveTossConnection();
        var quoteTimestamp = Instant.parse("2026-07-06T14:00:00Z");
        var surface = mock(BrokerSurfaceService.class);
        when(surface.prices(USER_ID, connectionId, "AAPL")).thenReturn(BrokerSurfaceResponse.available(List.of(
                new BrokerSurfaceResponse.PriceView("AAPL", new BigDecimal("203.40"), null, null,
                        "USD", Instant.now(), quoteTimestamp))));
        when(surface.marketCalendar(USER_ID, connectionId, "US", LocalDate.parse("2026-07-06")))
                .thenReturn(calendarResponse("2026-07-07",
                        "2026-07-07T09:30:00-04:00", "2026-07-07T16:00:00-04:00", null, null));

        assertThat(service(new StockDataProviderRegistry(List.of()), surface).capture(USER_ID)).isEqualTo(1);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' AND source = 'TOSS'
                """, Integer.class, USER_ID)).isZero();
        var payload = mapper.readTree(jdbc.queryForObject("""
                SELECT payload::text FROM investment_security_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' ORDER BY created_at DESC LIMIT 1
                """, String.class, USER_ID));
        assertThat(payload.path("price").path("source").asText()).isEqualTo("TOSS");
        assertThat(payload.path("price").path("latestPrice").decimalValue()).isEqualByComparingTo("203.40");
        assertThat(payload.path("price").path("latestPriceAsOf").asText()).isEqualTo(quoteTimestamp.toString());
        assertThat(payload.path("price").path("session").isNull()).isTrue();
        assertThat(payload.path("price").path("status").asText()).isEqualTo("PARTIAL");
    }

    @Test
    void tossCalendarForWrongMarketDoesNotClassifyOrPersistQuote() throws Exception {
        var connectionId = insertActiveTossConnection();
        var quoteTimestamp = Instant.parse("2026-07-06T14:00:00Z");
        var surface = mock(BrokerSurfaceService.class);
        when(surface.prices(USER_ID, connectionId, "AAPL")).thenReturn(BrokerSurfaceResponse.available(List.of(
                new BrokerSurfaceResponse.PriceView("AAPL", new BigDecimal("203.40"), null, null,
                        "USD", Instant.now(), quoteTimestamp))));
        when(surface.marketCalendar(USER_ID, connectionId, "US", LocalDate.parse("2026-07-06")))
                .thenReturn(calendarResponse("CA", "2026-07-06",
                        "2026-07-06T09:30:00-04:00", "2026-07-06T16:00:00-04:00", null, null));

        assertThat(service(new StockDataProviderRegistry(List.of()), surface).capture(USER_ID)).isEqualTo(1);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM investment_price_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' AND source = 'TOSS'
                """, Integer.class, USER_ID)).isZero();
        var payload = mapper.readTree(jdbc.queryForObject("""
                SELECT payload::text FROM investment_security_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' ORDER BY created_at DESC LIMIT 1
                """, String.class, USER_ID));
        assertThat(payload.path("price").path("source").asText()).isEqualTo("TOSS");
        assertThat(payload.path("price").path("session").isNull()).isTrue();
        assertThat(payload.path("price").path("status").asText()).isEqualTo("PARTIAL");
    }

    @Test
    void tossCalendarOutsideIntervalsKeepsSessionMissingAndExposesExactReason() throws Exception {
        var connectionId = insertActiveTossConnection();
        var quoteTimestamps = Map.of(
                "AAPL", Instant.parse("2026-10-02T23:50:00Z"),
                "AVT", Instant.parse("2026-10-02T23:40:52Z"),
                "CSTM", Instant.parse("2026-10-02T23:30:08Z"),
                "LUNR", Instant.parse("2026-10-02T23:58:29Z"),
                "RDW", Instant.parse("2026-10-02T23:59:55Z"),
                "TSLA", Instant.parse("2026-10-02T04:00:00Z"));
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        for (var symbol : List.of("AVT", "CSTM", "LUNR", "RDW", "TSLA")) {
            jdbc.update("""
                    INSERT INTO monitoring_watchlist (id, user_id, symbol, levels, evidence, observed_at, created_at, updated_at)
                    VALUES (?, ?, ?, '{"prepare":1,"confirm":2,"pullback":3,"invalidate":0}'::jsonb,
                            '{}'::jsonb, ?, ?, ?)
                    """, UUID.randomUUID(), USER_ID, symbol, now, now, now);
        }

        var surface = mock(BrokerSurfaceService.class);
        quoteTimestamps.forEach((symbol, timestamp) -> when(surface.prices(USER_ID, connectionId, symbol))
                .thenReturn(BrokerSurfaceResponse.available(List.of(new BrokerSurfaceResponse.PriceView(
                        symbol, BigDecimal.ONE, null, null, "USD", timestamp.plusSeconds(1), timestamp)))));
        var calendar = mapper.readTree("""
                {"today":{"date":"2026-10-02",
                  "dayMarket":{"startTime":"2026-10-02T09:00:00.000+09:00","endTime":"2026-10-02T17:00:00.000+09:00"},
                  "preMarket":{"startTime":"2026-10-02T17:00:00.000+09:00","endTime":"2026-10-02T22:30:00.000+09:00"},
                  "regularMarket":{"startTime":"2026-10-02T22:30:00.000+09:00","endTime":"2026-10-03T05:00:00.000+09:00"},
                  "afterMarket":{"startTime":"2026-10-03T05:00:00.000+09:00","endTime":"2026-10-03T08:50:00.000+09:00"}}}
                """);
        when(surface.marketCalendar(USER_ID, connectionId, "US", LocalDate.parse("2026-10-02")))
                .thenReturn(BrokerSurfaceResponse.available(
                        new BrokerSurfaceResponse.MarketCalendarView("US", calendar)));

        assertThat(service(new StockDataProviderRegistry(List.of()), surface).capture(USER_ID))
                .isEqualTo(quoteTimestamps.size());

        for (var entry : quoteTimestamps.entrySet()) {
            var payload = mapper.readTree(jdbc.queryForObject("""
                    SELECT payload::text FROM investment_security_snapshots
                     WHERE user_id = ? AND ticker = ? ORDER BY created_at DESC LIMIT 1
                    """, String.class, USER_ID, entry.getKey()));
            var price = payload.path("price");
            assertThat(price.path("source").asText()).isEqualTo("TOSS");
            var missingFields = new java.util.ArrayList<String>();
            payload.path("readiness").path("missingFields").forEach(item -> missingFields.add(item.asText()));
            if ("TSLA".equals(entry.getKey())) {
                assertThat(price.path("session").isNull()).isTrue();
                assertThat(price.path("status").asText()).isEqualTo("PARTIAL");
                assertThat(missingFields).contains("price.session.TOSS_SESSION_UNVERIFIED")
                        .doesNotContain("price.session.TOSS_QUOTE_OUTSIDE_DECLARED_INTERVALS");
            } else if (entry.getValue().isBefore(Instant.parse("2026-10-02T23:50:00Z"))) {
                assertThat(price.path("session").asText()).isEqualTo("AFTER_HOURS");
                assertThat(missingFields).doesNotContain("price.session.TOSS_QUOTE_OUTSIDE_DECLARED_INTERVALS");
            } else {
                assertThat(price.path("session").isNull()).isTrue();
                assertThat(price.path("status").asText()).isEqualTo("PARTIAL");
                assertThat(missingFields).contains(
                        "price.session", "price.session.TOSS_QUOTE_OUTSIDE_DECLARED_INTERVALS");
                var input = jdbc.queryForObject("""
                        SELECT payload::text FROM analysis_input_snapshots
                         WHERE user_id = ? AND symbol = ? ORDER BY created_at DESC LIMIT 1
                        """, String.class, USER_ID, entry.getKey());
                assertThat(input).contains("TOSS_QUOTE_OUTSIDE_DECLARED_INTERVALS");
            }
        }
        assertThat(jdbc.queryForList("""
                SELECT ticker FROM investment_price_snapshots
                 WHERE user_id = ? AND source = 'TOSS' ORDER BY ticker
                """, String.class, USER_ID)).containsExactly("AVT", "CSTM");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM investment_price_snapshots
                 WHERE user_id = ? AND source = 'TOSS' AND regular_close IS NOT NULL
                """, Integer.class, USER_ID)).isZero();
        verify(surface).marketCalendar(USER_ID, connectionId, "US", LocalDate.parse("2026-10-02"));
    }

    @Test
    void captureStoresRegularCloseSessionFactsFromTheOfficialCalendarWithoutSubstitutingTheQuote() throws Exception {
        var connectionId = insertActiveTossConnection();
        var newYork = java.time.ZoneId.of("America/New_York");
        var now = Instant.now();
        var today = now.atZone(newYork).toLocalDate();
        var previous = today.minusDays(1);
        var next = today.plusDays(1);
        var nextClose = next.atTime(16, 0).atZone(newYork);
        var surface = mock(BrokerSurfaceService.class);
        when(surface.prices(USER_ID, connectionId, "AAPL")).thenReturn(BrokerSurfaceResponse.available(List.of(
                new BrokerSurfaceResponse.PriceView("AAPL", new BigDecimal("105"), null, null,
                        "USD", now, now.minusSeconds(1)))));
        when(surface.marketCalendar(USER_ID, connectionId, "US", today))
                .thenReturn(BrokerSurfaceResponse.available(new BrokerSurfaceResponse.MarketCalendarView(
                        "US", mapper.readTree("""
                                {"today":{"date":"%s","regularMarket":null},
                                 "previousBusinessDay":{"date":"%s"},"nextBusinessDay":{"date":"%s"}}
                                """.formatted(today, previous, next)))));
        when(surface.marketCalendar(USER_ID, connectionId, "US", next))
                .thenReturn(calendarResponse(next.toString(),
                        next.atTime(9, 30).atZone(newYork).toOffsetDateTime().toString(),
                        nextClose.toOffsetDateTime().toString(), null, null));
        var candles = List.of(previous.minusDays(1), previous).stream()
                .map(date -> {
                    var close = date.equals(previous) ? new BigDecimal("100") : new BigDecimal("99");
                    return new BrokerSurfaceResponse.CandleView(date.atStartOfDay(newYork).toInstant(),
                            close, close.add(BigDecimal.ONE), close.subtract(BigDecimal.ONE), close,
                            BigDecimal.TEN, "USD");
                })
                .toList();
        when(surface.candles(USER_ID, connectionId, "AAPL", "1d", 100, null, false))
                .thenReturn(BrokerSurfaceResponse.available(new BrokerSurfaceResponse.CandleSeriesView(
                        "AAPL", "1d", false, candles, null)));

        service(new StockDataProviderRegistry(List.of()), surface).capture(USER_ID);

        var price = mapper.readTree(jdbc.queryForObject("""
                SELECT payload::text FROM investment_security_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL' ORDER BY created_at DESC LIMIT 1
                """, String.class, USER_ID)).path("price");
        assertThat(price.path("latestPrice").decimalValue()).isEqualByComparingTo("105");
        assertThat(price.path("regularClose").decimalValue()).isEqualByComparingTo("100");
        assertThat(price.path("regularCloseSessionDate").asText()).isEqualTo(previous.toString());
        assertThat(price.path("lastCompletedSessionDate").asText()).isEqualTo(previous.toString());
        assertThat(Instant.parse(price.path("regularCloseValidUntil").asText())).isEqualTo(nextClose.toInstant());
        assertThat(price.path("regularCloseStatus").asText()).isEqualTo("OK");
        assertThat(price.path("session").isNull()).isTrue();
        assertThat(price.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(price.path("sessionReason").asText()).isEqualTo("TOSS_SESSION_UNVERIFIED");
        assertThat(price.has("nextDeclaredIntervalStartsAt")).isFalse();
    }

    @Test
    void tossSessionClassificationUsesOffsetRangesAndExclusiveEnd() throws Exception {
        var edt = mapper.readTree("""
                {"today":{"date":"2026-07-06",
                  "preMarket":{"startTime":"2026-07-06T04:00:00-04:00","endTime":"2026-07-06T09:30:00-04:00"},
                  "regularMarket":{"startTime":"2026-07-06T09:30:00-04:00","endTime":"2026-07-06T16:00:00-04:00"}}}
                """);
        assertThat(InvestmentContextService.tossSession(
                Instant.parse("2026-07-06T13:30:00Z"), LocalDate.parse("2026-07-06"), edt))
                .isEqualTo("LIVE_REGULAR");
        assertThat(InvestmentContextService.tossSession(
                Instant.parse("2026-07-06T20:00:00Z"), LocalDate.parse("2026-07-06"), edt)).isNull();

        var est = mapper.readTree("""
                {"today":{"date":"2026-11-03",
                  "regularMarket":{"startTime":"2026-11-03T09:30:00-05:00","endTime":"2026-11-03T16:00:00-05:00"},
                  "afterMarket":{"startTime":"2026-11-03T16:00:00-05:00","endTime":"2026-11-03T20:00:00-05:00"}}}
                """);
        assertThat(InvestmentContextService.tossSession(
                Instant.parse("2026-11-03T14:30:00Z"), LocalDate.parse("2026-11-03"), est))
                .isEqualTo("LIVE_REGULAR");
        assertThat(InvestmentContextService.tossSession(
                Instant.parse("2026-11-03T21:00:00Z"), LocalDate.parse("2026-11-03"), est))
                .isEqualTo("AFTER_HOURS");
        assertThat(InvestmentContextService.tossSession(
                Instant.parse("2026-11-03T14:30:00Z"), LocalDate.parse("2026-11-04"), est)).isNull();

        var overlapping = mapper.readTree("""
                {"today":{"date":"2026-07-06",
                  "preMarket":{"startTime":"2026-07-06T09:00:00-04:00","endTime":"2026-07-06T10:00:00-04:00"},
                  "regularMarket":{"startTime":"2026-07-06T09:30:00-04:00","endTime":"2026-07-06T16:00:00-04:00"}}}
                """);
        assertThat(InvestmentContextService.tossSession(
                Instant.parse("2026-07-06T13:45:00Z"), LocalDate.parse("2026-07-06"), overlapping)).isNull();

        var corruptBounds = mapper.readTree("""
                {"today":{"date":"2026-07-06",
                  "regularMarket":{"startTime":"2026-07-06T16:00:00-04:00","endTime":"2026-07-06T09:30:00-04:00"}}}
                """);
        assertThat(InvestmentContextService.tossSession(
                Instant.parse("2026-07-06T13:45:00Z"), LocalDate.parse("2026-07-06"), corruptBounds)).isNull();

        var dayMarketOnly = mapper.readTree("""
                {"today":{"date":"2026-07-06",
                  "dayMarket":{"startTime":"2026-07-06T09:00:00+09:00","endTime":"2026-07-06T16:50:00+09:00"}}}
                """);
        assertThat(InvestmentContextService.tossSession(
                Instant.parse("2026-07-06T05:00:00Z"), LocalDate.parse("2026-07-06"), dayMarketOnly)).isNull();
    }

    @Test
    void tossSessionSupportsOfficialKstAndLegacyAdjacentBusinessDayWindows() throws Exception {
        var official = mapper.readTree("""
                {"previousBusinessDay":{"date":"2026-08-05",
                  "regularMarket":{"startTime":"2026-08-05T22:30:00+09:00","endTime":"2026-08-06T05:00:00+09:00"}},
                 "today":{"date":"2026-08-06","regularMarket":null},
                 "nextBusinessDay":{"date":"2026-08-07","regularMarket":null}}
                """);
        assertThat(InvestmentContextService.tossSession(
                Instant.parse("2026-08-05T19:30:00Z"), LocalDate.parse("2026-08-05"), official))
                .isEqualTo("LIVE_REGULAR");

        var legacy = mapper.readTree("""
                {"previousBusinessDay":{"date":"2026-08-05",
                  "regularMarket":{"open":"2026-08-05T22:30:00+09:00","close":"2026-08-06T05:00:00+09:00"}},
                 "today":{"date":"2026-08-06","regularMarket":null}}
                """);
        assertThat(InvestmentContextService.tossSession(
                Instant.parse("2026-08-05T19:30:00Z"), LocalDate.parse("2026-08-05"), legacy))
                .isEqualTo("LIVE_REGULAR");

        var conflictingAliases = mapper.readTree("""
                {"today":{"date":"2026-08-05",
                  "regularMarket":{"startTime":"2026-08-05T22:30:00+09:00","open":"2026-08-05T22:31:00+09:00",
                    "endTime":"2026-08-06T05:00:00+09:00"}}}
                """);
        assertThat(InvestmentContextService.tossSession(
                Instant.parse("2026-08-05T19:30:00Z"), LocalDate.parse("2026-08-05"), conflictingAliases)).isNull();
    }

    private void assertPipelineFailure(String error, OffsetDateTime lastSuccess) {
        var row = jdbc.queryForMap("""
                SELECT status, last_error FROM investment_pipeline_state
                 WHERE user_id = ? AND pipeline = 'SECURITY_DATA'
                """, USER_ID);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("last_error").toString()).contains(error);
        assertThat(jdbc.queryForObject("""
                SELECT last_success_at FROM investment_pipeline_state
                 WHERE user_id = ? AND pipeline = 'SECURITY_DATA'
                """, OffsetDateTime.class, USER_ID)).isEqualTo(lastSuccess);
    }

    private InvestmentContextService.DecisionInput decision(
            UUID decisionId, Instant asOf, String referencePrice, String confidence
    ) {
        return new InvestmentContextService.DecisionInput(decisionId, asOf, "aapl", "hold",
                new BigDecimal(referencePrice), "regular_close", "1Y", "Thesis remains intact",
                "Revenue declines for two quarters", "Review after next filing", new BigDecimal(confidence));
    }

    private InvestmentContextService service(StockDataProvider provider) {
        return service(new StockDataProviderRegistry(List.of(provider)));
    }

    private InvestmentContextService service(StockDataProviderRegistry providers) {
        var riskPolicies = mock(RiskPolicyService.class);
        when(riskPolicies.current(USER_ID)).thenReturn(new RiskPolicyService.RiskPolicySnapshot(
                0, new BigDecimal("10000000"), new BigDecimal("10000"),
                new BigDecimal("100"), new BigDecimal("0.25"), false));
        return new InvestmentContextService(jdbc, mapper, transactions, providers,
                mock(ObjectProvider.class), mock(PortfolioReadService.class), mock(MonitoringWatchlistService.class),
                riskPolicies, Duration.ofMinutes(15), Duration.ofDays(7),
                Duration.ofDays(210), Duration.ofDays(10));
    }

    private InvestmentContextService service(
            StockDataProviderRegistry providers, BrokerSurfaceService surface
    ) {
        var surfaces = mock(ObjectProvider.class);
        when(surfaces.getIfAvailable()).thenReturn(surface);
        return new InvestmentContextService(jdbc, mapper, transactions, providers, surfaces,
                mock(PortfolioReadService.class), mock(MonitoringWatchlistService.class),
                mock(RiskPolicyService.class), Duration.ofMinutes(15), Duration.ofDays(7),
                Duration.ofDays(210), Duration.ofDays(10));
    }

    private UUID insertActiveTossConnection() {
        return insertActiveTossConnection(USER_ID);
    }

    private UUID insertActiveTossConnection(UUID userId) {
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

    private BrokerSurfaceResponse<BrokerSurfaceResponse.MarketCalendarView> calendarResponse(
            String date, String regularStart, String regularEnd, String afterStart, String afterEnd
    ) throws Exception {
        return calendarResponse("US", date, regularStart, regularEnd, afterStart, afterEnd);
    }

    private BrokerSurfaceResponse<BrokerSurfaceResponse.MarketCalendarView> calendarResponse(
            String market, String date, String regularStart, String regularEnd, String afterStart, String afterEnd
    ) throws Exception {
        var today = mapper.createObjectNode().put("date", date);
        if (regularStart != null && regularEnd != null) {
            today.putObject("regularMarket").put("startTime", regularStart).put("endTime", regularEnd);
        } else {
            today.putNull("regularMarket");
        }
        if (afterStart != null && afterEnd != null) {
            today.putObject("afterMarket").put("startTime", afterStart).put("endTime", afterEnd);
        } else {
            today.putNull("afterMarket");
        }
        var payload = mapper.createObjectNode().set("today", today);
        return BrokerSurfaceResponse.available(new BrokerSurfaceResponse.MarketCalendarView(market, payload));
    }

    private StockDataProvider provider(boolean unavailable) {
        return new StockDataProvider() {
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
                return Set.of("price.latestPrice");
            }

            @Override
            public List<ProviderValue> fetch(ProviderRequest request) {
                if (unavailable) throw new ProviderUnavailableException(id(), "HTTP_402");
                return List.of(new ProviderValue("price.latestPrice", null, null, null, null, null,
                        List.of("DATA_NOT_PRESENT")));
            }
        };
    }

    private StockDataProviderRegistry canonicalProviders() {
        var fiscalPeriod = LocalDate.now(ZoneOffset.UTC).minusDays(35);
        var toss = providerWithValues(List.of(
                decimal("price.regularClose", "100", Instant.parse("2026-10-02T20:00:00Z")),
                observedText("price.session", "REGULAR_CLOSE", Instant.parse("2026-10-02T20:00:00Z"))),
                StockDataProviderId.TOSS);
        var sec = providerWithValues(List.of(
                text("fundamental.fiscalPeriod", fiscalPeriod.toString(), fiscalPeriod),
                text("fundamental.reportedAt", fiscalPeriod.plusDays(10) + "T12:00:00Z", fiscalPeriod),
                decimal("fundamental.cash", "50", fiscalPeriod),
                decimal("fundamental.revenueTTM", "1000", fiscalPeriod)), StockDataProviderId.SEC);
        return new StockDataProviderRegistry(List.of(toss, sec));
    }

    private StockDataProvider providerWithValues(List<ProviderValue> values) {
        return providerWithValues(new AtomicReference<>(values));
    }

    private StockDataProvider providerWithValues(List<ProviderValue> values, StockDataProviderId providerId) {
        return providerWithValues(new AtomicReference<>(values), providerId);
    }

    private StockDataProvider providerWithValues(AtomicReference<List<ProviderValue>> values) {
        return providerWithValues(values, StockDataProviderId.FMP);
    }

    private StockDataProvider providerWithValues(
            AtomicReference<List<ProviderValue>> values, StockDataProviderId providerId
    ) {
        return new StockDataProvider() {
            @Override
            public StockDataProviderId id() {
                return providerId;
            }

            @Override
            public DataProviderRole role() {
                return ProviderCatalog.roleOf(providerId);
            }

            @Override
            public Set<String> fields() {
                return values.get().stream().map(ProviderValue::field).collect(java.util.stream.Collectors.toSet());
            }

            @Override
            public List<ProviderValue> fetch(ProviderRequest request) {
                return values.get();
            }
        };
    }

    private List<ProviderValue> fmpFundamentals(
            LocalDate balancePeriod, LocalDate incomePeriod, LocalDate cashFlowPeriod,
            LocalDate balanceFilingDate, LocalDate incomeFilingDate, LocalDate cashFlowFilingDate,
            Instant quoteAsOf, String marketCap, String cash, String debt, String revenue,
            String ebitda, String eps, String dilutedShares, String fcf
    ) {
        return List.of(
                text("fundamental.fiscalPeriod", balancePeriod.toString(), balancePeriod),
                text("fundamental.reportedAt", balanceFilingDate.toString(), balancePeriod),
                text("fundamental.incomeFiscalPeriod", incomePeriod.toString(), incomePeriod),
                text("fundamental.incomeReportedAt", incomeFilingDate.toString(), incomePeriod),
                text("fundamental.cashFlowFiscalPeriod", cashFlowPeriod.toString(), cashFlowPeriod),
                text("fundamental.cashFlowReportedAt", cashFlowFilingDate.toString(), cashFlowPeriod),
                text("fundamental.fiscalYear", Integer.toString(incomePeriod.getYear()), incomePeriod),
                text("fundamental.fiscalPeriodCode", "Q2", incomePeriod),
                decimal("fundamental.marketCap", marketCap, quoteAsOf),
                decimal("fundamental.cash", cash, balancePeriod),
                decimal("fundamental.debt", debt, balancePeriod),
                decimal("fundamental.revenueTTM", revenue, incomePeriod),
                decimal("fundamental.ebitdaTTM", ebitda, incomePeriod),
                decimal("fundamental.eps", eps, incomePeriod),
                decimal("fundamental.dilutedShares", dilutedShares, incomePeriod),
                decimal("fundamental.fcfTTM", fcf, cashFlowPeriod));
    }

    private ProviderValue text(String field, String value, LocalDate asOf) {
        return new ProviderValue(field, mapper.valueToTree(value), null, null, null,
                asOf.atStartOfDay().toInstant(ZoneOffset.UTC), List.of());
    }

    private ProviderValue decimal(String field, String value, Instant asOf) {
        return new ProviderValue(field, mapper.valueToTree(new BigDecimal(value)), null, null, null, asOf, List.of());
    }

    private ProviderValue usdDecimal(String field, String value, Instant asOf) {
        return new ProviderValue(field, mapper.valueToTree(new BigDecimal(value)), "USD", null, null,
                asOf, List.of());
    }

    private ProviderValue usdDecimal(String field, String value, LocalDate asOf) {
        return usdDecimal(field, value, asOf.atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    private ProviderValue unitDecimal(String field, String value, String unit, LocalDate asOf) {
        return new ProviderValue(field, mapper.valueToTree(new BigDecimal(value)), unit, null, null,
                asOf.atStartOfDay().toInstant(ZoneOffset.UTC), List.of());
    }

    private ProviderValue decimal(String field, String value, LocalDate asOf) {
        return new ProviderValue(field, mapper.valueToTree(new BigDecimal(value)), null, null, null,
                asOf.atStartOfDay().toInstant(ZoneOffset.UTC), List.of());
    }

    private List<ProviderValue> withIncomeHistory(List<ProviderValue> values,
                                                 tools.jackson.databind.JsonNode history,
                                                 LocalDate asOf) {
        var result = new java.util.ArrayList<>(values);
        result.add(new ProviderValue("fundamental.incomeHistory", history, null, null, null,
                asOf.atStartOfDay().toInstant(ZoneOffset.UTC), List.of()));
        return List.copyOf(result);
    }

    private ProviderValue observedText(String field, String value, Instant observedAt) {
        return new ProviderValue(field, mapper.valueToTree(value), null, null, null, observedAt,
                List.of(), com.jmj.trade.marketdata.StockAnalysisInput.AsOfBasis.OBSERVED_AT);
    }

    private ProviderValue observedDecimal(String field, String value, Instant observedAt) {
        return new ProviderValue(field, mapper.valueToTree(new BigDecimal(value)), null, null, null, observedAt,
                List.of(), com.jmj.trade.marketdata.StockAnalysisInput.AsOfBasis.OBSERVED_AT);
    }
}
