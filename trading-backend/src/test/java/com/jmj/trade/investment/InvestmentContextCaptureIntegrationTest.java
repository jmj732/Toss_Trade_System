package com.jmj.trade.investment;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.marketdata.DataProviderRole;
import com.jmj.trade.marketdata.ProviderRequest;
import com.jmj.trade.marketdata.ProviderUnavailableException;
import com.jmj.trade.marketdata.ProviderValue;
import com.jmj.trade.marketdata.StockDataProvider;
import com.jmj.trade.marketdata.StockDataProviderId;
import com.jmj.trade.marketdata.StockDataProviderRegistry;
import com.jmj.trade.monitoring.MonitoringWatchlistService;
import com.jmj.trade.risk.RiskPolicyService;
import org.flywaydb.core.Flyway;
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
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
        assertPipelineFailure("PROVIDER_UNAVAILABLE", lastSuccess);
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

        var snapshot = jdbc.queryForMap("""
                SELECT payload -> 'fundamentals' AS fundamentals
                  FROM investment_security_snapshots WHERE user_id = ? AND ticker = 'AAPL'
                ORDER BY created_at DESC LIMIT 1
                """, USER_ID).get("fundamentals").toString();
        assertThat(snapshot).contains("\"asOf\": \"" + period + "T00:00:00Z\"")
                .contains("\"marketCapAsOf\": \"" + quoteAsOf + "\"")
                .contains("\"enterpriseValueAsOf\": \"" + quoteAsOf + "\"")
                .contains("\"enterpriseValueSource\": \"FMP_MARKET_CAP_PLUS_BALANCE_SHEET\"")
                .contains("\"dilutedSharesBasis\": \"WEIGHTED_AVERAGE_TTM\"");
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

    private InvestmentContextService service(StockDataProvider provider) {
        return service(new StockDataProviderRegistry(List.of(provider)));
    }

    private InvestmentContextService service(StockDataProviderRegistry providers) {
        return new InvestmentContextService(jdbc, mapper, transactions, providers,
                mock(PortfolioReadService.class), mock(MonitoringWatchlistService.class),
                mock(RiskPolicyService.class), Duration.ofMinutes(15), Duration.ofDays(7),
                Duration.ofDays(210), Duration.ofDays(10));
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
                if (unavailable) throw new ProviderUnavailableException(id(), "HTTP_503");
                return List.of(new ProviderValue("price.latestPrice", null, null, null, null, null,
                        List.of("DATA_NOT_PRESENT")));
            }
        };
    }

    private StockDataProvider providerWithValues(List<ProviderValue> values) {
        return providerWithValues(new AtomicReference<>(values));
    }

    private StockDataProvider providerWithValues(AtomicReference<List<ProviderValue>> values) {
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
