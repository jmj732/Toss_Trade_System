package com.jmj.trade.monitoring;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TradingBackendApplication.class)
class MonitoringStateSchemaIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TRANSITION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MonitoringWatchlistService watchlist;

    @Autowired
    private MonitoringMarketSeriesStore marketSeries;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE monitoring_event_cursors, monitoring_evaluation_cursors, monitoring_state_history, monitoring_current_states, monitoring_watchlist, users CASCADE");
        jdbc.update("INSERT INTO users (id) VALUES (?)", USER_ID);
    }

    @Test
    void storesWatchlistLevelsAndAppendOnlyTimestampedStateEvidence() {
        jdbc.update("""
                INSERT INTO monitoring_watchlist (id, user_id, symbol, levels, status, evidence,
                                                  observed_at, created_at, updated_at)
                VALUES (?, ?, 'ONTO', '{"prepare":{"min":295,"max":302},"confirm":{"min":306,"max":310},"pullback":{"min":262,"max":270},"invalidate":{"min":240,"max":250}}'::jsonb,
                        'WATCH', '{}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), USER_ID);
        jdbc.update("""
                INSERT INTO monitoring_current_states (user_id, scope, subject_key, state,
                                                        evidence, observed_at, updated_at)
                VALUES (?, 'WATCHLIST', 'ONTO', 'WATCH', '{"price":280,"source":"fake"}'::jsonb,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, USER_ID);
        jdbc.update("""
                INSERT INTO monitoring_state_history (id, user_id, scope, subject_key,
                    previous_state, new_state, source_type, source_event_id, evidence,
                    observed_at, created_at)
                VALUES (?, ?, 'WATCHLIST', 'ONTO', NULL, 'WATCH', 'EVALUATION', NULL,
                        '{"price":280,"source":"fake"}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, TRANSITION_ID, USER_ID);

        assertThat(jdbc.queryForObject("SELECT symbol FROM monitoring_watchlist WHERE user_id = ?",
                String.class, USER_ID)).isEqualTo("ONTO");
        assertThat(jdbc.queryForObject("SELECT evidence->>'source' FROM monitoring_current_states " +
                "WHERE user_id = ? AND scope = 'WATCHLIST' AND subject_key = 'ONTO'",
                String.class, USER_ID)).isEqualTo("fake");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM monitoring_state_history WHERE id = ?", TRANSITION_ID))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring_state_history WHERE id = ?",
                Integer.class, TRANSITION_ID)).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateSourceEventForTheSameScope() {
        var eventId = "sec:0000123456:8-K:2026-09-27";
        insertTransition(UUID.randomUUID(), eventId);
        assertThatThrownBy(() -> insertTransition(UUID.randomUUID(), eventId))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring_state_history " +
                "WHERE user_id = ? AND source_event_id = ?", Integer.class, USER_ID, eventId)).isEqualTo(1);
    }

    @Test
    void recordsConfiguredLevelsAsEvidenceOnInitialWatchlistState() {
        var range = new MonitoringWatchlistService.Range(new BigDecimal("295"), new BigDecimal("302"));

        watchlist.put(USER_ID, "onto", new MonitoringWatchlistService.WatchlistLevels(
                range, range, range, range));

        assertThat(jdbc.queryForObject("""
                SELECT evidence->>'action' FROM monitoring_state_history
                 WHERE user_id = ? AND scope = 'WATCHLIST' AND subject_key = 'ONTO'
                """, String.class, USER_ID)).isEqualTo("CREATE_WATCHLIST");
        assertThat(jdbc.queryForObject("""
                SELECT evidence->'levels'->'prepare'->>'min' FROM monitoring_state_history
                 WHERE user_id = ? AND scope = 'WATCHLIST' AND subject_key = 'ONTO'
                """, String.class, USER_ID)).isEqualTo("295");
        assertThat(jdbc.queryForObject("""
                SELECT evidence->>'asOf' FROM monitoring_current_states
                 WHERE user_id = ? AND scope = 'WATCHLIST' AND subject_key = 'ONTO'
                """, String.class, USER_ID)).isNotBlank();
    }

    @Test
    void staleRatioProviderObservationIsUnknownDuringOpenSession() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO monitoring_market_observations (
                    user_id, metric, source, unit, cadence, value, as_of, collected_at)
                VALUES (?, 'equity.rsp_spy', 'MarketDataAdapter', 'ratio', 'DAILY', 1.2, ?, ?)
                """, USER_ID, now.minusDays(1), now.minusMinutes(31));

        var latest = marketSeries.loadRatios(USER_ID).stream()
                .filter(series -> series.metric().equals("equity.rsp_spy"))
                .flatMap(series -> series.points().stream())
                .findFirst().orElseThrow();

        assertThat(latest.value()).isNull();
    }

    private void insertTransition(UUID id, String sourceEventId) {
        jdbc.update("""
                INSERT INTO monitoring_state_history (id, user_id, scope, subject_key,
                    previous_state, new_state, source_type, source_event_id, evidence,
                    observed_at, created_at)
                VALUES (?, ?, 'EVENT', 'ONTO', NULL, 'THESIS_RECHECK_REQUIRED', 'SEC', ?,
                        '{"official":true}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, USER_ID, sourceEventId);
    }
}
