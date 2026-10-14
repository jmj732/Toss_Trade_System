package com.jmj.trade.investment;

import com.jmj.trade.account.AccountSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;

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
    void legacyCalendarEvidenceBootstrapUsesQuoteOnlyCaptureAndStopsAfterTheMarkerAppears() {
        var investment = mock(InvestmentContextService.class);
        when(investment.needsInitialCapture()).thenReturn(false);
        when(investment.needsCalendarEvidenceBootstrap()).thenReturn(true, false);
        var scheduler = scheduler(investment);

        scheduler.intraday();
        scheduler.intraday();

        verify(investment, times(2)).needsCalendarEvidenceBootstrap();
        verify(investment).captureAllQuoteUpdates();
        verify(investment, never()).captureAll();
    }

    @Test
    void failedCalendarEvidenceCaptureRetriesAtMostThreeTimes() {
        var investment = mock(InvestmentContextService.class);
        when(investment.needsInitialCapture()).thenReturn(false);
        when(investment.needsCalendarEvidenceBootstrap()).thenReturn(true, true, true);
        doThrow(new IllegalStateException("provider unavailable"))
                .when(investment).captureAllQuoteUpdates();
        var scheduler = scheduler(investment);

        scheduler.intraday();
        scheduler.intraday();
        scheduler.intraday();
        scheduler.intraday();

        verify(investment, times(3)).needsCalendarEvidenceBootstrap();
        verify(investment, times(3)).captureAllQuoteUpdates();
        verify(investment, never()).captureAll();
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

    @Test
    void closedIntervalEvidenceIsCapturedAfterDeclaredEveningSessionsUsingQuoteOnlyData()
            throws NoSuchMethodException {
        var schedule = InvestmentDataScheduler.class.getDeclaredMethod("afterClosedInterval")
                .getAnnotation(Scheduled.class);
        assertThat(schedule.cron()).isEqualTo(
                "${investment.data.closed-interval-evidence-cron:0 15 20 * * MON-FRI}");
        assertThat(schedule.zone()).isEqualTo("${investment.data.time-zone:America/New_York}");

        var investment = mock(InvestmentContextService.class);
        scheduler(investment).afterClosedInterval();

        verify(investment).captureAllQuoteUpdates();
        verify(investment, never()).captureAll();
    }

    @Test
    void preMarketQuoteCaptureRunsHourlyWithoutRefreshingTheBrokerPortfolio() throws Exception {
        var cron = "${investment.data.pre-market-capture-cron:0 0 4-9 * * MON-FRI}";
        Method capture = Arrays.stream(InvestmentDataScheduler.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Scheduled.class))
                .filter(method -> cron.equals(method.getAnnotation(Scheduled.class).cron()))
                .findFirst()
                .orElse(null);
        assertThat(capture).as("hourly quote-only pre-market capture schedule").isNotNull();

        var investment = mock(InvestmentContextService.class);
        var accountSync = mock(ObjectProvider.class);
        var scheduler = new InvestmentDataScheduler(mock(JdbcTemplate.class), investment,
                accountSync, "America/New_York", BEFORE_MARKET);
        capture.invoke(scheduler);

        verify(investment).captureAllQuoteUpdates();
        verify(investment, never()).captureAll();
        verify(accountSync, never()).getIfAvailable();
    }

    @Test
    void eightAmPreMarketRefreshKeepsPortfolioSyncSeparateFromHourlyQuoteCapture()
            throws NoSuchMethodException {
        var schedule = InvestmentDataScheduler.class.getDeclaredMethod("beforeMarket")
                .getAnnotation(Scheduled.class);
        assertThat(schedule.cron()).isEqualTo(
                "${investment.data.pre-market-cron:0 0 8 * * MON-FRI}");
        assertThat(schedule.zone()).isEqualTo("${investment.data.time-zone:America/New_York}");

        var investment = mock(InvestmentContextService.class);
        var accountSync = mock(ObjectProvider.class);
        var scheduler = new InvestmentDataScheduler(mock(JdbcTemplate.class), investment,
                accountSync, "America/New_York", BEFORE_MARKET);

        scheduler.beforeMarket();

        verify(accountSync).getIfAvailable();
        verify(investment, never()).captureAllQuoteUpdates();
        verify(investment, never()).captureAll();
    }

    private static InvestmentDataScheduler scheduler(InvestmentContextService investment) {
        return scheduler(investment, BEFORE_MARKET);
    }

    private static InvestmentDataScheduler scheduler(InvestmentContextService investment, Clock clock) {
        return new InvestmentDataScheduler(mock(JdbcTemplate.class), investment,
                mock(ObjectProvider.class), "America/New_York", clock);
    }
}
