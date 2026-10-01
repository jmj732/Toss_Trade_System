package com.jmj.trade.monitoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class MonitoringWatchlistService {

    private static final Set<String> CONDITION_KEYS = Set.of("add", "reduce", "exit", "invalidation");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final MonitoringStateStore states;

    MonitoringWatchlistService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            MonitoringStateStore states
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.states = Objects.requireNonNull(states, "states");
    }

    public List<WatchlistEntry> list(UUID userId) {
        requireUser(userId);
        return jdbc.query("""
                SELECT id, symbol, status, levels::text, evidence::text, observed_at, created_at, updated_at
                  FROM monitoring_watchlist
                 WHERE user_id = ?
                 ORDER BY symbol
                """, (resultSet, rowNum) -> entry(
                resultSet.getObject("id", UUID.class), resultSet.getString("symbol"),
                resultSet.getString("status"), resultSet.getString("levels"),
                resultSet.getString("evidence"), resultSet.getObject("observed_at", OffsetDateTime.class),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class)), userId);
    }

    @Transactional
    WatchlistEntry put(UUID userId, String symbol, WatchlistLevels levels) {
        requireUser(userId);
        var normalizedSymbol = symbol(symbol);
        if (levels == null) {
            throw new MonitoringException(MonitoringException.Code.INVALID_INPUT);
        }
        var normalizedLevels = levels;
        var now = now();
        var json = encode(normalizedLevels);
        var initialEvidence = encode(initialEvidence(normalizedLevels, now.toInstant()));
        var result = jdbc.query("""
                INSERT INTO monitoring_watchlist (id, user_id, symbol, status, levels, evidence,
                                                 observed_at, created_at, updated_at)
                VALUES (?, ?, ?, 'WATCH', CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?)
                ON CONFLICT (user_id, symbol) DO UPDATE SET
                    levels = EXCLUDED.levels, updated_at = EXCLUDED.updated_at
                RETURNING id, symbol, status, levels::text, evidence::text,
                          observed_at, created_at, updated_at
                """, (resultSet, rowNum) -> entry(
                resultSet.getObject("id", UUID.class), resultSet.getString("symbol"),
                resultSet.getString("status"), resultSet.getString("levels"),
                resultSet.getString("evidence"), resultSet.getObject("observed_at", OffsetDateTime.class),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class)),
                UUID.randomUUID(), userId, normalizedSymbol, json, initialEvidence, now, now, now).getFirst();
        states.observe(userId, "WATCHLIST", normalizedSymbol, result.status(),
                result.evidence(), "CONFIGURATION", null, result.observedAt());
        return result;
    }

    @Transactional
    void remove(UUID userId, String symbol) {
        requireUser(userId);
        var normalizedSymbol = symbol(symbol);
        var updated = now();
        if (jdbc.update("DELETE FROM monitoring_watchlist WHERE user_id = ? AND symbol = ?",
                userId, normalizedSymbol) != 1) {
            throw new MonitoringException(MonitoringException.Code.NOT_FOUND);
        }
        states.remove(userId, "WATCHLIST", normalizedSymbol, updated.toInstant());
    }

    Optional<PositionContext> positionContext(UUID userId, String symbol) {
        requireUser(userId);
        var normalizedSymbol = symbol(symbol);
        return jdbc.query("""
                SELECT symbol, sector, factor, beta, correlation, thesis, primary_alpha,
                       conditions::text, updated_at
                  FROM monitoring_position_contexts
                 WHERE user_id = ? AND symbol = ?
                """, (resultSet, rowNum) -> new PositionContext(
                resultSet.getString("symbol"), resultSet.getString("sector"),
                resultSet.getString("factor"), resultSet.getBigDecimal("beta"),
                resultSet.getBigDecimal("correlation"), resultSet.getString("thesis"),
                resultSet.getString("primary_alpha"), decodeConditions(resultSet.getString("conditions")),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()),
                userId, normalizedSymbol).stream().findFirst();
    }

    @Transactional
    PositionContext putPositionContext(UUID userId, String symbol, PositionContextInput input) {
        requireUser(userId);
        var normalizedSymbol = symbol(symbol);
        var value = input == null ? new PositionContextInput(null, null, null, null, null, null, Map.of()) : input;
        validateContext(value);
        var now = now();
        var conditions = encode(value.conditions());
        return jdbc.query("""
                INSERT INTO monitoring_position_contexts (
                    user_id, symbol, sector, factor, beta, correlation, thesis, primary_alpha,
                    conditions, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (user_id, symbol) DO UPDATE SET
                    sector = EXCLUDED.sector, factor = EXCLUDED.factor,
                    beta = EXCLUDED.beta, correlation = EXCLUDED.correlation,
                    thesis = EXCLUDED.thesis, primary_alpha = EXCLUDED.primary_alpha,
                    conditions = EXCLUDED.conditions, updated_at = EXCLUDED.updated_at
                RETURNING symbol, sector, factor, beta, correlation, thesis, primary_alpha,
                          conditions::text, updated_at
                """, (resultSet, rowNum) -> new PositionContext(
                resultSet.getString("symbol"), resultSet.getString("sector"),
                resultSet.getString("factor"), resultSet.getBigDecimal("beta"),
                resultSet.getBigDecimal("correlation"), resultSet.getString("thesis"),
                resultSet.getString("primary_alpha"), decodeConditions(resultSet.getString("conditions")),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()),
                userId, normalizedSymbol, text(value.sector(), 100), text(value.factor(), 100),
                value.beta(), value.correlation(), text(value.thesis(), 5000),
                text(value.primaryAlpha(), 2000), conditions, now).getFirst();
    }

    private WatchlistEntry entry(
            UUID id, String symbol, String status, String levels, String evidence,
            OffsetDateTime observedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt
    ) {
        return new WatchlistEntry(id, symbol, status, decode(levels, WatchlistLevels.class),
                decodeMap(evidence), observedAt.toInstant(), createdAt.toInstant(), updatedAt.toInstant());
    }

    private Map<String, String> decodeConditions(String json) {
        try {
            @SuppressWarnings("unchecked")
            var decoded = (Map<String, String>) objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
            return Map.copyOf(decoded);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored monitoring conditions are invalid", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decodeMap(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored monitoring evidence is invalid", exception);
        }
    }

    private <T> T decode(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored monitoring definition is invalid", exception);
        }
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("monitoring definition serialization failed", exception);
        }
    }

    static Map<String, Object> initialEvidence(WatchlistLevels levels, Instant asOf) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("action", "CREATE_WATCHLIST");
        evidence.put("source", "USER_CONFIGURATION");
        evidence.put("asOf", asOf.toString());
        evidence.put("levels", levels);
        return evidence;
    }

    private static void validateContext(PositionContextInput value) {
        if (value.correlation() != null && (value.correlation().compareTo(BigDecimal.ONE) > 0
                || value.correlation().compareTo(BigDecimal.ONE.negate()) < 0)
                || value.conditions() == null || value.conditions().keySet().stream()
                .anyMatch(key -> !CONDITION_KEYS.contains(key))) {
            throw new MonitoringException(MonitoringException.Code.INVALID_INPUT);
        }
        text(value.sector(), 100);
        text(value.factor(), 100);
        text(value.thesis(), 5000);
        text(value.primaryAlpha(), 2000);
        value.conditions().forEach((key, condition) -> {
            if (condition == null || condition.isBlank() || condition.length() > 1000) {
                throw new MonitoringException(MonitoringException.Code.INVALID_INPUT);
            }
        });
    }

    private static String symbol(String value) {
        if (value == null || value.isBlank()) {
            throw new MonitoringException(MonitoringException.Code.INVALID_INPUT);
        }
        var normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > 32 || !normalized.matches("[A-Z0-9._-]+")) {
            throw new MonitoringException(MonitoringException.Code.INVALID_INPUT);
        }
        return normalized;
    }

    private static String text(String value, int limit) {
        if (value == null) {
            return null;
        }
        var normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > limit) {
            throw new MonitoringException(MonitoringException.Code.INVALID_INPUT);
        }
        return normalized;
    }

    private static void requireUser(UUID userId) {
        if (userId == null) {
            throw new MonitoringException(MonitoringException.Code.INVALID_USER);
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public record WatchlistLevels(Range prepare, Range confirm, Range pullback, Range invalidate) {
        public WatchlistLevels {
            if (prepare == null || confirm == null || pullback == null || invalidate == null) {
                throw new MonitoringException(MonitoringException.Code.INVALID_INPUT);
            }
            validateRange(prepare);
            validateRange(confirm);
            validateRange(pullback);
            validateRange(invalidate);
        }
    }

    public record Range(BigDecimal min, BigDecimal max) {
    }

    public record WatchlistEntry(
            UUID id,
            String symbol,
            String status,
            WatchlistLevels levels,
            Map<String, Object> evidence,
            Instant observedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record PositionContextInput(
            String sector,
            String factor,
            BigDecimal beta,
            BigDecimal correlation,
            String thesis,
            String primaryAlpha,
            Map<String, String> conditions
    ) {
    }

    public record PositionContext(
            String symbol,
            String sector,
            String factor,
            BigDecimal beta,
            BigDecimal correlation,
            String thesis,
            String primaryAlpha,
            Map<String, String> conditions,
            Instant updatedAt
    ) {
    }

    private static void validateRange(Range range) {
        if (range == null) {
            return;
        }
        if (range.min() == null || range.max() == null
                || range.min().signum() <= 0 || range.max().signum() <= 0
                || range.min().compareTo(range.max()) > 0) {
            throw new MonitoringException(MonitoringException.Code.INVALID_INPUT);
        }
    }
}
