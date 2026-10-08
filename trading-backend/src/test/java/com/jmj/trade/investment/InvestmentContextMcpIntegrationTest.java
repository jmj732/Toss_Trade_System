package com.jmj.trade.investment;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.account.BrokerSurfaceService;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.connector.ConnectorApiKeyService;
import com.jmj.trade.connector.ConnectorMcpController;
import com.jmj.trade.connector.ConnectorMcpProtocol;
import com.jmj.trade.connector.ConnectorService;
import com.jmj.trade.investment.tactical.TacticalOverlayAggregationCalculator;
import com.jmj.trade.investment.tactical.TacticalOverlayCalculator;
import com.jmj.trade.investment.tactical.TacticalOverlayProperties;
import com.jmj.trade.investment.tactical.TacticalOverlayService;
import com.jmj.trade.marketdata.DataProviderRole;
import com.jmj.trade.marketdata.ProviderRequest;
import com.jmj.trade.marketdata.ProviderValue;
import com.jmj.trade.marketdata.StockDataProvider;
import com.jmj.trade.marketdata.StockDataProviderId;
import com.jmj.trade.marketdata.StockDataProviderRegistry;
import com.jmj.trade.monitoring.MonitoringWatchlistService;
import com.jmj.trade.risk.RiskPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class InvestmentContextMcpIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("01990000-0000-7000-8000-000000000001");
    private static final UUID CONNECTION_ID = UUID.fromString("01990000-0000-7000-8000-000000000002");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final List<String> READ_TABLES = List.of(
            "analysis_input_snapshots", "investment_price_snapshots", "investment_security_snapshots",
            "investment_tactical_overlay_inputs", "investment_tactical_overlay_bar_snapshots",
            "investment_tactical_overlay_snapshots", "investment_pipeline_state", "investment_decision_ledger",
            "investment_thesis_states");

    private JdbcTemplate jdbc;
    private HikariDataSource dataSource;
    private DataSourceTransactionManager transactions;
    private ObjectMapper mapper;

    @BeforeEach
    void migrateAndSeedUser() {
        freshMigratedSchema();
        dataSource = pooledTestDataSource();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        mapper = new ObjectMapper();
        jdbc.update("INSERT INTO users (id) VALUES (?)", USER_ID);
    }

    @AfterEach
    void closeTestDataSource() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void contextRestAndMcpReturnIdenticalReadOnlyInvestmentContext() throws Exception {
        var now = Instant.parse("2026-10-06T20:00:00Z");
        var entryDate = LocalDate.parse("2026-10-05");
        var markAsOf = now.minus(Duration.ofHours(2));
        var barSourceAsOf = entryDate.atTime(16, 0).atZone(NEW_YORK).toInstant();
        var properties = TacticalOverlayProperties.defaults();
        var overlay = new TacticalOverlayService(jdbc, mapper, new TacticalOverlayCalculator(properties),
                properties, new TacticalOverlayAggregationCalculator(), "AVT");
        overlay.recordTossBars(USER_ID, "AVT", mapper.valueToTree(List.of(barRow(entryDate, "100", barSourceAsOf))),
                now, null);
        insertTossLatestPrice(markAsOf, now);
        overlay.putPerformanceEntry(USER_ID, "AVT", new TacticalOverlayService.PerformanceInput(
                "mcp-stale-mark", null, entryDate, bd("100"), bd("90"), bd("5"),
                "breakout", "NO_EFFECT", List.of(), "USER_INPUT", now));
        var securityAsOf = now.minusSeconds(1);
        insertSecurityRevisionSnapshot(securityAsOf);

        var providerCalls = new AtomicInteger();
        StockDataProvider provider = new StockDataProvider() {
            @Override public StockDataProviderId id() { return StockDataProviderId.FMP; }
            @Override public DataProviderRole role() { return DataProviderRole.FUNDAMENTALS; }
            @Override public Set<String> fields() { return Set.of("fundamental.cash"); }
            @Override public List<ProviderValue> fetch(ProviderRequest request) {
                providerCalls.incrementAndGet();
                return List.of();
            }
        };
        var portfolios = mock(PortfolioReadService.class);
        var watchlist = mock(MonitoringWatchlistService.class);
        when(watchlist.list(USER_ID)).thenReturn(List.of());
        var risks = mock(RiskPolicyService.class);
        when(risks.current(USER_ID)).thenReturn(new RiskPolicyService.RiskPolicySnapshot(
                0, bd("10000000"), bd("10000"), bd("100"), bd("0.25"), false));
        @SuppressWarnings("unchecked")
        ObjectProvider<BrokerSurfaceService> surfaceProvider = mock(ObjectProvider.class);
        when(surfaceProvider.getIfAvailable()).thenReturn(null);
        var contextService = new InvestmentContextService(jdbc, mapper, transactions,
                new StockDataProviderRegistry(List.of(provider)), surfaceProvider, portfolios,
                watchlist, risks, Duration.ofMinutes(15), Duration.ofDays(7),
                Duration.ofDays(210), Duration.ofDays(10));
        contextService.setTacticalOverlayService(overlay);
        var connectorService = mock(ConnectorService.class);
        var protocol = new ConnectorMcpProtocol(connectorService, null, contextService, mapper);
        MockMvc contextMvc = standaloneSetup(new InvestmentContextController(contextService)).build();
        MockMvc connectorMvc = standaloneSetup(new ConnectorMcpController(
                protocol, "https://dashboard.example")).build();

        var countsBeforeRead = readTableCounts();
        var direct = mapper.readTree(mapper.writeValueAsString(contextService.context(USER_ID)));
        var contextResponse = contextMvc.perform(get("/investment/context")
                        .principal(new TestingAuthenticationToken(USER_ID.toString(), null)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var root = mapper.readTree(contextResponse);
        var mcpRequest = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", "context");
        mcpRequest.put("method", "tools/call");
        mcpRequest.putObject("params").put("name", "get_investment_context")
                .set("arguments", mapper.createObjectNode());
        var mcpResponse = connectorMvc.perform(post("/api/v1/connector/mcp")
                        .principal(readAuthentication())
                        .contentType("application/json")
                        .accept("application/json")
                        .content(mapper.writeValueAsString(mcpRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var mcp = mapper.readTree(mcpResponse).path("result");

        for (var field : List.of("portfolio", "securities", "watchlist", "riskPolicy", "decisionLedger",
                "pipeline", "tacticalOverlay", "decisionOverlays")) {
            assertThat(root.has(field)).as("root context field %s", field).isTrue();
        }
        assertThat(root).isEqualTo(direct);
        assertThat(mcp.path("isError").asBoolean()).isFalse();
        assertThat(mcp.path("structuredContent")).isEqualTo(root);
        assertThat(mcp.path("content").get(0).path("type").asText()).isEqualTo("text");
        assertThat(mapper.readTree(mcp.path("content").get(0).path("text").asText())).isEqualTo(root);
        assertOverlayStatuses(root, securityAsOf, entryDate, barSourceAsOf, now, markAsOf);
        assertThat(readTableCounts()).isEqualTo(countsBeforeRead);
        assertThat(providerCalls).hasValue(0);
        verifyNoInteractions(portfolios, connectorService);
    }

    private void assertOverlayStatuses(JsonNode root, Instant securityAsOf, LocalDate barAsOf,
                                       Instant barSourceAsOf, Instant entrySourceAsOf, Instant markAsOf) {
        var security = root.path("securities").get(0);
        assertThat(security.path("ticker").asText()).isEqualTo("AVT");
        assertThat(security.path("asOf").asText()).isEqualTo(securityAsOf.toString());
        var revision = security.path("revision");
        assertThat(revision.path("status").asText()).isEqualTo("INSUFFICIENT_HISTORY");
        for (var metricName : List.of("revenueRevision30D", "revenueRevision90D", "epsRevision30D", "epsRevision90D")) {
            var metric = revision.path(metricName);
            assertThat(metric.path("value").isNull()).isTrue();
            assertThat(metric.path("baselineAsOf").isNull()).isTrue();
            assertThat(metric.path("status").asText()).isEqualTo("INSUFFICIENT_HISTORY");
        }
        var tacticalPortfolio = root.path("tacticalOverlay");
        assertThat(tacticalPortfolio.path("themes").isNull()).isTrue();
        var tacticalSecurity = security.path("tacticalOverlay");
        assertThat(tacticalSecurity.path("themeId").isNull()).isTrue();
        assertThat(tacticalSecurity.path("anchoredVwaps").isArray()).isTrue();
        assertThat(tacticalSecurity.path("anchoredVwaps").size()).isZero();
        var performanceSnapshot = tacticalSecurity.path("performance").get(0);
        var performance = performanceSnapshot.path("performance");
        assertThat(performance.path("currentR").path("status").asText()).isEqualTo("STALE");
        assertThat(performance.path("currentR").path("value").isNull()).isTrue();
        assertThat(performanceSnapshot.path("markAsOf").asText()).isEqualTo(markAsOf.toString());
        assertThat(performanceSnapshot.path("barAsOf").asText()).isEqualTo(barAsOf.toString());
        assertThat(performanceSnapshot.path("barSourceAsOf").asText()).isEqualTo(barSourceAsOf.toString());
        assertThat(performanceSnapshot.path("entrySourceAsOf").asText()).isEqualTo(entrySourceAsOf.toString());
        assertThat(performanceSnapshot.path("sourceAsOf").asText()).isEqualTo(entrySourceAsOf.toString());
    }

    private Map<String, Integer> readTableCounts() {
        return READ_TABLES.stream().collect(java.util.stream.Collectors.toMap(
                table -> table,
                table -> jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?",
                        Integer.class, USER_ID)));
    }

    private void insertTossLatestPrice(Instant markAsOf, Instant capturedAt) {
        var inputId = UUID.randomUUID();
        var captured = OffsetDateTime.ofInstant(capturedAt, ZoneOffset.UTC);
        var sourceAsOf = OffsetDateTime.ofInstant(markAsOf, ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO analysis_input_snapshots (
                    id, user_id, symbol, schema_version, payload, payload_hash, collected_at, created_at
                ) VALUES (?, ?, 'AVT', '1', '{}'::jsonb, ?, ?, ?)
                """, inputId, USER_ID, "c".repeat(64), captured, captured);
        jdbc.update("""
                INSERT INTO investment_price_snapshots (
                    id, user_id, input_snapshot_id, ticker, as_of, session, latest_price,
                    latest_price_as_of, source, observed_at
                ) VALUES (?, ?, ?, 'AVT', ?, 'LIVE_REGULAR', 110, ?, 'TOSS', ?)
                """, UUID.randomUUID(), USER_ID, inputId, sourceAsOf, sourceAsOf, captured);
    }

    private void insertSecurityRevisionSnapshot(Instant asOf) throws Exception {
        var snapshot = mapper.createObjectNode().put("asOf", asOf.toString());
        snapshot.putObject("price").put("status", "DATA_MISSING");
        snapshot.putObject("technical").put("trendStatus", "DATA_MISSING");
        snapshot.putObject("fundamentals").put("status", "DATA_MISSING");
        snapshot.putObject("consensus").put("status", "DATA_MISSING");
        var revision = snapshot.putObject("revision").put("status", "INSUFFICIENT_HISTORY");
        for (var field : List.of("revenueRevision30D", "revenueRevision90D", "epsRevision30D", "epsRevision90D")) {
            var metric = revision.putObject(field);
            metric.putNull("value");
            metric.putNull("baselineAsOf");
            metric.put("status", "INSUFFICIENT_HISTORY");
        }
        snapshot.putObject("valuation").put("status", "DATA_MISSING");
        snapshot.putObject("readiness")
                .put("priceStatus", "DATA_MISSING")
                .put("trendStatus", "DATA_MISSING")
                .put("fundamentalStatus", "DATA_MISSING")
                .put("consensusStatus", "DATA_MISSING")
                .put("revisionStatus", "INSUFFICIENT_HISTORY")
                .put("valuationStatus", "DATA_MISSING")
                .put("balanceSheetStatus", "DATA_MISSING")
                .put("riskStatus", "DATA_MISSING")
                .put("overallDataStatus", "INSUFFICIENT_HISTORY");
        var timestamp = OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO investment_security_snapshots (id, user_id, ticker, as_of, payload, created_at)
                VALUES (?, ?, 'AVT', ?, ?::jsonb, ?)
                """, UUID.randomUUID(), USER_ID, timestamp,
                mapper.writeValueAsString(snapshot), timestamp);
    }

    private static TestingAuthenticationToken readAuthentication() {
        var token = new TestingAuthenticationToken(USER_ID.toString(), null, "SCOPE_CONNECTOR_READ");
        token.setDetails(new ConnectorApiKeyService.AuthenticatedKey(
                UUID.randomUUID(), USER_ID, CONNECTION_ID, null));
        return token;
    }

    private static Map<String, Object> barRow(LocalDate date, String close, Instant sourceAsOf) {
        var price = bd(close);
        return Map.of("date", date.toString(), "timestamp", sourceAsOf.toString(), "session", "REGULAR_CLOSE",
                "currency", "USD", "open", price, "high", price.add(BigDecimal.ONE),
                "low", price.subtract(BigDecimal.ONE), "close", price, "volume", bd("1000"));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
