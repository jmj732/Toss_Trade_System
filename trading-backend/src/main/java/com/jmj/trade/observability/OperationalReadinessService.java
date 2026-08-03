package com.jmj.trade.observability;

import com.jmj.trade.marketdata.ProviderCatalog;
import com.jmj.trade.marketdata.StockAnalysisInput;
import com.jmj.trade.marketdata.StockAnalysisInputAssembler;
import com.jmj.trade.marketdata.StockAnalysisProviderProperties;
import com.jmj.trade.marketdata.StockDataProviderId;
import com.jmj.trade.marketdata.StockDataProviderRegistry;
import com.jmj.trade.notification.NotificationEventType;
import com.jmj.trade.notification.NotificationOutboxWriter;
import com.jmj.trade.order.KillSwitchStateReader;
import com.jmj.trade.order.RealOrderCanaryProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public final class OperationalReadinessService {

    private static final List<String> SCHEDULER_KEYS = List.of(
            "market-events.scheduler.enabled",
            "portfolio.refresh.enabled",
            "notification.outbox.enabled",
            "order.outbox.enabled",
            "prediction.evaluation.enabled");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final StockAnalysisProviderProperties providerProperties;
    private final StockDataProviderRegistry providers;
    private final RealOrderCanaryProperties canary;
    private final ObjectProvider<KillSwitchStateReader> killSwitches;
    private final NotificationOutboxWriter notifications;
    private final Environment environment;
    private final Duration maxDataAge;
    private final Clock clock;

    @Autowired
    public OperationalReadinessService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            StockAnalysisProviderProperties providerProperties,
            StockDataProviderRegistry providers,
            RealOrderCanaryProperties canary,
            ObjectProvider<KillSwitchStateReader> killSwitches,
            NotificationOutboxWriter notifications,
            Environment environment,
            @Value("${production.readiness.max-data-age:PT15M}") Duration maxDataAge
    ) {
        this(jdbc, objectMapper, providerProperties, providers, canary, killSwitches,
                notifications, environment, maxDataAge, Clock.systemUTC());
    }

    OperationalReadinessService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            StockAnalysisProviderProperties providerProperties,
            StockDataProviderRegistry providers,
            RealOrderCanaryProperties canary,
            ObjectProvider<KillSwitchStateReader> killSwitches,
            NotificationOutboxWriter notifications,
            Environment environment,
            Duration maxDataAge,
            Clock clock
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.providerProperties = Objects.requireNonNull(providerProperties, "providerProperties");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.canary = Objects.requireNonNull(canary, "canary");
        this.killSwitches = Objects.requireNonNull(killSwitches, "killSwitches");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.environment = Objects.requireNonNull(environment, "environment");
        if (maxDataAge == null || !maxDataAge.isPositive()) {
            throw new IllegalArgumentException("maxDataAge must be positive");
        }
        this.maxDataAge = maxDataAge;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ReadinessView read(UUID userId) {
        requireId(userId);
        var evidence = latestEvidence(userId);
        var providerViews = providerViews(evidence);
        var canaryView = canaryView(userId);
        var killSwitch = killSwitchView(userId, canaryView);
        var alerts = alerts(providerViews, canaryView, killSwitch, evidence.isEmpty());
        return view(providerViews, canaryView, killSwitch, alerts,
                evidence.isEmpty() ? null : evidence.get(0).id(),
                evidence.isEmpty() ? null : evidence.get(0).createdAt());
    }

    public ReadinessView checkProviders(UUID userId, String symbol, String actor) {
        requireId(userId);
        var cleanSymbol = requireSymbol(symbol);
        requireText(actor);
        var now = clock.instant();
        var evidenceId = UUID.randomUUID();
        var evidence = new ArrayList<ProviderEvidence>();
        var usable = providers.providers().stream()
                .filter(provider -> providerConfiguration(provider.id()).enabled())
                .filter(provider -> credentialConfigured(provider.id(), providerConfiguration(provider.id())))
                .toList();

        StockAnalysisInput input = null;
        if (!usable.isEmpty()) {
            input = new StockAnalysisInputAssembler(
                    new StockDataProviderRegistry(usable), clock).assemble(cleanSymbol, Map.of());
        }
        for (var providerId : StockDataProviderId.values()) {
            var configuration = providerConfiguration(providerId);
            var configured = configured(configuration);
            var credential = configured && credentialConfigured(providerId, configuration);
            var observations = input == null ? List.<StockAnalysisInput.Observation>of()
                    : input.observations().stream()
                    .filter(item -> item.provider() == providerId).toList();
            var missing = observations.stream()
                    .flatMap(item -> item.missingData().stream())
                    .distinct().sorted().toList();
            var asOf = observations.stream()
                    .map(StockAnalysisInput.Observation::asOf)
                    .filter(Objects::nonNull)
                    .min(Instant::compareTo).orElse(null);
            var collectedAt = observations.stream()
                    .map(StockAnalysisInput.Observation::collectedAt)
                    .max(Instant::compareTo).orElse(now);
            var classification = classify(configuration.enabled(), credential, missing, asOf,
                    collectedAt, now, maxDataAge);
            var status = !configuration.enabled() ? "DISABLED"
                    : !configured ? "NOT_CONFIGURED"
                    : classification.status();
            evidence.add(new ProviderEvidence(
                    providerId.name(), status, classification.lagMs(),
                    missing, asOf, collectedAt));
        }
        var overall = overall(evidence);
        persist(evidenceId, userId, cleanSymbol, overall, evidence, now);
        if (!"HEALTHY".equals(overall)) {
            notifications.emit(userId, NotificationEventType.PRODUCTION_READINESS_ALERT,
                    evidenceId, Map.of("status", overall, "evidenceId", evidenceId.toString()), now);
        }
        return read(userId);
    }

    public static Classification classify(
            boolean configured,
            boolean credentialConfigured,
            List<String> missingData,
            Instant asOf,
            Instant collectedAt,
            Instant now,
            Duration maxAge
    ) {
        var missing = missingData == null ? List.<String>of() : List.copyOf(missingData);
        if (!configured) {
            return new Classification("NOT_CONFIGURED", false, null, missing);
        }
        if (!credentialConfigured) {
            return new Classification("SECRET_MISSING", false, null, missing);
        }
        if (missing.stream().anyMatch("PROVIDER_UNAVAILABLE"::equals)) {
            return new Classification("UNAVAILABLE", false, lag(asOf, now), missing);
        }
        if (!missing.isEmpty() || asOf == null || collectedAt == null) {
            return new Classification("DEGRADED", false, lag(asOf, now), missing);
        }
        var lag = lag(asOf, now);
        if (lag == null || lag > maxAge.toMillis()) {
            return new Classification("STALE", false, lag, missing);
        }
        return new Classification("HEALTHY", true, lag, missing);
    }

    public static String evidenceJson(List<ProviderEvidence> evidence) {
        try {
            return new ObjectMapper().writeValueAsString(evidence == null ? List.of() : evidence);
        } catch (JacksonException exception) {
            throw new IllegalStateException("readiness evidence serialization failed", exception);
        }
    }

    private ReadinessView view(
            List<ProviderView> providerViews,
            CanaryView canaryView,
            KillSwitchView killSwitch,
            List<String> alerts,
            UUID evidenceId,
            Instant evidenceCreatedAt
    ) {
        var maxLag = providerViews.stream().map(ProviderView::lagMs)
                .filter(Objects::nonNull).max(Long::compareTo).orElse(0L);
        var freshness = new FreshnessView(
                alerts.contains("PROVIDER_CHECK_NOT_RUN") ? "NOT_CHECKED"
                        : maxLag > maxDataAge.toMillis() ? "STALE" : alerts.isEmpty() ? "HEALTHY" : "DEGRADED",
                maxLag, maxDataAge.toMillis(), evidenceCreatedAt);
        var status = alerts.stream().anyMatch(item -> item.startsWith("KILL_SWITCH")
                || item.contains("CREDENTIAL_LOOKUP_FAILED")) ? "BLOCKED"
                : providerViews.stream().anyMatch(item -> "SECRET_MISSING".equals(item.status()))
                ? "SECRET_MISSING" : alerts.isEmpty() ? "HEALTHY" : "DEGRADED";
        return new ReadinessView(status, clock.instant(), canaryView,
                schedulers(), killSwitch, providerViews, freshness, alerts, evidenceId);
    }

    private List<ProviderView> providerViews(List<ReadinessRow> latest) {
        var byId = new java.util.HashMap<String, ProviderEvidence>();
        latest.stream().findFirst().ifPresent(row -> row.evidence().forEach(item -> byId.put(item.provider(), item)));
        var views = new ArrayList<ProviderView>();
        for (var providerId : StockDataProviderId.values()) {
            var configuration = providerConfiguration(providerId);
            var configured = configured(configuration);
            var credential = configured && credentialConfigured(providerId, configuration);
            var item = byId.get(providerId.name());
            var status = item == null ? (!configuration.enabled() ? "DISABLED"
                    : !configured ? "NOT_CONFIGURED" : !credential ? "SECRET_MISSING" : "NOT_CHECKED")
                    : currentStatus(configuration, credential, item);
            var currentLag = item == null ? null : lag(item.asOf(), clock.instant());
            views.add(new ProviderView(providerId.name(), status, configuration.enabled(), configured,
                    credential, "HEALTHY".equals(status), currentLag,
                    item == null ? null : item.asOf(), item == null ? List.of() : item.missingData()));
        }
        return List.copyOf(views);
    }

    private String currentStatus(
            ProviderConfiguration configuration,
            boolean credential,
            ProviderEvidence evidence
    ) {
        if (!configuration.enabled()) return "DISABLED";
        if (!configured(configuration)) return "NOT_CONFIGURED";
        return classify(true, credential, evidence.missingData(), evidence.asOf(),
                evidence.collectedAt(), clock.instant(), maxDataAge).status();
    }

    private CanaryView canaryView(UUID userId) {
        var errors = new ArrayList<>(canary.validationErrors());
        var configured = canary.connectionId() != null && canary.brokerAccountId() != null;
        var credentialsPresent = configured && brokerCredentialPresent(userId);
        var allowlisted = configured && accountAllowlisted(userId);
        if (canary.enabled() && !Boolean.parseBoolean(environment.getProperty("real-order.enabled", "false"))) {
            errors.add("REAL_ORDER_DISABLED");
        }
        if (canary.enabled() && configured && !credentialsPresent) {
            errors.add("BROKER_CREDENTIAL_MISSING");
        }
        if (canary.enabled() && configured && !allowlisted) {
            errors.add("CANARY_ALLOWLIST_MISSING");
        }
        var status = !canary.enabled() ? "DISABLED" : errors.isEmpty() ? "READY" : "BLOCKED";
        return new CanaryView(canary.enabled(), configured, credentialsPresent, allowlisted,
                status, List.copyOf(new LinkedHashSet<>(errors)));
    }

    private KillSwitchView killSwitchView(UUID userId, CanaryView canaryView) {
        if (!canaryView.enabled() || canary.connectionId() == null) {
            return new KillSwitchView("NOT_REQUIRED", false);
        }
        var reader = killSwitches.getIfAvailable();
        if (reader == null) {
            return new KillSwitchView("UNAVAILABLE", true);
        }
        try {
            var engaged = reader.anyEngaged(userId, canary.connectionId());
            return new KillSwitchView(engaged ? "ENGAGED" : "CLEAR", engaged);
        } catch (RuntimeException exception) {
            return new KillSwitchView("UNAVAILABLE", true);
        }
    }

    private List<String> alerts(
            List<ProviderView> providerViews,
            CanaryView canaryView,
            KillSwitchView killSwitch,
            boolean noEvidence
    ) {
        var alerts = new ArrayList<String>();
        if (noEvidence) alerts.add("PROVIDER_CHECK_NOT_RUN");
        providerViews.stream().filter(item -> !SetOf.OK.contains(item.status()))
                .filter(item -> !"DISABLED".equals(item.status()))
                .forEach(item -> alerts.add("PROVIDER_" + item.provider() + "_" + item.status()));
        canaryView.blockers().stream().filter(item -> !"CANARY_DISABLED".equals(item)).forEach(alerts::add);
        if ("ENGAGED".equals(killSwitch.status())) alerts.add("KILL_SWITCH_ENGAGED");
        if ("UNAVAILABLE".equals(killSwitch.status())) alerts.add("KILL_SWITCH_UNAVAILABLE");
        return List.copyOf(alerts);
    }

    private List<SchedulerView> schedulers() {
        return SCHEDULER_KEYS.stream().map(key -> new SchedulerView(
                key, Boolean.parseBoolean(environment.getProperty(key, "false")),
                key.contains("portfolio") || key.contains("order"))).toList();
    }

    private void persist(UUID id, UUID userId, String symbol, String status,
                         List<ProviderEvidence> evidence, Instant now) {
        var maxLag = evidence.stream().map(ProviderEvidence::lagMs).filter(Objects::nonNull)
                .max(Long::compareTo).orElse(null);
        jdbc.update("""
                INSERT INTO production_readiness_checks
                    (id, user_id, symbol, status, degraded, max_lag_ms, evidence, created_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, id, userId, symbol, status, !"HEALTHY".equals(status), maxLag,
                evidenceJson(evidence), OffsetDateTime.now(java.time.ZoneOffset.UTC));
    }

    private List<ReadinessRow> latestEvidence(UUID userId) {
        return jdbc.query("""
                SELECT id, status, evidence, created_at
                  FROM production_readiness_checks
                 WHERE user_id = ?
                 ORDER BY created_at DESC, id DESC
                 LIMIT 1
                """, (result, row) -> new ReadinessRow(
                result.getObject("id", UUID.class), result.getString("status"),
                readEvidence(result.getString("evidence")),
                result.getObject("created_at", OffsetDateTime.class).toInstant()), userId);
    }

    private List<ProviderEvidence> readEvidence(String json) {
        try {
            return java.util.Arrays.asList(objectMapper.readValue(json, ProviderEvidence[].class));
        } catch (JacksonException exception) {
            return List.of();
        }
    }

    private ProviderConfiguration providerConfiguration(StockDataProviderId provider) {
        return providerProperties.providers().entrySet().stream()
                .filter(entry -> safeParse(entry.getKey()) == provider)
                .map(Map.Entry::getValue).map(ProviderConfiguration::new)
                .findFirst().orElse(ProviderConfiguration.EMPTY);
    }

    private boolean brokerCredentialPresent(UUID userId) {
        try {
            return Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(
                        SELECT 1 FROM broker_connections
                         WHERE id = ? AND user_id = ? AND status = 'ACTIVE'
                           AND deleted_at IS NULL AND credential_ciphertext IS NOT NULL)
                    """, Boolean.class, canary.connectionId(), userId));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean accountAllowlisted(UUID userId) {
        try {
            return Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM real_order_account_allowlist
                         WHERE user_id = ? AND broker_connection_id = ?
                           AND broker_account_id = ? AND enabled = TRUE)
                    """, Boolean.class, userId, canary.connectionId(), canary.brokerAccountId()));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean configured(ProviderConfiguration configuration) {
        return configuration != null && configuration.baseUrl() != null && !configuration.fields().isEmpty();
    }

    private static boolean credentialConfigured(StockDataProviderId provider,
                                                ProviderConfiguration configuration) {
        return configuration != null && ProviderCatalog.credentialsPresent(
                provider, configuration.apiKey(), configuration.userAgent());
    }

    static String overall(List<ProviderEvidence> evidence) {
        var active = evidence.stream().filter(item -> !"DISABLED".equals(item.status())).toList();
        if (active.isEmpty()) return "NOT_CONFIGURED";
        for (var status : List.of("NOT_CONFIGURED", "SECRET_MISSING", "UNAVAILABLE", "STALE", "DEGRADED")) {
            if (active.stream().anyMatch(item -> status.equals(item.status()))) return status;
        }
        return "HEALTHY";
    }

    private static Long lag(Instant asOf, Instant now) {
        return asOf == null || now == null ? null : Math.max(0L, Duration.between(asOf, now).toMillis());
    }

    private static StockDataProviderId safeParse(String value) {
        try {
            return StockDataProviderId.parse(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String requireSymbol(String value) {
        if (value == null || !value.matches("[A-Za-z0-9.\\-]{1,16}")) {
            throw new IllegalArgumentException("symbol is invalid");
        }
        return value.toUpperCase();
    }

    private static void requireId(UUID value) {
        Objects.requireNonNull(value, "userId");
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("actor is required");
    }

    public record Classification(String status, boolean ready, Long lagMs, List<String> missingData) {
    }

    public record ProviderEvidence(String provider, String status, Long lagMs,
                                   List<String> missingData, Instant asOf, Instant collectedAt) {
        public ProviderEvidence {
            missingData = missingData == null ? List.of() : List.copyOf(missingData);
        }
    }

    public record ReadinessView(String status, Instant measuredAt, CanaryView canary,
                                List<SchedulerView> schedulers, KillSwitchView killSwitch,
                                List<ProviderView> providers, FreshnessView dataFreshness,
                                List<String> alerts, UUID evidenceId) {
    }

    public record CanaryView(boolean enabled, boolean configured, boolean credentialsPresent,
                             boolean allowlisted, String status, List<String> blockers) {
    }

    public record SchedulerView(String name, boolean enabled, boolean credentialsRequired) {
    }

    public record KillSwitchView(String status, boolean engaged) {
    }

    public record ProviderView(String provider, String status, boolean enabled, boolean configured,
                               boolean credentialConfigured, boolean ready, Long lagMs,
                               Instant asOf, List<String> missingData) {
    }

    public record FreshnessView(String status, long maxLagMs, long maxAgeMs, Instant checkedAt) {
    }

    private record ReadinessRow(UUID id, String status, List<ProviderEvidence> evidence,
                                Instant createdAt) {
    }

    private record ProviderConfiguration(boolean enabled, java.net.URI baseUrl,
                                         Map<String, String> fields, String apiKey, String userAgent) {
        private static final ProviderConfiguration EMPTY =
                new ProviderConfiguration(false, null, Map.of(), "", "");

        private ProviderConfiguration(StockAnalysisProviderProperties.ProviderConfiguration value) {
            this(value.enabled(), value.baseUrl(), value.fields(),
                    value.apiKey() == null ? "" : value.apiKey(), value.userAgent());
        }
    }

    private static final class SetOf {
        private static final java.util.Set<String> OK = java.util.Set.of("HEALTHY", "DISABLED");
    }
}
