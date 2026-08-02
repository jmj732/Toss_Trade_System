package com.jmj.trade.order;

import com.jmj.trade.inbox.InboxConsumer;
import com.jmj.trade.inbox.InboxLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Generic relay over one order outbox table, dispatching each claimed row to its registered
 * {@link InboxConsumer}s through the inbox idempotency ledger. A row is claimed in an outer
 * transaction via {@code SELECT ... FOR UPDATE SKIP LOCKED} — so overlapping scheduler ticks or
 * multiple instances still partition the backlog rather than double-claiming a row — and that
 * claim lock is held for the whole dispatch.
 *
 * <p>Crucially, each consumer runs in its <em>own</em> {@code REQUIRES_NEW} transaction, committing
 * the consumer's domain change and its {@code inbox_messages} row together and independently of
 * every other consumer. One consumer throwing rolls back only its own transaction; a sibling that
 * already succeeded stays committed. {@code published_at} is stamped (in the outer transaction)
 * only once every consumer of the event type has an inbox row — a row where some consumer failed
 * stays unpublished, its {@code attempts} advances toward the dead-letter cap, and the next tick
 * re-dispatches, skipping consumers that already finished. An event type with no consumers is
 * published immediately. A consumer already recorded in the inbox is not run again, so re-delivery
 * is a no-op.
 */
public final class OrderOutboxProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(OrderOutboxProcessor.class);

    private final JdbcTemplate jdbc;
    private final InboxLedger inbox;
    private final TransactionTemplate claimTransaction;
    private final TransactionTemplate consumerTransaction;
    private final String table;
    private final Map<String, List<InboxConsumer<OrderOutboxRecord>>> consumers;
    private final int maxAttempts;

    private final String claimSql;
    private final String publishSql;
    private final String failSql;

    public OrderOutboxProcessor(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            InboxLedger inbox,
            String table,
            Map<String, List<InboxConsumer<OrderOutboxRecord>>> consumers,
            int maxAttempts
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.claimTransaction = new TransactionTemplate(transactionManager);
        this.consumerTransaction = new TransactionTemplate(transactionManager);
        this.consumerTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.table = requireTable(table);
        this.consumers = copyConsumers(Objects.requireNonNull(consumers, "consumers"));
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.maxAttempts = maxAttempts;
        this.claimSql = """
                SELECT *, payload::text AS payload_json
                  FROM %s
                 WHERE published_at IS NULL AND failed_at IS NULL
                 ORDER BY created_at, id
                 LIMIT 1
                 FOR UPDATE SKIP LOCKED
                """.formatted(table);
        this.publishSql = "UPDATE %s SET published_at = ? WHERE id = ?".formatted(table);
        this.failSql = """
                UPDATE %s
                   SET attempts = attempts + 1,
                       failed_at = CASE WHEN attempts + 1 >= ? THEN ? ELSE failed_at END,
                       last_error = CASE WHEN attempts + 1 >= ? THEN ? ELSE last_error END
                 WHERE id = ? AND published_at IS NULL AND failed_at IS NULL
                """.formatted(table);
    }

    public ProcessResult process(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        var published = 0;
        for (var i = 0; i < batchSize; i++) {
            Boolean claimed;
            try {
                claimed = claimTransaction.execute(status -> claimAndDispatch());
            } catch (RowProcessingFailed failure) {
                recordFailure(failure);
                continue;
            }
            if (claimed == null) {
                break;
            }
            published++;
        }
        return new ProcessResult(published);
    }

    private Boolean claimAndDispatch() {
        var row = jdbc.query(claimSql, new ColumnMapRowMapper())
                .stream().findFirst().orElse(null);
        if (row == null) {
            return null;
        }
        var record = new OrderOutboxRecord(row);
        var eventConsumers = consumers.getOrDefault(record.eventType(), List.of());
        RowProcessingFailed failure = null;
        for (var consumer : eventConsumers) {
            try {
                consumerTransaction.executeWithoutResult(status -> dispatchOne(consumer, record));
            } catch (DuplicateKeyException concurrentlyProcessed) {
                // Another worker recorded this (consumer, event) first; its commit stands and this
                // consumer's own transaction rolled back, so the side effect happened exactly once.
            } catch (RuntimeException exception) {
                // Keep dispatching the remaining consumers — their success must not be held hostage
                // to this one's failure — but remember the first failure so the row stays
                // unpublished and its attempts advance. Carry only the exception's type name
                // outward so last_error can never leak a payload or secret.
                if (failure == null) {
                    failure = new RowProcessingFailed(record.id(), record.eventType(),
                            exception.getClass().getName(), exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        jdbc.update(publishSql, now(), record.id());
        return Boolean.TRUE;
    }

    private void dispatchOne(InboxConsumer<OrderOutboxRecord> consumer, OrderOutboxRecord record) {
        if (inbox.isProcessed(consumer.name(), record.id())) {
            return;
        }
        consumer.consume(record);
        inbox.markProcessed(consumer.name(), record.id(), record.eventType());
    }

    private void recordFailure(RowProcessingFailed failure) {
        LOG.atWarn()
                .addKeyValue("operation", "order_outbox_relay")
                .addKeyValue("table", table)
                .addKeyValue("outbox_event_id", failure.id())
                .addKeyValue("event_type", failure.eventType())
                .addKeyValue("error_type", failure.errorType())
                .setCause(failure.getCause())
                .log("order outbox consumer failed");
        claimTransaction.executeWithoutResult(status ->
                jdbc.update(failSql,
                        maxAttempts, now(),
                        maxAttempts, "handler failed: " + failure.errorType(),
                        failure.id()));
    }

    private static Map<String, List<InboxConsumer<OrderOutboxRecord>>> copyConsumers(
            Map<String, List<InboxConsumer<OrderOutboxRecord>>> consumers) {
        var copy = new HashMap<String, List<InboxConsumer<OrderOutboxRecord>>>();
        consumers.forEach((eventType, list) -> copy.put(
                Objects.requireNonNull(eventType, "eventType"),
                List.copyOf(Objects.requireNonNull(list, "consumers list"))));
        return Map.copyOf(copy);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static String requireTable(String table) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table is required");
        }
        return table;
    }

    public record ProcessResult(int published) {
    }

    private static final class RowProcessingFailed extends RuntimeException {

        private final UUID id;
        private final String eventType;
        private final String errorType;

        private RowProcessingFailed(UUID id, String eventType, String errorType, Throwable cause) {
            super(cause);
            this.id = id;
            this.eventType = eventType;
            this.errorType = errorType;
        }

        private UUID id() {
            return id;
        }

        private String eventType() {
            return eventType;
        }

        private String errorType() {
            return errorType;
        }
    }
}
