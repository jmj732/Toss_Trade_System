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
import com.jmj.trade.monitoring.MonitoringWatchlistService;
import com.jmj.trade.risk.RiskPolicyService;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
    private DataSourceTransactionManager transactions;
    private ObjectMapper mapper;

    @BeforeEach
    void migrateAndSeed() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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

    @Test
    void providerFailureKeepsSnapshotAndLastSuccessfulCollectionTime() {
        var completed = service(provider(false)).capture(USER_ID);
        var lastSuccess = jdbc.queryForObject("""
                SELECT last_success_at FROM investment_pipeline_state
                 WHERE user_id = ? AND pipeline = 'SECURITY_DATA'
                """, OffsetDateTime.class, USER_ID);
        assertThat(completed).isEqualTo(1);
        assertThat(lastSuccess).isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT status FROM investment_pipeline_state
                 WHERE user_id = ? AND pipeline = 'SECURITY_DATA'
                """, String.class, USER_ID)).isEqualTo("SUCCEEDED");

        var noProviders = service(new StockDataProviderRegistry(List.of()));
        assertThat(noProviders.capture(USER_ID)).isEqualTo(1);
        assertPipelineFailure("NO_DATA_COLLECTED", lastSuccess);

        var unavailable = service(provider(true));
        assertThat(unavailable.capture(USER_ID)).isEqualTo(1);
        assertPipelineFailure("PROVIDER_HTTP_402", lastSuccess);
        assertThat(unavailable.context(USER_ID).pipeline().lastError()).isEqualTo("PROVIDER_HTTP_402");
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

        assertThatThrownBy(unavailable::captureAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("one or more investment data captures failed");
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
    void coherentFmpStatementsPersistDerivedEvWithoutUsingQuoteTimeAsFinancialAsOf() {
        var period = LocalDate.now(ZoneOffset.UTC).minusDays(45);
        var filingDate = period.plusDays(35);
        var quoteAsOf = Instant.now().minusSeconds(30);
        var provider = providerWithValues(fmpFundamentals(period, period, period,
                filingDate, filingDate, filingDate, quoteAsOf, "1000000", "100", "300",
                "1000", "200", "4.2", "100", "120"));

        assertThat(service(provider).capture(USER_ID)).isEqualTo(1);

        var row = jdbc.queryForMap("""
                SELECT fiscal_period, reported_at, as_of, market_cap, enterprise_value
                  FROM fundamental_snapshots WHERE user_id = ? AND ticker = 'AAPL'
                """, USER_ID);
        assertThat(row.get("fiscal_period")).isEqualTo(period.toString());
        assertThat(row.get("as_of").toString()).contains(period.toString());
        assertThat(row.get("reported_at").toString()).contains(filingDate.toString());
        assertThat((BigDecimal) row.get("market_cap")).isEqualByComparingTo("1000000");
        assertThat((BigDecimal) row.get("enterprise_value")).isEqualByComparingTo("1000200");
        assertThat(row.get("revenue_growth_yoy")).isNull();

        var persistedQuoteAsOf = quoteAsOf.truncatedTo(ChronoUnit.MICROS);
        var snapshot = jdbc.queryForMap("""
                SELECT payload -> 'fundamentals' AS fundamentals
                  FROM investment_security_snapshots WHERE user_id = ? AND ticker = 'AAPL'
                ORDER BY created_at DESC LIMIT 1
                """, USER_ID).get("fundamentals").toString();
        assertThat(snapshot).contains("\"asOf\": \"" + period + "T00:00:00Z\"")
                .contains("\"marketCapAsOf\": \"" + persistedQuoteAsOf + "\"")
                .contains("\"enterpriseValueAsOf\": \"" + persistedQuoteAsOf + "\"")
                .contains("\"enterpriseValueSource\": \"FMP_MARKET_CAP_PLUS_BALANCE_SHEET\"")
                .contains("\"dilutedSharesBasis\": \"WEIGHTED_AVERAGE_TTM\"");
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
    void mismatchedFmpStatementPeriodsAreNotJoinedOrPersistedAsFundamentals() {
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
        assertThat(snapshot.get("fundamentals").toString()).contains("\"status\": \"SOURCE_CONFLICT\"");
        assertThat(snapshot.get("readiness").toString()).contains("\"overallDataStatus\": \"SOURCE_CONFLICT\"")
                .contains("fundamentals.statementPeriodConflict");
        assertThat(snapshot.get("price").toString()).doesNotContain("SOURCE_CONFLICT");
    }

    @Test
    void revenueGrowthUsesOnlyMatchingPriorFiscalYearAndQuarter() {
        var period = LocalDate.now(ZoneOffset.UTC).minusDays(45);
        var priorPeriod = period.minusYears(1);
        var priorFilingDate = priorPeriod.plusDays(35);
        var currentFilingDate = period.plusDays(35);
        var values = new AtomicReference<>(fmpFundamentals(priorPeriod, priorPeriod, priorPeriod,
                priorFilingDate, priorFilingDate, priorFilingDate, Instant.now().minusSeconds(60),
                "1000000", "100", "300", "1000", "200", "4.2", "100", "120"));
        var service = service(providerWithValues(values));

        service.capture(USER_ID);
        values.set(fmpFundamentals(period, period, period, currentFilingDate, currentFilingDate, currentFilingDate,
                Instant.now().minusSeconds(30), "1000000", "100", "300", "1200", "200", "4.2", "100", "120"));
        service.capture(USER_ID);

        var rows = jdbc.queryForList("""
                SELECT fiscal_year, fiscal_period_code, revenue_ttm, revenue_growth_yoy
                  FROM fundamental_snapshots WHERE user_id = ? AND ticker = 'AAPL'
                 ORDER BY fiscal_period
                """, USER_ID);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("fiscal_year", "2025").containsEntry("fiscal_period_code", "Q2");
        assertThat(rows.get(1)).containsEntry("fiscal_year", "2026").containsEntry("fiscal_period_code", "Q2");
        assertThat((BigDecimal) rows.get(1).get("revenue_growth_yoy")).isEqualByComparingTo("0.2");
    }

    @Test
    void incomeHistoryRequiresExactFiscalYearAndPeriodAndRejectsFutureRows() {
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
        var values = new AtomicReference<>(withIncomeHistory(fmpFundamentals(period, period, period,
                filingDate, filingDate, filingDate, Instant.now().minusSeconds(30),
                "1000000", "100", "300", "1200", "200", "4.2", "100", "120"), history, period));
        var service = service(providerWithValues(values));

        service.capture(USER_ID);
        assertThat(jdbc.queryForObject("""
                SELECT revenue_growth_yoy FROM fundamental_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL'
                """, BigDecimal.class, USER_ID)).isNull();

        history.addObject().put("date", priorPeriod.toString())
                .put("fiscalYear", Integer.toString(priorPeriod.getYear()))
                .put("period", "Q2").put("revenue", 1000);
        values.set(withIncomeHistory(fmpFundamentals(period, period, period,
                filingDate, filingDate, filingDate, Instant.now().minusSeconds(20),
                "1000000", "100", "300", "1200", "200", "4.2", "100", "120"), history, period));
        service.capture(USER_ID);

        var growthValues = jdbc.queryForList("""
                SELECT revenue_growth_yoy FROM fundamental_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL'
                 ORDER BY created_at
                """, USER_ID);
        assertThat(growthValues).hasSize(2);
        assertThat(growthValues.get(0).get("revenue_growth_yoy")).isNull();
        assertThat((BigDecimal) growthValues.get(1).get("revenue_growth_yoy")).isEqualByComparingTo("0.2");
    }

    @Test
    void incomeHistoryUsesExactIsoPriorDateOnlyWhenCurrentFiscalMetadataIsMissing() {
        var period = LocalDate.now(ZoneOffset.UTC).minusDays(45);
        var priorPeriod = period.minusYears(1);
        var filingDate = period.plusDays(35);
        var history = mapper.createArrayNode();
        history.addObject().put("date", priorPeriod.plusDays(1).toString()).put("revenue", 5000);
        history.addObject().put("date", priorPeriod.toString()).put("revenue", 1000);
        var currentValues = fmpFundamentals(period, period, period,
                filingDate, filingDate, filingDate, Instant.now().minusSeconds(30),
                "1000000", "100", "300", "1200", "200", "4.2", "100", "120").stream()
                .filter(value -> !value.field().equals("fundamental.fiscalYear")
                        && !value.field().equals("fundamental.fiscalPeriodCode"))
                .toList();
        var provider = providerWithValues(withIncomeHistory(currentValues, history, period));

        assertThat(service(provider).capture(USER_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT revenue_growth_yoy FROM fundamental_snapshots
                 WHERE user_id = ? AND ticker = 'AAPL'
                """, BigDecimal.class, USER_ID)).isEqualByComparingTo("0.2");

        var partialMetadata = fmpFundamentals(period, period, period,
                filingDate, filingDate, filingDate, Instant.now().minusSeconds(20),
                "1000000", "100", "300", "1200", "200", "4.2", "100", "120").stream()
                .filter(value -> !value.field().equals("fundamental.fiscalPeriodCode"))
                .toList();
        assertThat(service(providerWithValues(withIncomeHistory(partialMetadata, history, period)))
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

        var fmpValues = new java.util.ArrayList<>(fmpFundamentals(period, period, period,
                period.plusDays(35), period.plusDays(35), period.plusDays(35), observedAt,
                "1000000", "100", "300", "1000", "200", "4.2", "100", "120"));
        fmpValues.add(observedDecimal("price.latestPrice", "100", observedAt));
        fmpValues.add(observedText("price.session", "LIVE_REGULAR", observedAt));
        var alphaValues = List.of(
                observedText("consensus.horizon", LocalDate.now(ZoneOffset.UTC).plusYears(1).toString(), observedAt),
                observedDecimal("consensus.revenueConsensus", "2000", observedAt),
                observedDecimal("consensus.epsConsensus", "5", observedAt),
                observedDecimal("consensus.ebitdaConsensus", "500", observedAt),
                observedDecimal("consensus.fcfConsensus", "200", observedAt));

        var providers = new StockDataProviderRegistry(List.of(
                providerWithValues(fmpValues), providerWithValues(alphaValues, StockDataProviderId.ALPHA_VANTAGE)));
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
        assertThat(valuation.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(readiness.get("overallDataStatus").asText()).isEqualTo("PARTIAL");
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
                """, String.class, USER_ID)).isEqualTo("PROVIDER_HTTP_402");
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
                """, String.class, USER_ID)).isEqualTo("PROVIDER_HTTP_402");
        assertThat(jdbc.queryForObject("""
                SELECT payload::text FROM analysis_input_snapshots
                 WHERE user_id = ? ORDER BY created_at DESC LIMIT 1
                """, String.class, USER_ID)).contains("TOSS_CANDLE_TIMESTAMP_INVALID");
        verify(surface).candles(USER_ID, connectionId, "AAPL", "1d", 100, null, false);
        service(new StockDataProviderRegistry(List.of(provider(true))), surface).captureQuoteUpdates(USER_ID);
        verify(surface, org.mockito.Mockito.times(1)).candles(USER_ID, connectionId, "AAPL", "1d", 100, null, false);
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
        assertThat(row.get("last_error")).isEqualTo(error);
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
