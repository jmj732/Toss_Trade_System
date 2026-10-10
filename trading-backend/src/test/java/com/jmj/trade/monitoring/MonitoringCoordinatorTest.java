package com.jmj.trade.monitoring;

import com.jmj.trade.account.AccountSyncService;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.intelligence.EventIntelligenceService;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.risk.RiskPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonitoringCoordinatorTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void unchangedSourceStampedSnapshotCallsEvaluatorOnlyOnceAcrossSchedulerCycles() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var objectMapper = mock(ObjectMapper.class);
        var brokers = mock(ObjectProvider.class);
        var syncServices = mock(ObjectProvider.class);
        var fred = mock(MonitoringFredSeriesReader.class);
        var events = mock(MonitoringEventReader.class);
        var marketSeries = mock(MonitoringMarketSeriesStore.class);
        var portfolios = mock(MonitoringPortfolioReader.class);
        var watchlist = mock(MonitoringWatchlistService.class);
        var evaluator = mock(MonitoringEvaluator.class);
        var persister = mock(MonitoringEvaluationPersister.class);
        var thesisTriggers = mock(MonitoringThesisTriggerDetector.class);
        var emptyPortfolio = new MonitoringEvaluationContract.PortfolioInput(null, List.of(),
                new MonitoringEvaluationContract.RiskPolicyInput(new BigDecimal("0.25"), null, null));
        var batch = new MonitoringEvaluationContract.EventBatch(List.of(), List.of(), List.of());
        var response = mock(JsonNode.class);

        doReturn(List.of(USER_ID)).when(jdbc).query(contains("SELECT user_id FROM broker_connections"),
                any(RowMapper.class));
        when(fred.load(USER_ID)).thenReturn(List.of());
        when(marketSeries.loadRatios(USER_ID)).thenReturn(List.of());
        when(portfolios.read(eq(USER_ID), any(Instant.class), eq(null))).thenReturn(emptyPortfolio);
        when(watchlist.list(USER_ID)).thenReturn(List.of());
        when(events.load(eq(USER_ID), any(), any(Instant.class))).thenReturn(batch);
        when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[]{1, 2, 3});
        when(evaluator.evaluate(any())).thenReturn(response);

        var hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(new byte[]{1, 2, 3}));
        when(jdbc.query(contains("SELECT request_hash"), any(RowMapper.class), eq(USER_ID)))
                .thenReturn(List.of()).thenReturn(List.of(hash));

        var coordinator = new MonitoringCoordinator(jdbc, objectMapper, brokers, syncServices,
                fred, events, marketSeries, portfolios, watchlist, evaluator, persister, thesisTriggers,
                Duration.ofMinutes(10));

        coordinator.runCycle();
        coordinator.runCycle();

        verify(evaluator).evaluate(any());
        verify(persister).persist(eq(USER_ID), any(), eq(response));
        // Thesis rows and the stored price are outside the fingerprint, so review detection runs every cycle.
        verify(thesisTriggers, times(2)).detect(USER_ID, emptyPortfolio);
    }

    @Test
    void thesisTriggerReviewRunsEvenWhenTheEvaluatorFails() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var objectMapper = mock(ObjectMapper.class);
        var fred = mock(MonitoringFredSeriesReader.class);
        var events = mock(MonitoringEventReader.class);
        var marketSeries = mock(MonitoringMarketSeriesStore.class);
        var portfolios = mock(MonitoringPortfolioReader.class);
        var watchlist = mock(MonitoringWatchlistService.class);
        var evaluator = mock(MonitoringEvaluator.class);
        var persister = mock(MonitoringEvaluationPersister.class);
        var thesisTriggers = mock(MonitoringThesisTriggerDetector.class);
        var portfolio = new MonitoringEvaluationContract.PortfolioInput(Instant.now(), List.of(),
                new MonitoringEvaluationContract.RiskPolicyInput(new BigDecimal("0.25"), null, null));

        doReturn(List.of(USER_ID)).when(jdbc).query(contains("SELECT user_id FROM broker_connections"),
                any(RowMapper.class));
        when(fred.load(USER_ID)).thenReturn(List.of());
        when(portfolios.read(eq(USER_ID), any(Instant.class), eq(null))).thenReturn(portfolio);
        when(watchlist.list(USER_ID)).thenReturn(List.of());
        when(events.load(eq(USER_ID), any(), any(Instant.class)))
                .thenReturn(new MonitoringEvaluationContract.EventBatch(List.of(), List.of(), List.of()));
        when(objectMapper.writeValueAsBytes(any())).thenReturn(new byte[]{4});
        when(evaluator.evaluate(any())).thenThrow(new IllegalStateException("evaluator unavailable"));

        new MonitoringCoordinator(jdbc, objectMapper, mock(ObjectProvider.class), mock(ObjectProvider.class),
                fred, events, marketSeries, portfolios, watchlist, evaluator, persister, thesisTriggers,
                Duration.ofMinutes(10)).runCycle();

        verify(thesisTriggers).detect(USER_ID, portfolio);
        verify(persister, never()).persist(any(), any(), any());
    }
}
