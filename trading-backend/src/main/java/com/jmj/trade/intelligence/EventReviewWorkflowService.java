package com.jmj.trade.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public final class EventReviewWorkflowService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final EventIntelligenceService events;

    EventReviewWorkflowService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            EventIntelligenceService events
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.events = Objects.requireNonNull(events, "events");
    }

    List<ReviewSummary> list(UUID userId, UUID connectionId, int limit) {
        var eventViews = events.list(userId, connectionId, limit);
        var states = jdbc.query("""
                SELECT event_id, status, version, reviewed_at
                  FROM event_reviews
                 WHERE user_id = ? AND broker_connection_id = ?
                """, (resultSet, rowNum) -> new ReviewState(
                resultSet.getObject("event_id", UUID.class),
                ReviewStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("version"),
                resultSet.getObject("reviewed_at", OffsetDateTime.class).toInstant()
        ), userId, connectionId).stream().collect(Collectors.toMap(
                ReviewState::eventId, Function.identity()));
        var compared = jdbc.queryForList("""
                SELECT event_id
                  FROM event_analysis_comparisons
                 WHERE user_id = ? AND broker_connection_id = ?
                """, UUID.class, userId, connectionId);
        return eventViews.stream()
                .map(event -> summary(event, states.get(event.id()), compared.contains(event.id())))
                .toList();
    }

    ReviewDetail get(UUID userId, UUID connectionId, UUID eventId) {
        var event = events.get(userId, connectionId, eventId);
        return detail(event, state(userId, connectionId, eventId),
                events.findComparison(userId, connectionId, eventId).orElse(null));
    }

    ReviewDetail review(
            UUID userId,
            UUID connectionId,
            UUID eventId,
            String idempotencyKey,
            ReviewCommand command
    ) {
        var key = key(idempotencyKey);
        if (userId == null || connectionId == null || eventId == null
                || command == null || command.status() == null
                || command.status() == ReviewStatus.PENDING
                || command.expectedVersion() == null
                || command.expectedVersion() < 0) {
            invalid();
        }
        return transaction.execute(status -> {
            var existing = receipt(userId, key);
            if (existing != null) {
                return replay(userId, connectionId, eventId, command, existing);
            }
            lockEvent(userId, connectionId, eventId);
            existing = receipt(userId, key);
            if (existing != null) {
                return replay(userId, connectionId, eventId, command, existing);
            }
            var current = state(userId, connectionId, eventId);
            var currentVersion = current == null ? 0 : current.version();
            if (currentVersion != command.expectedVersion()) {
                conflict();
            }
            var nextVersion = currentVersion + 1;
            var reviewedAt = now();
            var inserted = jdbc.update("""
                    INSERT INTO event_review_commands (
                        user_id, idempotency_key, event_id, broker_connection_id,
                        target_status, expected_version, resulting_version, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (user_id, idempotency_key) DO NOTHING
                    """, userId, key, eventId, connectionId, command.status().name(),
                    command.expectedVersion(), nextVersion, reviewedAt);
            if (inserted == 0) {
                return replay(userId, connectionId, eventId, command, receipt(userId, key));
            }
            jdbc.update("""
                    INSERT INTO event_reviews (
                        event_id, user_id, broker_connection_id, status, version, reviewed_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (event_id) DO UPDATE
                       SET status = EXCLUDED.status,
                           version = EXCLUDED.version,
                           reviewed_at = EXCLUDED.reviewed_at
                    """, eventId, userId, connectionId, command.status().name(),
                    nextVersion, reviewedAt);
            return detail(events.get(userId, connectionId, eventId),
                    new ReviewState(eventId, command.status(), nextVersion, reviewedAt.toInstant()),
                    events.findComparison(userId, connectionId, eventId).orElse(null));
        });
    }

    private ReviewDetail replay(
            UUID userId,
            UUID connectionId,
            UUID eventId,
            ReviewCommand command,
            Receipt receipt
    ) {
        if (!receipt.eventId().equals(eventId)
                || !receipt.connectionId().equals(connectionId)
                || receipt.status() != command.status()
                || receipt.expectedVersion() != command.expectedVersion()) {
            conflict();
        }
        var event = events.get(userId, connectionId, eventId);
        return detail(event, new ReviewState(eventId, receipt.status(),
                        receipt.resultingVersion(), receipt.createdAt()),
                events.findComparison(userId, connectionId, eventId).orElse(null));
    }

    private Receipt receipt(UUID userId, String key) {
        return jdbc.query("""
                SELECT event_id, broker_connection_id, target_status, expected_version,
                       resulting_version, created_at
                  FROM event_review_commands
                 WHERE user_id = ? AND idempotency_key = ?
                """, (resultSet, rowNum) -> new Receipt(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getObject("broker_connection_id", UUID.class),
                ReviewStatus.valueOf(resultSet.getString("target_status")),
                resultSet.getLong("expected_version"),
                resultSet.getLong("resulting_version"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant()
        ), userId, key).stream().findFirst().orElse(null);
    }

    private ReviewState state(UUID userId, UUID connectionId, UUID eventId) {
        return jdbc.query("""
                SELECT event_id, status, version, reviewed_at
                  FROM event_reviews
                 WHERE event_id = ? AND user_id = ? AND broker_connection_id = ?
                """, (resultSet, rowNum) -> new ReviewState(
                resultSet.getObject("event_id", UUID.class),
                ReviewStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("version"),
                resultSet.getObject("reviewed_at", OffsetDateTime.class).toInstant()
        ), eventId, userId, connectionId).stream().findFirst().orElse(null);
    }

    private void lockEvent(UUID userId, UUID connectionId, UUID eventId) {
        if (jdbc.queryForList("""
                SELECT id
                  FROM intelligence_events
                 WHERE id = ? AND user_id = ? AND broker_connection_id = ?
                 FOR UPDATE
                """, UUID.class, eventId, userId, connectionId).size() != 1) {
            throw new EventIntelligenceException(EventIntelligenceException.Code.EVENT_NOT_FOUND);
        }
    }

    private static ReviewSummary summary(
            EventIntelligenceService.EventView event,
            ReviewState state,
            boolean comparisonAvailable
    ) {
        return new ReviewSummary(event.id(), event.source(), event.sourceEventId(), event.type(),
                event.summary(), event.affectedSymbols(), event.occurredAt(), event.collectedAt(),
                state == null ? ReviewStatus.PENDING : state.status(),
                state == null ? 0 : state.version(),
                state == null ? null : state.reviewedAt(), comparisonAvailable);
    }

    private static ReviewDetail detail(
            EventIntelligenceService.EventView event,
            ReviewState state,
            EventIntelligenceService.ComparisonView comparison
    ) {
        var summary = summary(event, state, comparison != null);
        return new ReviewDetail(summary.id(), summary.source(), summary.sourceEventId(),
                summary.type(), summary.summary(), summary.affectedSymbols(),
                summary.occurredAt(), summary.collectedAt(), summary.reviewStatus(),
                summary.reviewVersion(), summary.reviewedAt(), summary.comparisonAvailable(),
                comparison);
    }

    private static String key(String value) {
        if (value == null || value.isBlank()) {
            invalid();
        }
        var normalized = value.trim();
        if (normalized.length() > 80) {
            invalid();
        }
        return normalized;
    }

    private static void invalid() {
        throw new EventIntelligenceException(EventIntelligenceException.Code.INVALID_INPUT);
    }

    private static void conflict() {
        throw new EventIntelligenceException(EventIntelligenceException.Code.REVIEW_CONFLICT);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public enum ReviewStatus {
        PENDING,
        CONFIRMED,
        HELD,
        IGNORED
    }

    public record ReviewCommand(ReviewStatus status, Long expectedVersion) {
    }

    public record ReviewSummary(
            UUID id,
            String source,
            String sourceEventId,
            String type,
            String summary,
            List<String> affectedSymbols,
            Instant occurredAt,
            Instant collectedAt,
            ReviewStatus reviewStatus,
            long reviewVersion,
            Instant reviewedAt,
            boolean comparisonAvailable
    ) {
    }

    public record ReviewDetail(
            UUID id,
            String source,
            String sourceEventId,
            String type,
            String summary,
            List<String> affectedSymbols,
            Instant occurredAt,
            Instant collectedAt,
            ReviewStatus reviewStatus,
            long reviewVersion,
            Instant reviewedAt,
            boolean comparisonAvailable,
            EventIntelligenceService.ComparisonView analysisComparison
    ) {
    }

    private record ReviewState(
            UUID eventId,
            ReviewStatus status,
            long version,
            Instant reviewedAt
    ) {
    }

    private record Receipt(
            UUID eventId,
            UUID connectionId,
            ReviewStatus status,
            long expectedVersion,
            long resultingVersion,
            Instant createdAt
    ) {
    }
}
