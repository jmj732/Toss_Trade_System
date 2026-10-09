package com.jmj.trade.investment;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Append-only review/audit notes, kept separate from the investment decision ledger.
 *
 * <p>A review record never creates a decision, order or thesis. Raw caller values (for example action
 * {@code REVIEW}, a non-UUID legacy key or an unknown price session) are stored verbatim; the canonical
 * {@code priceSession} is set only when the raw value is exactly one of the supported sessions.
 */
@Service
public final class InvestmentReviewService {

    public static final int CONTEXT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    private static final int MAX_RECORD_KEY = 128;
    private static final int MAX_RAW_TOKEN = 64;
    private static final int MAX_OUTCOME = 2000;
    private static final int MAX_RATIONALE = 5000;
    private static final int MAX_TRIGGER = 2000;
    private static final int MAX_RAW_PAYLOAD_CHARS = 16_384;
    private static final Set<String> SOURCES = Set.of("USER_REST", "CONNECTOR_MCP", "SHEET_LEGACY_IMPORT");
    private static final String COLUMNS = """
            id, source, record_key, scope, asset, as_of, raw_action, outcome, rationale, next_review_trigger,
            reference_price, raw_price_session, price_session, source_as_of, raw_payload::text AS raw_payload,
            actor_type, actor_user_id, actor_session_id, recorded_at""";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public InvestmentReviewService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(jdbc, objectMapper, Clock.systemUTC());
    }

    public InvestmentReviewService(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Validates, normalizes and appends one review record. A replay with the same
     * (user, source, recordKey) and an identical normalized body returns the stored row unchanged;
     * any difference is a {@link InvestmentException.Code#CONFLICT}.
     */
    public ReviewView recordReview(UUID userId, ReviewInput input, Actor actor) {
        requireUser(userId);
        var review = normalize(userId, input, actor);
        var payload = encode(review.rawPayload());
        var inserted = jdbc.update("""
                INSERT INTO investment_review_records (
                    id, user_id, source, record_key, scope, asset, as_of, raw_action, outcome, rationale,
                    next_review_trigger, reference_price, raw_price_session, price_session, source_as_of,
                    raw_payload, actor_type, actor_user_id, actor_session_id, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
                ON CONFLICT (user_id, source, record_key) DO NOTHING
                """, UUID.randomUUID(), userId, review.source(), review.recordKey(), review.scope(), review.asset(),
                timestamp(review.asOf()), review.rawAction(), review.outcome(), review.rationale(),
                review.nextReviewTrigger(), review.referencePrice(), review.rawPriceSession(),
                canonicalSession(review.rawPriceSession()), timestampOrNull(review.sourceAsOf()), payload,
                actor.type(), actor.userId(), actor.sessionId(), timestamp(clock.instant()));
        if (inserted == 0) {
            record Existing(ReviewView view, boolean samePayload) {
            }
            var existing = jdbc.query("SELECT " + COLUMNS + ", raw_payload = CAST(? AS jsonb) AS same_payload"
                            + " FROM investment_review_records WHERE user_id = ? AND source = ? AND record_key = ?",
                    (resultSet, rowNum) -> new Existing(reviewRow().mapRow(resultSet, rowNum),
                            resultSet.getBoolean("same_payload")),
                    payload, userId, review.source(), review.recordKey()).stream().findFirst().orElse(null);
            if (existing == null || !existing.samePayload() || !sameReview(existing.view(), review)) {
                throw new InvestmentException(InvestmentException.Code.CONFLICT);
            }
            return existing.view();
        }
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM investment_review_records WHERE user_id = ? AND source = ? AND record_key = ?",
                reviewRow(), userId, review.source(), review.recordKey()).getFirst();
    }

    /** Owner-scoped review records, newest {@code asOf} first. */
    public List<ReviewView> reviews(UUID userId, int limit) {
        requireUser(userId);
        if (limit < 1 || limit > MAX_LIMIT) throw new InvestmentException(InvestmentException.Code.INVALID_INPUT);
        return recent(userId, limit);
    }

    /** Context read path: the user was already validated by the caller. */
    List<ReviewView> recent(UUID userId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM investment_review_records
                WHERE user_id = ?
                ORDER BY as_of DESC, recorded_at DESC, id DESC
                LIMIT ?
                """, reviewRow(), userId, limit);
    }

    private ReviewInput normalize(UUID userId, ReviewInput input, Actor actor) {
        if (input == null || actor == null || !userId.equals(actor.userId())
                || !Set.of("USER_SESSION", "CONNECTOR_MCP").contains(actor.type())) {
            throw invalid();
        }
        var source = input.source() == null ? "" : input.source().trim().toUpperCase(Locale.ROOT);
        if (!SOURCES.contains(source) || "CONNECTOR_MCP".equals(source) != "CONNECTOR_MCP".equals(actor.type())) {
            throw invalid();
        }
        var recordKey = input.recordKey();
        if (recordKey == null || recordKey.isBlank() || recordKey.length() > MAX_RECORD_KEY) throw invalid();
        var scope = input.scope() == null ? "" : input.scope().trim().toUpperCase(Locale.ROOT);
        String asset;
        if ("SECURITY".equals(scope)) {
            asset = InvestmentContextService.ticker(input.asset());
        } else if ("PORTFOLIO".equals(scope)) {
            if (input.asset() != null && !input.asset().isBlank()) throw invalid();
            asset = null;
        } else {
            throw invalid();
        }
        var now = clock.instant();
        if (input.asOf() == null || input.asOf().isAfter(now)
                || input.sourceAsOf() != null && input.sourceAsOf().isAfter(now)) {
            throw invalid();
        }
        if (tooLong(input.rawAction(), MAX_RAW_TOKEN) || tooLong(input.rawPriceSession(), MAX_RAW_TOKEN)
                || tooLong(input.outcome(), MAX_OUTCOME) || tooLong(input.rationale(), MAX_RATIONALE)
                || tooLong(input.nextReviewTrigger(), MAX_TRIGGER)) {
            throw invalid();
        }
        BigDecimal referencePrice = null;
        if (input.referencePrice() != null) {
            referencePrice = input.referencePrice().setScale(8, RoundingMode.HALF_UP);
            if (referencePrice.signum() <= 0 || referencePrice.precision() - referencePrice.scale() > 16) {
                throw invalid();
            }
        }
        var rawPayload = input.rawPayload();
        if (rawPayload == null || rawPayload.isNull() || rawPayload.isMissingNode()) {
            // A legacy import must carry the original row; otherwise the received input is the raw record.
            if ("SHEET_LEGACY_IMPORT".equals(source)) throw invalid();
            var derived = (ObjectNode) objectMapper.valueToTree(input);
            derived.remove("rawPayload");
            rawPayload = derived;
        }
        if (!rawPayload.isObject() || encode(rawPayload).length() > MAX_RAW_PAYLOAD_CHARS) throw invalid();
        return new ReviewInput(source, recordKey, scope, asset, input.asOf().truncatedTo(ChronoUnit.MICROS),
                input.rawAction(), clean(input.outcome()), clean(input.rationale()), clean(input.nextReviewTrigger()),
                referencePrice, input.rawPriceSession(),
                input.sourceAsOf() == null ? null : input.sourceAsOf().truncatedTo(ChronoUnit.MICROS), rawPayload);
    }

    private static boolean sameReview(ReviewView existing, ReviewInput input) {
        return existing.scope().equals(input.scope())
                && Objects.equals(existing.asset(), input.asset())
                && existing.asOf().equals(input.asOf())
                && Objects.equals(existing.rawAction(), input.rawAction())
                && Objects.equals(existing.outcome(), input.outcome())
                && Objects.equals(existing.rationale(), input.rationale())
                && Objects.equals(existing.nextReviewTrigger(), input.nextReviewTrigger())
                && (existing.referencePrice() == null ? input.referencePrice() == null
                : input.referencePrice() != null && existing.referencePrice().compareTo(input.referencePrice()) == 0)
                && Objects.equals(existing.rawPriceSession(), input.rawPriceSession())
                && Objects.equals(existing.sourceAsOf(), input.sourceAsOf());
    }

    /** Exact canonical session only; anything else (aliases, typos, blanks) stays raw with a null session. */
    static String canonicalSession(String raw) {
        return InvestmentContextService.normalizeSession(raw);
    }

    private RowMapper<ReviewView> reviewRow() {
        return (resultSet, rowNum) -> new ReviewView(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("source"),
                resultSet.getString("record_key"),
                resultSet.getString("scope"),
                resultSet.getString("asset"),
                instant(resultSet.getObject("as_of", OffsetDateTime.class)),
                resultSet.getString("raw_action"),
                resultSet.getString("outcome"),
                resultSet.getString("rationale"),
                resultSet.getString("next_review_trigger"),
                resultSet.getBigDecimal("reference_price"),
                resultSet.getString("raw_price_session"),
                resultSet.getString("price_session"),
                instant(resultSet.getObject("source_as_of", OffsetDateTime.class)),
                decode(resultSet.getString("raw_payload")),
                resultSet.getString("actor_type"),
                resultSet.getObject("actor_user_id", UUID.class),
                resultSet.getObject("actor_session_id", UUID.class),
                instant(resultSet.getObject("recorded_at", OffsetDateTime.class)));
    }

    private void requireUser(UUID userId) {
        if (userId == null || jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users WHERE id = ?)",
                Boolean.class, userId) != Boolean.TRUE) {
            throw new InvestmentException(InvestmentException.Code.INVALID_USER);
        }
    }

    private String encode(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw invalid();
        }
    }

    private JsonNode decode(String value) {
        try {
            return value == null ? null : objectMapper.readTree(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored review payload is unreadable", exception);
        }
    }

    private static InvestmentException invalid() {
        return new InvestmentException(InvestmentException.Code.INVALID_INPUT);
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static OffsetDateTime timestamp(Instant value) {
        return OffsetDateTime.ofInstant(value.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static OffsetDateTime timestampOrNull(Instant value) {
        return value == null ? null : timestamp(value);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** Who appended the record. Connector writes carry no session id. */
    public record Actor(String type, UUID userId, UUID sessionId) {
        public static Actor userSession(UUID userId, UUID sessionId) {
            return new Actor("USER_SESSION", userId, sessionId);
        }

        public static Actor connector(UUID userId) {
            return new Actor("CONNECTOR_MCP", userId, null);
        }
    }

    /**
     * Caller-authored review. {@code rawPayload} is the original input verbatim; when omitted the received
     * fields are stored. It is required for {@code SHEET_LEGACY_IMPORT} (the full original sheet row).
     */
    public record ReviewInput(
            String source, String recordKey, String scope, String asset, Instant asOf, String rawAction,
            String outcome, String rationale, String nextReviewTrigger, BigDecimal referencePrice,
            String rawPriceSession, Instant sourceAsOf, JsonNode rawPayload
    ) {
        public ReviewInput withSource(String value) {
            return new ReviewInput(value, recordKey, scope, asset, asOf, rawAction, outcome, rationale,
                    nextReviewTrigger, referencePrice, rawPriceSession, sourceAsOf, rawPayload);
        }

        public ReviewInput withRawPayload(JsonNode value) {
            return new ReviewInput(source, recordKey, scope, asset, asOf, rawAction, outcome, rationale,
                    nextReviewTrigger, referencePrice, rawPriceSession, sourceAsOf, value);
        }
    }

    public record ReviewView(
            UUID id, String source, String recordKey, String scope, String asset, Instant asOf,
            String rawAction, String outcome, String rationale, String nextReviewTrigger,
            BigDecimal referencePrice, String rawPriceSession, String priceSession, Instant sourceAsOf,
            JsonNode rawPayload, String actorType, UUID actorUserId, UUID actorSessionId, Instant recordedAt
    ) {
    }
}
