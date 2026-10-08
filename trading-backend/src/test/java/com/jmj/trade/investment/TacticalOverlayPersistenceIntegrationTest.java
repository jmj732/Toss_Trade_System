package com.jmj.trade.investment;

import com.jmj.trade.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TacticalOverlayPersistenceIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("9ce5d74e-9340-4c2a-b61d-5f95b8375a27");
    private JdbcTemplate jdbc;
    private HikariDataSource dataSource;

    @BeforeEach
    void migrateAndSeed() {
        freshMigratedSchema();
        dataSource = pooledTestDataSource();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO users (id) VALUES (?)", USER_ID);
    }

    @AfterEach
    void closeTestDataSource() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void tacticalInputsBarsAndDerivedSnapshotsAreAppendOnlyAndVersioned() {
        var inputId = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO investment_tactical_overlay_inputs (
                    id, user_id, input_type, entity_key, ticker, effective_date, source,
                    source_as_of, active, overlay_version, payload, created_at
                ) VALUES (?, ?, 'AVWAP_ANCHOR', 'aapl:earnings-gap', 'AAPL', ?, 'USER_INPUT',
                          ?, true, 'TACTICAL_V1', '{"anchorType":"EARNINGS_GAP"}'::jsonb, ?)
                """, inputId, USER_ID, LocalDate.parse("2026-09-30"), now, now);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE investment_tactical_overlay_inputs SET active = false WHERE id = ?", inputId))
                .isInstanceOf(UncategorizedSQLException.class)
                .satisfies(exception -> assertThat(((UncategorizedSQLException) exception).getSQLException()
                        .getSQLState()).isEqualTo("P0001"));

        var barId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO investment_tactical_overlay_bar_snapshots (
                    id, user_id, ticker, bar_date, source, source_as_of, captured_at,
                    open_price, high_price, low_price, close_price, volume, created_at
                ) VALUES (?, ?, 'AAPL', ?, 'TOSS', ?, ?, 100, 105, 98, 103, 1000, ?)
                """, barId, USER_ID, LocalDate.parse("2026-09-30"), now, now, now);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM investment_tactical_overlay_bar_snapshots WHERE id = ?", barId))
                .isInstanceOf(UncategorizedSQLException.class)
                .satisfies(exception -> assertThat(((UncategorizedSQLException) exception).getSQLException()
                        .getSQLState()).isEqualTo("P0001"));

        var snapshotId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO investment_tactical_overlay_snapshots (
                    id, user_id, snapshot_type, entity_key, ticker, as_of, source_as_of,
                    calculated_at, source, overlay_version, status, reasons, input_refs,
                    properties_hash, payload, created_at
                ) VALUES (?, ?, 'SECURITY', 'AAPL', 'AAPL', ?, ?, ?, 'TOSS', 'TACTICAL_V1',
                          'INSUFFICIENT_HISTORY', '["INSUFFICIENT_HISTORY"]'::jsonb,
                          '[]'::jsonb, repeat('a', 64), '{"trendStage":null}'::jsonb, ?)
                """, snapshotId, USER_ID, LocalDate.parse("2026-09-30"), now, now, now);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE investment_tactical_overlay_snapshots SET status = 'OK' WHERE id = ?", snapshotId))
                .isInstanceOf(UncategorizedSQLException.class)
                .satisfies(exception -> assertThat(((UncategorizedSQLException) exception).getSQLException()
                        .getSQLState()).isEqualTo("P0001"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM investment_tactical_overlay_snapshots
                 WHERE user_id = ? AND overlay_version = 'TACTICAL_V1'
                   AND status = 'INSUFFICIENT_HISTORY'
                """, Integer.class, USER_ID)).isEqualTo(1);
    }

    @Test
    void validatesOnlySupportedAnchorAndEffectVocabulariesAndPositiveRiskValues() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO investment_tactical_overlay_inputs (
                    id, user_id, input_type, entity_key, ticker, effective_date, source,
                    source_as_of, active, overlay_version, payload, created_at
                ) VALUES (?, ?, 'AVWAP_ANCHOR', 'aapl:unknown', 'AAPL', ?, 'USER_INPUT',
                          ?, true, 'TACTICAL_V1', '{"anchorType":"UNKNOWN"}'::jsonb, ?)
                """, UUID.randomUUID(), USER_ID, LocalDate.now(), now, now))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO investment_tactical_overlay_inputs (
                    id, user_id, input_type, entity_key, ticker, effective_date, source,
                    source_as_of, active, overlay_version, payload, created_at
                ) VALUES (?, ?, 'PERFORMANCE_ENTRY', 'decision:test', 'AAPL', ?, 'USER_INPUT',
                          ?, true, 'TACTICAL_V1',
                          '{"entryPrice":10,"initialRiskPrice":11,"quantity":1,"overlayEffect":"ADD"}'::jsonb,
                          ?)
                """, UUID.randomUUID(), USER_ID, LocalDate.now(), now, now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
