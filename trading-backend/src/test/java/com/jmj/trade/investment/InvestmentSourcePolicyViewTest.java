package com.jmj.trade.investment;

import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.MarketDataAdapter;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.marketdata.StockDataProviderRegistry;
import com.jmj.trade.monitoring.MonitoringWatchlistService;
import com.jmj.trade.risk.RiskPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Readiness annotation and portfolio-view hazard cache under each source policy. */
class InvestmentSourcePolicyViewTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    private static final UUID USER = UUID.fromString("018f0000-0000-7000-8000-0000000000aa");
    private static final UUID CONNECTION = UUID.fromString("018f0000-0000-7000-8000-0000000000bb");

    @Test
    void tossOnlyReadinessShowsUnsupportedSourceAndLegacyFlag() {
        var service = service(mock(JdbcTemplate.class), mock(MarketDataAdapter.class));
        ReflectionTestUtils.setField(service, "fundamentalSourcePolicy", "TOSS_ONLY");
        var readiness = new ObjectMapper().createObjectNode();

        service.annotateSourcePolicy(readiness, decision(
                List.of(InvestmentBuyRiskGate.TOSS_SOURCE_UNSUPPORTED),
                List.of(new InvestmentBuyRiskGate.LegacyProviderData("consensus", "STALE", "ALPHA_VANTAGE",
                        NOW.minusSeconds(86400L * 60), "LEGACY_PROVIDER_EXCLUDED"))));

        assertThat(readiness.get("sourcePolicy").asText()).isEqualTo("TOSS_ONLY");
        assertThat(readiness.get("tossSourceUnsupported").asBoolean()).isTrue();
        assertThat(readiness.get("legacyProviderData").get(0).get("flag").asText())
                .isEqualTo("LEGACY_PROVIDER_EXCLUDED");
    }

    @Test
    void providersReadinessDoesNotShowTossUnsupportedOrLegacyData() {
        var service = service(mock(JdbcTemplate.class), mock(MarketDataAdapter.class));
        var readiness = new ObjectMapper().createObjectNode();

        service.annotateSourcePolicy(readiness, decision(List.of("CONSENSUS_STALE"), List.of()));

        assertThat(readiness.get("sourcePolicy").asText()).isEqualTo("PROVIDERS");
        assertThat(readiness.get("tossSourceUnsupported").asBoolean()).isFalse();
        assertThat(readiness.get("legacyProviderData").size()).isZero();
    }

    @Test
    void portfolioViewHazardsAreCachedForFiveMinutesAndNotForUnavailableResults() {
        var jdbc = mock(JdbcTemplate.class);
        var adapter = mock(MarketDataAdapter.class);
        doReturn(List.of(CONNECTION)).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        var info = new MarketDataAdapter.StockInfo("LUNR", "ACTIVE", "STOCK", true, null, null,
                BigDecimal.valueOf(1000), "USD");
        when(adapter.getStockInfo(any(BrokerConnectionRef.class), anyString())).thenReturn(
                new BrokerResponse<>(info, com.jmj.trade.broker.BrokerOrderPort.localMetadata()));
        when(adapter.getStockWarnings(any(BrokerConnectionRef.class), anyString())).thenReturn(
                new BrokerResponse<>(List.of(), com.jmj.trade.broker.BrokerOrderPort.localMetadata()));
        var service = service(jdbc, adapter);
        ReflectionTestUtils.setField(service, "fundamentalSourcePolicy", "TOSS_ONLY");

        var first = service.cachedTossStockHazards(USER, "LUNR", NOW);
        var second = service.cachedTossStockHazards(USER, "LUNR", NOW.plusSeconds(120));
        var afterTtl = service.cachedTossStockHazards(USER, "LUNR", NOW.plusSeconds(301));

        assertThat(first.status()).isEqualTo("CLEAR");
        assertThat(second.status()).isEqualTo("CLEAR");
        assertThat(afterTtl.status()).isEqualTo("CLEAR");
        verify(adapter, times(2)).getStockInfo(any(BrokerConnectionRef.class), anyString());
    }

    @Test
    void unavailableHazardsAreNotCachedSoTheViewRetriesLive() {
        var jdbc = mock(JdbcTemplate.class);
        var adapter = mock(MarketDataAdapter.class);
        doReturn(List.of(CONNECTION)).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(adapter.getStockInfo(any(BrokerConnectionRef.class), anyString()))
                .thenThrow(new IllegalStateException("toss down"));
        var service = service(jdbc, adapter);

        var first = service.cachedTossStockHazards(USER, "LUNR", NOW);
        var second = service.cachedTossStockHazards(USER, "LUNR", NOW.plusSeconds(10));

        assertThat(first.status()).isEqualTo("UNAVAILABLE");
        assertThat(second.status()).isEqualTo("UNAVAILABLE");
        verify(adapter, times(2)).getStockInfo(any(BrokerConnectionRef.class), anyString());
    }

    private static InvestmentBuyRiskGate.Decision decision(List<String> reasons,
                                                          List<InvestmentBuyRiskGate.LegacyProviderData> legacy) {
        var verification = new InvestmentBuyRiskGate.FundamentalVerification("UNAVAILABLE", reasons,
                "DATA_MISSING", null, null, "DATA_MISSING", "ALPHA_VANTAGE", null, legacy);
        return new InvestmentBuyRiskGate.Decision("REVIEW_REQUIRED", reasons, "STRATEGIC", "OK", "INTACT", "CLEAR",
                verification, NOW, "EXTERNAL_CITED");
    }

    private static InvestmentContextService service(JdbcTemplate jdbc, MarketDataAdapter adapter) {
        var service = new InvestmentContextService(jdbc, new ObjectMapper(),
                mock(PlatformTransactionManager.class), mock(StockDataProviderRegistry.class), null,
                mock(PortfolioReadService.class), mock(MonitoringWatchlistService.class),
                mock(RiskPolicyService.class), Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofDays(210),
                Duration.ofDays(10), Clock.fixed(NOW, ZoneOffset.UTC));
        service.setStockReferenceAdapter(adapter);
        return service;
    }
}
