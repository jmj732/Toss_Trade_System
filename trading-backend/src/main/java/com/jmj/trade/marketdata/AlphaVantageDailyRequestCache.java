package com.jmj.trade.marketdata;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Durable one-attempt-per-symbol/function/day Alpha cache and daily quota guard. */
final class AlphaVantageDailyRequestCache {

    private static final StockDataProviderId PROVIDER = StockDataProviderId.ALPHA_VANTAGE;
    private static final String CACHE_UNAVAILABLE = "CACHE_UNAVAILABLE";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int dailyLimit;
    private final Map<Key, Entry> memoryEntries = new ConcurrentHashMap<>();
    private final Object memoryReservationLock = new Object();

    AlphaVantageDailyRequestCache(ObjectMapper mapper, Clock clock, int dailyLimit) {
        this(null, null, mapper, clock, dailyLimit);
    }

    AlphaVantageDailyRequestCache(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper mapper,
            Clock clock,
            int dailyLimit
    ) {
        if ((jdbc == null) != (transactionManager == null)) {
            throw new IllegalArgumentException("database cache requires JDBC and transaction manager together");
        }
        if (dailyLimit < 1) throw new IllegalArgumentException("daily limit must be positive");
        this.jdbc = jdbc;
        this.transactions = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        this.mapper = mapper;
        this.clock = clock;
        this.dailyLimit = dailyLimit;
    }

    List<ProviderValue> get(String symbol, String function, String apiKey,
                            Supplier<List<ProviderValue>> loader) {
        return get(symbol, function, List.of(apiKey == null ? "" : apiKey), ignored -> loader.get());
    }

    List<ProviderValue> get(String symbol, String function, List<String> apiKeys,
                            Function<String, List<ProviderValue>> loader) {
        var keys = normalizeKeys(apiKeys);
        var primaryKey = keys.isEmpty() ? null : keys.getFirst();
        return get(symbol, function, keys, primaryKey, loader);
    }

    List<ProviderValue> get(String symbol, String function, List<String> apiKeys, String legacyPrimaryApiKey,
                            Function<String, List<ProviderValue>> loader) {
        var keys = normalizeKeys(apiKeys);
        if (keys.isEmpty()) throw unavailable("API_KEY_UNAVAILABLE");
        var primaryKey = legacyPrimaryApiKey == null || legacyPrimaryApiKey.isBlank()
                ? null : legacyPrimaryApiKey.trim();
        String lastRotatableFailure = null;
        for (int index = 0; index < keys.size(); index++) {
            var apiKey = keys.get(index);
            var key = new Key(clock.instant().atZone(ZoneOffset.UTC).toLocalDate(), fingerprint(apiKey),
                    symbol.toUpperCase(Locale.ROOT), function.toUpperCase(Locale.ROOT));
            var includeLegacyUsage = primaryKey != null && primaryKey.equals(apiKey);
            var reservation = jdbc == null ? reserveMemory(key, includeLegacyUsage)
                    : reserveDatabase(key, includeLegacyUsage);
            if (reservation.failureCode() != null) {
                if (isRotatableFailure(reservation.failureCode())) {
                    lastRotatableFailure = preferDailyExhaustion(lastRotatableFailure, reservation.failureCode());
                    continue;
                }
                throw unavailable(reservation.failureCode());
            }
            if (reservation.state() == State.SUCCEEDED) return decode(reservation.payload());
            if (reservation.state() == State.FAILED) {
                var code = readFailureCode(reservation.payload());
                if (isRotatableFailure(code)) {
                    lastRotatableFailure = preferDailyExhaustion(lastRotatableFailure, code);
                    continue;
                }
                throw unavailable(code);
            }
            if (!reservation.owner()) throw unavailable("REQUEST_IN_PROGRESS");

            final List<ProviderValue> values;
            try {
                values = List.copyOf(loader.apply(apiKey));
            } catch (RuntimeException exception) {
                var code = exception instanceof ProviderUnavailableException unavailable
                        && unavailable.provider() == PROVIDER ? unavailable.reasonCode() : "API_ERROR";
                if (!storeFailure(key, code)) throw unavailable(CACHE_UNAVAILABLE);
                if (isRotatableFailure(code)) {
                    lastRotatableFailure = preferDailyExhaustion(lastRotatableFailure, code);
                    continue;
                }
                throw unavailable(code);
            }

            var payload = encode(values);
            if (jdbc == null) {
                completeMemory(key, new Entry(State.SUCCEEDED, payload, false));
            } else {
                try {
                    var updated = jdbc.update("""
                            UPDATE alpha_vantage_daily_cache
                               SET state = 'SUCCEEDED', payload = CAST(? AS jsonb), completed_at = ?
                             WHERE request_date = ? AND credential_fingerprint = ?
                               AND symbol = ? AND function = ? AND state = 'REQUESTED'
                            """, write(payload), timestamp(clock.instant()), key.date(), key.fingerprint(), key.symbol(),
                            key.function());
                    if (updated != 1) throw unavailable(CACHE_UNAVAILABLE);
                } catch (RuntimeException exception) {
                    if (exception instanceof ProviderUnavailableException unavailable) throw unavailable;
                    throw unavailable(CACHE_UNAVAILABLE);
                }
            }
            return values;
        }
        throw unavailable(lastRotatableFailure == null ? "DAILY_QUOTA_EXHAUSTED" : lastRotatableFailure);
    }

    private static List<String> normalizeKeys(List<String> apiKeys) {
        if (apiKeys == null) return List.of();
        var normalized = new java.util.LinkedHashSet<String>();
        for (var key : apiKeys) {
            if (key != null && !key.isBlank()) normalized.add(key.trim());
        }
        return List.copyOf(normalized);
    }

    private static boolean isRotatableFailure(String code) {
        return "DAILY_QUOTA_EXHAUSTED".equals(code) || "INVALID_API_KEY".equals(code);
    }

    private static String preferDailyExhaustion(String current, String candidate) {
        if (current == null || "DAILY_QUOTA_EXHAUSTED".equals(candidate)) return candidate;
        return current;
    }

    private Reservation reserveMemory(Key key, boolean includeLegacyUsage) {
        synchronized (memoryReservationLock) {
            memoryEntries.keySet().removeIf(existing -> existing.date().isBefore(key.date()));
            var logical = memoryEntries.entrySet().stream()
                    .filter(entry -> sameLogicalRequest(entry.getKey(), key))
                    .toList();
            var completed = logical.stream().filter(entry -> entry.getValue().state() == State.SUCCEEDED)
                    .findFirst();
            if (completed.isPresent()) {
                return new Reservation(State.SUCCEEDED, completed.get().getValue().payload(), false, null);
            }
            if (logical.stream().anyMatch(entry -> entry.getValue().state() == State.REQUESTED)) {
                return Reservation.denied("REQUEST_IN_PROGRESS");
            }
            var terminalFailure = logical.stream().filter(entry -> entry.getValue().state() == State.FAILED)
                    .map(entry -> readFailureCode(entry.getValue().payload()))
                    .filter(code -> !isRotatableFailure(code)).findFirst();
            if (terminalFailure.isPresent()) return Reservation.denied(terminalFailure.get());
            var existing = memoryEntries.get(key);
            if (existing != null) {
                return new Reservation(existing.state(), existing.payload(), false, null);
            }
            if (hasRotatableMemoryFailure(key)) return Reservation.denied(rotatableMemoryFailure(key));
            var used = memoryEntries.keySet().stream()
                    .filter(existingKey -> existingKey.date().equals(key.date())
                            && existingKey.fingerprint().equals(key.fingerprint()))
                    .count();
            if (used >= dailyLimit) return Reservation.denied("DAILY_QUOTA_EXHAUSTED");
            memoryEntries.put(key, new Entry(State.REQUESTED, mapper.createArrayNode(), true));
            return new Reservation(State.REQUESTED, null, true, null);
        }
    }

    private boolean hasRotatableMemoryFailure(Key key) {
        return memoryEntries.entrySet().stream().anyMatch(entry -> entry.getKey().date().equals(key.date())
                && entry.getKey().fingerprint().equals(key.fingerprint())
                && entry.getValue().state() == State.FAILED
                && isRotatableFailure(readFailureCode(entry.getValue().payload())));
    }

    private String rotatableMemoryFailure(Key key) {
        return memoryEntries.entrySet().stream().filter(entry -> entry.getKey().date().equals(key.date())
                        && entry.getKey().fingerprint().equals(key.fingerprint())
                        && entry.getValue().state() == State.FAILED)
                .map(entry -> readFailureCode(entry.getValue().payload()))
                .filter(AlphaVantageDailyRequestCache::isRotatableFailure)
                .reduce(null, AlphaVantageDailyRequestCache::preferDailyExhaustion);
    }

    private Reservation reserveDatabase(Key key, boolean includeLegacyUsage) {
        try {
            return transactions.execute(status -> {
                var logicalLockKey = "alpha-vantage-logical:" + key.date() + ":" + key.symbol() + ":"
                        + key.function();
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", resultSet -> null,
                        logicalLockKey);
                var lockKey = "alpha-vantage:" + key.date() + ":" + key.fingerprint();
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", resultSet -> null, lockKey);
                jdbc.update("""
                        DELETE FROM alpha_vantage_daily_cache
                         WHERE request_date < ?
                           AND (state <> 'REQUESTED' OR requested_at < ?)
                        """, key.date(), timestamp(clock.instant().minus(java.time.Duration.ofDays(1))));
                var logical = jdbc.query("""
                        SELECT state, payload::text
                          FROM alpha_vantage_daily_cache
                         WHERE request_date = ? AND symbol = ? AND function = ?
                         ORDER BY CASE state WHEN 'SUCCEEDED' THEN 0 WHEN 'REQUESTED' THEN 1 ELSE 2 END,
                                  requested_at
                        """, resultSet -> {
                    var entries = new ArrayList<Reservation>();
                    while (resultSet.next()) {
                        entries.add(new Reservation(State.valueOf(resultSet.getString(1)),
                                parse(resultSet.getString(2)), false, null));
                    }
                    return entries;
                }, key.date(), key.symbol(), key.function());
                var completed = logical.stream().filter(entry -> entry.state() == State.SUCCEEDED).findFirst();
                if (completed.isPresent()) return completed.get();
                if (logical.stream().anyMatch(entry -> entry.state() == State.REQUESTED)) {
                    return Reservation.denied("REQUEST_IN_PROGRESS");
                }
                var terminalFailure = logical.stream().filter(entry -> entry.state() == State.FAILED)
                        .map(entry -> readFailureCode(entry.payload()))
                        .filter(code -> !isRotatableFailure(code)).findFirst();
                if (terminalFailure.isPresent()) return Reservation.denied(terminalFailure.get());
                var cached = jdbc.query("""
                        SELECT state, payload::text
                          FROM alpha_vantage_daily_cache
                         WHERE request_date = ? AND credential_fingerprint = ?
                           AND symbol = ? AND function = ?
                        """, resultSet -> resultSet.next()
                        ? new Reservation(State.valueOf(resultSet.getString(1)), parse(resultSet.getString(2)),
                                false, null)
                        : null, key.date(), key.fingerprint(), key.symbol(), key.function());
                if (cached != null) return cached;
                if (hasRotatableDatabaseFailure(key)) {
                    return Reservation.denied(rotatableDatabaseFailure(key));
                }
                var used = jdbc.queryForObject(DAILY_USAGE_SQL, Long.class,
                        key.date(), key.fingerprint(), includeLegacyUsage, key.date(), key.date(), key.date());
                if (used != null && used >= dailyLimit) {
                    return Reservation.denied("DAILY_QUOTA_EXHAUSTED");
                }
                jdbc.update("""
                        INSERT INTO alpha_vantage_daily_cache (
                            request_date, credential_fingerprint, symbol, function, state,
                            payload, requested_at, completed_at
                        ) VALUES (?, ?, ?, ?, 'REQUESTED', CAST('[]' AS jsonb), ?, NULL)
                        """, key.date(), key.fingerprint(), key.symbol(), key.function(), timestamp(clock.instant()));
                return new Reservation(State.REQUESTED, null, true, null);
            });
        } catch (RuntimeException exception) {
            if (exception instanceof ProviderUnavailableException unavailable) throw unavailable;
            return Reservation.denied(CACHE_UNAVAILABLE);
        }
    }

    private boolean hasRotatableDatabaseFailure(Key key) {
        var count = jdbc.queryForObject("""
                SELECT count(*) FROM alpha_vantage_daily_cache
                 WHERE request_date = ? AND credential_fingerprint = ? AND state = 'FAILED'
                   AND payload->0->>'failureCode' IN ('DAILY_QUOTA_EXHAUSTED', 'INVALID_API_KEY')
                """, Long.class, key.date(), key.fingerprint());
        return count != null && count > 0;
    }

    private String rotatableDatabaseFailure(Key key) {
        var code = jdbc.query("""
                SELECT payload->0->>'failureCode'
                  FROM alpha_vantage_daily_cache
                 WHERE request_date = ? AND credential_fingerprint = ? AND state = 'FAILED'
                   AND payload->0->>'failureCode' IN ('DAILY_QUOTA_EXHAUSTED', 'INVALID_API_KEY')
                 ORDER BY CASE payload->0->>'failureCode'
                          WHEN 'DAILY_QUOTA_EXHAUSTED' THEN 0 ELSE 1 END
                 LIMIT 1
                """, resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                key.date(), key.fingerprint());
        return code == null ? "DAILY_QUOTA_EXHAUSTED" : code;
    }

    private static boolean sameLogicalRequest(Key left, Key right) {
        return left.date().equals(right.date()) && left.symbol().equals(right.symbol())
                && left.function().equals(right.function());
    }

    private boolean storeFailure(Key key, String failureCode) {
        var payload = mapper.createArrayNode();
        payload.addObject().put("failureCode", failureCode);
        if (jdbc == null) {
            completeMemory(key, new Entry(State.FAILED, payload, false));
            return true;
        }
        try {
            return jdbc.update("""
                    UPDATE alpha_vantage_daily_cache
                       SET state = 'FAILED', payload = CAST(? AS jsonb), completed_at = ?
                     WHERE request_date = ? AND credential_fingerprint = ?
                       AND symbol = ? AND function = ? AND state = 'REQUESTED'
                    """, write(payload), timestamp(clock.instant()), key.date(), key.fingerprint(), key.symbol(),
                    key.function()) == 1;
        } catch (RuntimeException ignored) {
            // REQUESTED remains a durable reservation; after restart it will not trigger another call.
            return false;
        }
    }

    private void completeMemory(Key key, Entry entry) {
        synchronized (memoryReservationLock) {
            var today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
            memoryEntries.keySet().removeIf(existing -> existing.date().isBefore(today));
            if (!key.date().isBefore(today)) memoryEntries.put(key, entry);
        }
    }

    private ArrayNode encode(List<ProviderValue> values) {
        var result = mapper.createArrayNode();
        for (var value : values) {
            var item = result.addObject();
            item.put("field", value.field());
            if (value.value() == null) item.putNull("value");
            else item.set("value", value.value().deepCopy());
            putNullable(item, "unit", value.unit());
            putNullable(item, "period", value.period());
            putNullable(item, "identifier", value.identifier());
            if (value.asOf() == null) item.putNull("asOf");
            else item.put("asOf", value.asOf().toString());
            var missing = item.putArray("missingData");
            value.missingData().forEach(missing::add);
            item.put("asOfBasis", value.asOfBasis().name());
        }
        return result;
    }

    private List<ProviderValue> decode(JsonNode payload) {
        if (payload == null || !payload.isArray()) throw unavailable("CACHE_CORRUPT");
        var values = new ArrayList<ProviderValue>();
        try {
            for (var item : payload) {
                if (!item.isObject() || item.has("failureCode")) throw unavailable("CACHE_CORRUPT");
                var field = item.path("field").asText(null);
                var rawAsOf = item.path("asOf");
                var asOf = rawAsOf.isTextual() ? java.time.Instant.parse(rawAsOf.asText()) : null;
                var missing = new ArrayList<String>();
                var missingNode = item.path("missingData");
                if (!missingNode.isArray()) throw unavailable("CACHE_CORRUPT");
                missingNode.forEach(reason -> {
                    if (reason.isTextual()) missing.add(reason.asText());
                    else throw unavailable("CACHE_CORRUPT");
                });
                var basis = StockAnalysisInput.AsOfBasis.valueOf(item.path("asOfBasis").asText());
                values.add(new ProviderValue(field, item.get("value"), nullableText(item, "unit"),
                        nullableText(item, "period"), nullableText(item, "identifier"), asOf, missing, basis));
            }
            return List.copyOf(values);
        } catch (RuntimeException exception) {
            if (exception instanceof ProviderUnavailableException unavailable) throw unavailable;
            throw unavailable("CACHE_CORRUPT");
        }
    }

    private String readFailureCode(JsonNode payload) {
        if (payload == null || !payload.isArray() || payload.isEmpty()
                || !payload.get(0).path("failureCode").isTextual()) return "API_ERROR";
        var code = payload.get(0).path("failureCode").asText();
        return code.matches("[A-Z0-9_]{1,64}") ? code : "API_ERROR";
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (JacksonException exception) {
            throw unavailable("CACHE_CORRUPT");
        }
    }

    private String write(JsonNode payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw unavailable("CACHE_UNAVAILABLE");
        }
    }

    private static String fingerprint(String apiKey) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((apiKey == null ? "" : apiKey).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static OffsetDateTime timestamp(java.time.Instant instant) {
        return instant.truncatedTo(java.time.temporal.ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    private static void putNullable(tools.jackson.databind.node.ObjectNode item, String name, String value) {
        if (value == null) item.putNull(name);
        else item.put(name, value);
    }

    private static String nullableText(JsonNode item, String key) {
        var value = item.get(key);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static ProviderUnavailableException unavailable(String code) {
        return new ProviderUnavailableException(PROVIDER, code);
    }

    private static final String DAILY_USAGE_SQL = """
            WITH cached AS (
                SELECT symbol, function,
                       CASE WHEN state = 'SUCCEEDED' AND payload->0->>'asOf' IS NOT NULL
                            THEN payload->0->>'asOf'
                            ELSE 'REQUEST:' || requested_at::text END AS identity
                  FROM alpha_vantage_daily_cache
                 WHERE request_date = ? AND credential_fingerprint = ?
            ), legacy AS (
                SELECT s.symbol, 'EARNINGS_ESTIMATES' AS function,
                       observation.value->>'asOf' AS identity
                  FROM analysis_input_snapshots s
                  CROSS JOIN LATERAL jsonb_array_elements(
                      CASE WHEN jsonb_typeof(s.payload->'observations') = 'array'
                           THEN s.payload->'observations' ELSE '[]'::jsonb END
                  ) AS observation(value)
                 WHERE observation.value->>'provider' = 'ALPHA_VANTAGE'
                   AND ?
                   AND observation.value->>'field' LIKE 'consensus.%'
                   AND observation.value->'value' IS NOT NULL
                   AND observation.value->'value' <> 'null'::jsonb
                   AND observation.value->'missingData' = '[]'::jsonb
                   AND NOT EXISTS (
                       SELECT 1 FROM alpha_vantage_daily_cache captured
                        WHERE captured.request_date = ? AND captured.symbol = s.symbol
                          AND captured.function = 'EARNINGS_ESTIMATES' AND captured.state = 'SUCCEEDED'
                          AND captured.payload->0->>'asOf' = observation.value->>'asOf'
                   )
                   AND NULLIF(observation.value->>'asOf', '')::timestamptz
                       >= (CAST(? AS date)::timestamp AT TIME ZONE 'UTC')
                   AND NULLIF(observation.value->>'asOf', '')::timestamptz
                       < ((CAST(? AS date) + 1)::timestamp AT TIME ZONE 'UTC')
            )
            SELECT COUNT(*)
              FROM (SELECT symbol, function, identity FROM cached
                    UNION
                    SELECT symbol, function, identity FROM legacy) usage
            """;

    private record Key(LocalDate date, String fingerprint, String symbol, String function) {
    }

    private record Entry(State state, JsonNode payload, boolean owner) {
    }

    private record Reservation(State state, JsonNode payload, boolean owner, String failureCode) {
        private static Reservation denied(String code) {
            return new Reservation(null, null, false, code);
        }
    }

    private enum State { REQUESTED, SUCCEEDED, FAILED }
}
