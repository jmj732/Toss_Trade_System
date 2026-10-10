package com.jmj.trade.monitoring;

import com.jmj.trade.account.AccountSyncService;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.MarketDataAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Builds a source-stamped snapshot and runs one deterministic internal evaluation per changed input. */
@Service
class MonitoringCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(MonitoringCoordinator.class);
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<BrokerAdapter> brokers;
    private final ObjectProvider<AccountSyncService> accountSyncServices;
    private final MonitoringFredSeriesReader fred;
    private final MonitoringEventReader events;
    private final MonitoringMarketSeriesStore marketSeries;
    private final MonitoringPortfolioReader portfolios;
    private final MonitoringWatchlistService watchlist;
    private final MonitoringEvaluator evaluator;
    private final MonitoringEvaluationPersister persister;
    private final MonitoringThesisTriggerDetector thesisTriggers;
    private final Duration priceInterval;
    private final Clock clock = Clock.systemUTC();

    MonitoringCoordinator(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ObjectProvider<BrokerAdapter> brokers,
            ObjectProvider<AccountSyncService> accountSyncServices,
            MonitoringFredSeriesReader fred,
            MonitoringEventReader events,
            MonitoringMarketSeriesStore marketSeries,
            MonitoringPortfolioReader portfolios,
            MonitoringWatchlistService watchlist,
            MonitoringEvaluator evaluator,
            MonitoringEvaluationPersister persister,
            MonitoringThesisTriggerDetector thesisTriggers,
            @Value("${monitoring.price-interval:PT10M}") Duration priceInterval
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.brokers = Objects.requireNonNull(brokers, "brokers");
        this.accountSyncServices = Objects.requireNonNull(accountSyncServices, "accountSyncServices");
        this.fred = Objects.requireNonNull(fred, "fred");
        this.events = Objects.requireNonNull(events, "events");
        this.marketSeries = Objects.requireNonNull(marketSeries, "marketSeries");
        this.portfolios = Objects.requireNonNull(portfolios, "portfolios");
        this.watchlist = Objects.requireNonNull(watchlist, "watchlist");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.persister = Objects.requireNonNull(persister, "persister");
        this.thesisTriggers = Objects.requireNonNull(thesisTriggers, "thesisTriggers");
        this.priceInterval = positive(priceInterval);
    }

    void runCycle() {
        var now = clock.instant();
        var broker = brokers.getIfAvailable();
        var marketData = broker instanceof MarketDataAdapter adapter ? adapter : null;
        var allUsers = users();
        for (var userId : allUsers) {
            try {
                evaluateUser(userId, now, broker, marketData);
            } catch (RuntimeException exception) {
                LOG.atWarn()
                        .addKeyValue("operation", "monitoring_evaluation")
                        .addKeyValue("user_id", userId)
                        .addKeyValue("error_type", exception.getClass().getSimpleName())
                        .log("monitoring evaluation did not complete");
            }
        }
    }

    private void evaluateUser(UUID userId, Instant now, BrokerAdapter broker, MarketDataAdapter marketData) {
        var connectionIds = connections(userId);
        var sessionOpen = marketSessionOpen(now, connectionIds, marketData);
        if (sessionOpen) refreshPortfolio(userId, connectionIds, now);

        var series = new ArrayList<>(fred.load(userId));
        if (sessionOpen && broker != null && marketData != null && !connectionIds.isEmpty()) {
            var quoteAdapter = new MonitoringQuoteAdapter(broker, marketData,
                    new BrokerConnectionRef(connectionIds.getFirst()), clock);
            marketSeries.saveRatios(userId, quoteAdapter.marketRatios());
        }
        series.addAll(marketSeries.loadRatios(userId, now));
        var fx = latestFx(series, now);
        var portfolio = portfolios.read(userId, now, fx);
        detectThesisTriggers(userId, portfolio);
        var watchEntries = watchlist.list(userId);
        var symbols = new LinkedHashSet<String>();
        portfolio.positions().stream().map(MonitoringEvaluationContract.PositionInput::symbol)
                .filter(Objects::nonNull).forEach(symbols::add);
        watchEntries.stream().map(MonitoringWatchlistService.WatchlistEntry::symbol).forEach(symbols::add);
        var eventBatch = events.load(userId, symbols, now);
        var watchInputs = new ArrayList<MonitoringEvaluationContract.WatchlistInput>();
        if (sessionOpen && broker != null && marketData != null && !connectionIds.isEmpty()) {
            var quoteAdapter = new MonitoringQuoteAdapter(broker, marketData,
                    new BrokerConnectionRef(connectionIds.getFirst()), clock);
            for (var entry : watchEntries) {
                var snapshot = quoteAdapter.snapshot(entry.symbol());
                watchInputs.add(new MonitoringEvaluationContract.WatchlistInput(
                        entry.symbol(), entry.status(), contractLevels(entry.levels()), snapshot.price(),
                        snapshot.volumeMultiple(), snapshot.relativeStrength(), snapshot.asOf(),
                        snapshot.source(), snapshot.collectedAt()));
            }
        } else {
            for (var entry : watchEntries) {
                watchInputs.add(new MonitoringEvaluationContract.WatchlistInput(
                        entry.symbol(), entry.status(), contractLevels(entry.levels()), null, null,
                        null, null, null, null));
            }
        }
        var request = new MonitoringEvaluationContract.Request(
                UUID.randomUUID(), "1", now,
                new MonitoringEvaluationContract.MarketInput(List.copyOf(series), eventBatch.shocks(),
                        eventBatch.incidents()),
                portfolio, eventBatch.events(), List.copyOf(watchInputs));
        var hash = fingerprint(request);
        if (alreadyEvaluated(userId, hash)) return;
        var result = evaluator.evaluate(request);
        persister.persist(userId, request, result);
        recordEvaluation(userId, hash, now);
    }

    /**
     * Runs before the evaluation fingerprint short-circuit and the external evaluator call: thesis rows and the
     * stored investment price are not part of the fingerprint, and an evaluator outage must not hide a review
     * prompt. Failures are isolated so the risk evaluation still runs.
     */
    private void detectThesisTriggers(UUID userId, MonitoringEvaluationContract.PortfolioInput portfolio) {
        try {
            thesisTriggers.detect(userId, portfolio);
        } catch (RuntimeException exception) {
            LOG.atWarn()
                    .addKeyValue("operation", "monitoring_thesis_trigger_review")
                    .addKeyValue("user_id", userId)
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("thesis trigger review detection did not complete");
        }
    }

    private void refreshPortfolio(UUID userId, List<UUID> connectionIds, Instant now) {
        var sync = accountSyncServices.getIfAvailable();
        if (sync == null) return;
        for (var connectionId : connectionIds) {
            var latest = lastSuccessfulSync(userId, connectionId);
            if (latest != null && !latest.isBefore(now.minus(priceInterval))) continue;
            try {
                sync.syncForMonitoring(userId, connectionId);
            } catch (RuntimeException exception) {
                LOG.atInfo()
                        .addKeyValue("operation", "monitoring_portfolio_refresh")
                        .addKeyValue("connection_id", connectionId)
                        .addKeyValue("error_type", exception.getClass().getSimpleName())
                        .log("read-only portfolio refresh failed; previous snapshot remains subject to freshness checks");
            }
        }
    }

    private boolean marketSessionOpen(Instant now, List<UUID> connectionIds, MarketDataAdapter marketData) {
        if (!MonitoringMarketSession.isPotentialRegularSession(now) || connectionIds.isEmpty()
                || marketData == null) return false;
        try {
            var response = marketData.getMarketCalendar(new BrokerConnectionRef(connectionIds.getFirst()), "US",
                    LocalDate.ofInstant(now, NEW_YORK));
            return response != null && response.value() != null && response.value().payload() != null
                    && MonitoringMarketSession.isOpen(now, response.value().payload(), response.metadata().observedAt());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private List<UUID> users() {
        return jdbc.query("""
                SELECT user_id FROM broker_connections
                 WHERE status = 'ACTIVE' AND deleted_at IS NULL
                UNION
                SELECT user_id FROM monitoring_watchlist
                ORDER BY user_id
                """, (resultSet, rowNum) -> resultSet.getObject(1, UUID.class));
    }

    private List<UUID> connections(UUID userId) {
        return jdbc.query("""
                SELECT id FROM broker_connections
                 WHERE user_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                 ORDER BY id
                """, (resultSet, rowNum) -> resultSet.getObject(1, UUID.class), userId);
    }

    private Instant lastSuccessfulSync(UUID userId, UUID connectionId) {
        return jdbc.query("""
                SELECT MAX(completed_at) FROM account_sync_runs
                 WHERE user_id = ? AND broker_connection_id = ? AND status = 'SUCCEEDED'
                """, (resultSet, rowNum) -> {
            var value = resultSet.getObject(1, OffsetDateTime.class);
            return value == null ? null : value.toInstant();
        }, userId, connectionId).stream().findFirst().orElse(null);
    }

    private boolean alreadyEvaluated(UUID userId, String requestHash) {
        return jdbc.query("""
                SELECT request_hash FROM monitoring_evaluation_cursors WHERE user_id = ?
                """, (resultSet, rowNum) -> resultSet.getString(1), userId)
                .stream().anyMatch(requestHash::equals);
    }

    private void recordEvaluation(UUID userId, String requestHash, Instant evaluatedAt) {
        jdbc.update("""
                INSERT INTO monitoring_evaluation_cursors (user_id, request_hash, evaluated_at)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    request_hash = EXCLUDED.request_hash,
                    evaluated_at = EXCLUDED.evaluated_at
                """, userId, requestHash, OffsetDateTime.ofInstant(evaluatedAt, ZoneOffset.UTC));
    }

    private String fingerprint(MonitoringEvaluationContract.Request request) {
        try {
            var body = objectMapper.writeValueAsBytes(new EvaluationFingerprint(
                    request.market(), request.portfolio(), request.events(), request.watchlist()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (JacksonException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("monitoring input fingerprint failed", exception);
        }
    }

    private static BigDecimal latestFx(List<MonitoringEvaluationContract.MetricSeries> series, Instant now) {
        return series.stream().filter(value -> "fx.usd_krw".equals(value.metric()))
                .flatMap(value -> value.points().stream())
                .filter(point -> point.value() != null && !point.asOf().isAfter(now)
                        && !point.asOf().isBefore(now.minus(Duration.ofDays(4))))
                .max(java.util.Comparator.comparing(MonitoringEvaluationContract.MetricPoint::asOf))
                .map(point -> {
                    try {
                        var value = new BigDecimal(point.value());
                        return value.signum() > 0 ? value : null;
                    } catch (NumberFormatException exception) {
                        return null;
                    }
                }).orElse(null);
    }

    private static MonitoringEvaluationContract.WatchlistLevels contractLevels(
            MonitoringWatchlistService.WatchlistLevels levels) {
        return new MonitoringEvaluationContract.WatchlistLevels(
                levels.prepare(), levels.confirm(), levels.pullback(), levels.invalidate());
    }

    private static Duration positive(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("monitoring price interval must be positive");
        }
        return value;
    }

    private record EvaluationFingerprint(
            MonitoringEvaluationContract.MarketInput market,
            MonitoringEvaluationContract.PortfolioInput portfolio,
            List<MonitoringEvaluationContract.EventInput> events,
            List<MonitoringEvaluationContract.WatchlistInput> watchlist
    ) {
    }
}
