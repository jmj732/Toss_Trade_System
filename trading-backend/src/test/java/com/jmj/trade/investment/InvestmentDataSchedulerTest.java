package com.jmj.trade.investment;

import com.jmj.trade.account.AccountSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class InvestmentDataSchedulerTest {

    private static final Clock BEFORE_MARKET = Clock.fixed(
            Instant.parse("2026-10-01T12:00:00Z"), ZoneId.of("UTC"));

    @Test
    void successfulBootstrapRunsBeforeMarketWindowOnlyOnceAfterCanonicalDataAppears() {
        var investment = mock(InvestmentContextService.class);
        when(investment.needsInitialCapture()).thenReturn(true, false);
        var scheduler = scheduler(investment);

        scheduler.intraday();
        scheduler.intraday();

        verify(investment, times(2)).needsInitialCapture();
        verify(investment).captureAll();
        verify(investment, never()).captureAllQuoteUpdates();
    }

    @Test
    void successfulBootstrapUsesOneCaptureOnBootstrapTick() {
        var investment = mock(InvestmentContextService.class);
        when(investment.needsInitialCapture()).thenReturn(true, false);
        var scheduler = scheduler(investment,
                Clock.fixed(Instant.parse("2026-10-01T15:00:00Z"), ZoneId.of("UTC")));

        scheduler.intraday();
        scheduler.intraday();

        verify(investment).captureAll();
        verify(investment).captureAllQuoteUpdates();
    }

    @Test
    void failedBootstrapCaptureRetriesOnNextScheduledTick() {
        var investment = mock(InvestmentContextService.class);
        when(investment.needsInitialCapture()).thenReturn(true, true, false);
        doThrow(new IllegalStateException("provider unavailable"))
                .doReturn(0).when(investment).captureAll();
        var scheduler = scheduler(investment);

        scheduler.intraday();
        scheduler.intraday();
        scheduler.intraday();

        verify(investment, times(3)).needsInitialCapture();
        verify(investment, times(2)).captureAll();
        verify(investment, never()).captureAllQuoteUpdates();
    }

    @Test
    void bootstrapStopsAfterThreeSuccessfulCapturesWithoutRequiredCanonicalData() {
        var investment = mock(InvestmentContextService.class);
        when(investment.needsInitialCapture()).thenReturn(true, true, true, true, true, true);
        doReturn(0).when(investment).captureAll();
        var scheduler = scheduler(investment);

        scheduler.intraday();
        scheduler.intraday();
        scheduler.intraday();
        scheduler.intraday();

        verify(investment, times(6)).needsInitialCapture();
        verify(investment, times(3)).captureAll();
        verify(investment, never()).captureAllQuoteUpdates();
    }

    @Test
    void firstScheduledTickSkipsBootstrapWhenCanonicalDataAlreadyExists() {
        var investment = mock(InvestmentContextService.class);
        when(investment.needsInitialCapture()).thenReturn(false);
        var scheduler = scheduler(investment);

        scheduler.intraday();
        scheduler.intraday();

        verify(investment).needsInitialCapture();
        verify(investment, never()).captureAll();
        verify(investment, never()).captureAllQuoteUpdates();
    }

    @Test
    void canonicalConsensusCaptureRunsOnWeekdaysAndWeekends() throws NoSuchMethodException {
        var weekday = InvestmentDataScheduler.class.getDeclaredMethod("afterClose")
                .getAnnotation(Scheduled.class);
        var weekend = InvestmentDataScheduler.class.getDeclaredMethod("weekendAfterClose")
                .getAnnotation(Scheduled.class);

        assertThat(weekday.cron()).isEqualTo(
                "${investment.data.after-close-cron:0 15 16 * * MON-FRI}");
        assertThat(weekend.cron()).isEqualTo(
                "${investment.data.weekend-capture-cron:0 15 16 * * SAT,SUN}");
        assertThat(weekday.zone()).isEqualTo("${investment.data.time-zone:America/New_York}");
        assertThat(weekend.zone()).isEqualTo(weekday.zone());
    }

    private static InvestmentDataScheduler scheduler(InvestmentContextService investment) {
        return scheduler(investment, BEFORE_MARKET);
    }

    private static InvestmentDataScheduler scheduler(InvestmentContextService investment, Clock clock) {
        return new InvestmentDataScheduler(mock(JdbcTemplate.class), investment,
                mock(ObjectProvider.class), "America/New_York", clock);
    }
}
