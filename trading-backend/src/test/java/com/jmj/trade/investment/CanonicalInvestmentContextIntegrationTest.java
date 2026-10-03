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
import com.jmj.trade.monitoring.MonitoringWatchlistService;
import com.jmj.trade.risk.RiskPolicyService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
                INSERT INTO monitoring_watchlist (id, user_id, symbol, status, levels, evidence,
                                                  observed_at, created_at, updated_at)
                VALUES (?, ?, 'AAPL', 'WATCH',
                        '{"prepare":{"min":1,"max":1},"confirm":{"min":2,"max":2},
                          "pullback":{"min":3,"max":3},"invalidate":{"min":0,"max":0}}'::jsonb,
                        '{}'::jsonb, ?, ?, ?)
                """, UUID.randomUUID(), USER_ID, now, now, now);
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
    void captureAndContextRetainFourHeldPositionsAndAddTwoUnheldSymbols() {
        var symbols = List.of("AAPL", "MSFT", "NVDA", "AMZN");
        var connectionId = seedHeldPositions(symbols);
        var portfolio = mock(PortfolioReadService.class);
        var positionViews = symbols.stream().map(symbol -> portfolioPosition(symbol, Instant.now())).toList();
        var account = new PortfolioReadService.AccountView("CASH", "TEST",
                Map.of("USD", new BigDecimal("4000")), Map.of("USD", new BigDecimal("4000")),
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
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM investment_security_snapshots
                 WHERE user_id = ? AND ticker IN ('AAPL','AMZN','GOOGL','MSFT','NVDA','VST')
                """, Integer.class, USER_ID)).isEqualTo(6);
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
        var period = LocalDate.now(ZoneOffset.UTC).minusDays(3);
        var sharesAsOf = LocalDate.now(ZoneOffset.UTC).minusDays(4);
        var balanceAsOf = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);
        return provider(StockDataProviderId.SEC, SEC_FIELDS, request -> List.of(
                text("fundamental.fiscalPeriod", period.toString(), period.atStartOfDay().toInstant(ZoneOffset.UTC)),
                text("fundamental.reportedAt", balanceAsOf.toString(), balanceAsOf),
                text("fundamental.fiscalYear", Integer.toString(period.getYear()),
                        period.atStartOfDay().toInstant(ZoneOffset.UTC)),
                text("fundamental.fiscalPeriodCode", "Q3", period.atStartOfDay().toInstant(ZoneOffset.UTC)),
                decimal("fundamental.cash", "50", "USD", balanceAsOf, "CashAndCashEquivalentsAtCarryingValue"),
                decimal("fundamental.debt", "200", "USD", balanceAsOf, "DebtCurrent+LongTermDebtNoncurrent"),
                decimal("fundamental.basicShares", "1000", "shares",
                        sharesAsOf.atStartOfDay().toInstant(ZoneOffset.UTC), "EntityCommonStockSharesOutstanding"),
                text("fundamental.basicSharesBasis", "EntityCommonStockSharesOutstanding_INSTANT",
                        sharesAsOf.atStartOfDay().toInstant(ZoneOffset.UTC)),
                missing("fundamental.dilutedShares", "DATA_NOT_PRESENT", balanceAsOf),
                decimal("fundamental.revenueTTM", "1000", "USD", balanceAsOf,
                        "RevenueFromContractWithCustomerExcludingAssessedTax"),
                decimal("fundamental.ebitdaTTM", "200", "USD", balanceAsOf, "CALCULATED_EBITDA"),
                decimal("fundamental.eps", "5", "USD/share", balanceAsOf, "EarningsPerShareDiluted"),
                decimal("fundamental.fcfTTM", "100", "USD", balanceAsOf, "CALCULATED_FCF"),
                text("fundamental.currency", "USD", balanceAsOf)));
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
        return new PortfolioReadService.PositionView(symbol, symbol, "US", BigDecimal.ONE, "USD",
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

    private ProviderValue missing(String field, String reason, Instant asOf) {
        return new ProviderValue(field, null, null, null, null, asOf, List.of(reason));
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
