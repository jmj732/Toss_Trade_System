package com.jmj.trade.investment;

import com.jmj.trade.account.AccountSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class InvestmentDataScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(InvestmentDataScheduler.class);
    // Initial attempt plus two retries, spaced by the existing intraday fixed delay (default PT5M).
    private static final int INITIAL_CAPTURE_ATTEMPT_LIMIT = 3;
    private final JdbcTemplate jdbc;
    private final InvestmentContextService investment;
    private final ObjectProvider<AccountSyncService> accountSync;
    private final ZoneId zone;
    private final Clock clock;
    private final AtomicBoolean initialCaptureComplete = new AtomicBoolean();
    private final AtomicBoolean initialCaptureExhausted = new AtomicBoolean();
    private final AtomicBoolean initialCaptureInProgress = new AtomicBoolean();
    private final AtomicInteger initialCaptureAttempts = new AtomicInteger();

    InvestmentDataScheduler(
            JdbcTemplate jdbc,
            InvestmentContextService investment,
            ObjectProvider<AccountSyncService> accountSync,
            String zone
    ) {
        this(jdbc, investment, accountSync, zone, Clock.systemUTC());
    }

    InvestmentDataScheduler(
            JdbcTemplate jdbc,
            InvestmentContextService investment,
            ObjectProvider<AccountSyncService> accountSync,
            String zone,
            Clock clock
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.investment = Objects.requireNonNull(investment, "investment");
        this.accountSync = Objects.requireNonNull(accountSync, "accountSync");
        this.zone = ZoneId.of(zone);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Scheduled(cron = "${investment.data.after-close-cron:0 15 16 * * MON-FRI}",
            zone = "${investment.data.time-zone:America/New_York}")
    void afterClose() {
        capture("after_close", false);
    }

    @Scheduled(cron = "${investment.data.weekend-capture-cron:0 15 16 * * SAT,SUN}",
            zone = "${investment.data.time-zone:America/New_York}")
    void weekendAfterClose() {
        capture("weekend_after_close", false);
    }

    @Scheduled(cron = "${investment.data.pre-market-cron:0 0 8 * * MON-FRI}",
            zone = "${investment.data.time-zone:America/New_York}")
    void beforeMarket() {
        refreshPortfolios();
        capture("pre_market", true);
    }

    private void refreshPortfolios() {
        var sync = accountSync.getIfAvailable();
        if (sync != null) {
            jdbc.query("""
                    SELECT user_id, id FROM broker_connections
                     WHERE status = 'ACTIVE' AND deleted_at IS NULL
                     ORDER BY user_id, id
                    """, (resultSet, rowNum) -> new Connection(
                    resultSet.getObject("user_id", UUID.class), resultSet.getObject("id", UUID.class)))
                    .forEach(connection -> {
                        try {
                            sync.syncForMonitoring(connection.userId(), connection.connectionId());
                        } catch (RuntimeException exception) {
                            LOG.atWarn().addKeyValue("operation", "investment_pre_market_portfolio_refresh")
                                    .addKeyValue("user_id", connection.userId())
                                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                                    .log("scheduled portfolio refresh failed");
                        }
                    });
        }
    }

    @Scheduled(fixedDelayString = "${investment.data.intraday-interval:PT5M}",
            initialDelayString = "${investment.data.initial-delay:PT1M}")
    void intraday() {
        if (!initialCaptureComplete.get() && !initialCaptureExhausted.get()) {
            if (!initialCaptureInProgress.compareAndSet(false, true)) return;
            try {
                refreshPortfolios();
                if (investment.needsInitialCapture()) {
                    var attempt = initialCaptureAttempts.incrementAndGet();
                    var captured = capture("initial_bootstrap", false);
                    if (!captured || investment.needsInitialCapture()) {
                        if (attempt >= INITIAL_CAPTURE_ATTEMPT_LIMIT) {
                            initialCaptureExhausted.set(true);
                            LOG.atWarn().addKeyValue("operation", "investment_data_initial_bootstrap")
                                    .addKeyValue("attempts", attempt)
                                    .log("bootstrap retry limit reached; after-close capture remains scheduled");
                        } else {
                            return;
                        }
                    } else {
                        initialCaptureComplete.set(true);
                    }
                    return;
                } else {
                    initialCaptureComplete.set(true);
                }
            } finally {
                initialCaptureInProgress.set(false);
            }
        }
        var now = clock.instant().atZone(zone);
        var time = now.toLocalTime();
        if (now.getDayOfWeek().getValue() > 5 || time.isBefore(LocalTime.of(9, 30))
                || !time.isBefore(LocalTime.of(16, 0))) return;
        // ponytail: weekday session window ignores exchange holidays; provider freshness still gates published status.
        capture("intraday", true);
    }

    private boolean capture(String run, boolean quoteOnly) {
        try {
            var captured = quoteOnly ? investment.captureAllQuoteUpdates() : investment.captureAll();
            LOG.atInfo().addKeyValue("operation", "investment_data_" + run)
                    .addKeyValue("securities_captured", captured).log("investment data capture completed");
            return true;
        } catch (RuntimeException exception) {
            LOG.atWarn().addKeyValue("operation", "investment_data_" + run)
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("investment data capture failed");
            return false;
        }
    }

    private record Connection(UUID userId, UUID connectionId) {
    }
}
