package com.jmj.trade.investment;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TradingBackendApplication.class)
class InvestmentOnboardingRegistrationIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InvestmentOnboardingService onboarding;

    @Autowired
    private com.jmj.trade.monitoring.MonitoringWatchlistService watchlist;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE monitoring_state_history, monitoring_current_states, monitoring_watchlist, users CASCADE");
        jdbc.update("INSERT INTO users (id) VALUES (?)", USER_ID);
    }

    @Test
    void registrationIsIdempotentAuditedAndDoesNotInventPriceLevels() {
        var created = onboarding.register(USER_ID, "newt");
        assertThat(created.ticker()).isEqualTo("NEWT");
        assertThat(created.registered()).isTrue();
        assertThat(created.alreadyRegistered()).isFalse();
        assertThat(created.status()).isEqualTo("WATCH");
        var registeredEntries = watchlist.list(USER_ID);
        var registeredEntry = registeredEntries.getFirst();
        assertThat(registeredEntries).singleElement().satisfies(entry -> {
            assertThat(entry.symbol()).isEqualTo("NEWT");
            assertThat(entry.levels()).isNotNull();
            assertThat(entry.levels().prepare()).isNull();
            assertThat(entry.levels().confirm()).isNull();
            assertThat(entry.levels().pullback()).isNull();
            assertThat(entry.levels().invalidate()).isNull();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring_state_history " +
                "WHERE user_id = ? AND scope = 'WATCHLIST' AND subject_key = 'NEWT'",
                Integer.class, USER_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT evidence->>'action' FROM monitoring_state_history
                 WHERE user_id = ? AND scope = 'WATCHLIST' AND subject_key = 'NEWT'
        """, String.class, USER_ID)).isEqualTo("REGISTER_ONBOARDING");
        assertThat(jdbc.queryForObject("""
                SELECT source_type FROM monitoring_state_history
                 WHERE user_id = ? AND scope = 'WATCHLIST' AND subject_key = 'NEWT'
                """, String.class, USER_ID)).isEqualTo("ONBOARDING");
        assertThat(jdbc.queryForObject("""
                SELECT source_event_id FROM monitoring_state_history
                 WHERE user_id = ? AND scope = 'WATCHLIST' AND subject_key = 'NEWT'
                """, String.class, USER_ID)).isEqualTo(registeredEntry.id().toString());
        assertThat(jdbc.queryForObject("""
                SELECT levels->'prepare' IS NULL OR levels->'prepare' = 'null'::jsonb
                  FROM monitoring_watchlist WHERE user_id = ? AND symbol = 'NEWT'
                """, Boolean.class, USER_ID)).isTrue();

        jdbc.update("""
                UPDATE monitoring_watchlist
                   SET status = 'PREPARE',
                       levels = '{"prepare":{"min":80,"max":81},"confirm":{"min":82,"max":83},
                                  "pullback":{"min":78,"max":79},"invalidate":{"min":75,"max":76}}'::jsonb,
                       evidence = '{"kept":"yes"}'::jsonb
                 WHERE user_id = ? AND symbol = 'NEWT'
                """, USER_ID);
        var retry = onboarding.register(USER_ID, "NEWT");

        assertThat(retry.alreadyRegistered()).isTrue();
        assertThat(retry.status()).isEqualTo("PREPARE");
        assertThat(watchlist.list(USER_ID).getFirst().levels().prepare().min())
                .isEqualByComparingTo("80");
        assertThat(jdbc.queryForObject("SELECT evidence->>'kept' FROM monitoring_watchlist " +
                "WHERE user_id = ? AND symbol = 'NEWT'", String.class, USER_ID)).isEqualTo("yes");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring_state_history " +
                "WHERE user_id = ? AND scope = 'WATCHLIST' AND subject_key = 'NEWT'",
                Integer.class, USER_ID)).isEqualTo(1);
    }

    @Test
    void removeAndReregisterCreatesANewAuditedRegistrationEvent() {
        var first = onboarding.register(USER_ID, "NEWT");
        var firstEntryId = watchlist.list(USER_ID).getFirst().id().toString();
        jdbc.update("""
                DELETE FROM monitoring_current_states
                 WHERE user_id = ? AND scope = 'WATCHLIST' AND subject_key = 'NEWT'
                """, USER_ID);
        jdbc.update("DELETE FROM monitoring_watchlist WHERE user_id = ? AND symbol = 'NEWT'", USER_ID);

        var second = onboarding.register(USER_ID, "NEWT");
        var secondEntryId = watchlist.list(USER_ID).getFirst().id().toString();
        var eventIds = jdbc.queryForList("""
                SELECT source_event_id FROM monitoring_state_history
                 WHERE user_id = ? AND scope = 'WATCHLIST' AND subject_key = 'NEWT'
                   AND source_type = 'ONBOARDING'
                """, String.class, USER_ID);

        assertThat(first.alreadyRegistered()).isFalse();
        assertThat(second.alreadyRegistered()).isFalse();
        assertThat(eventIds).containsExactlyInAnyOrder(firstEntryId, secondEntryId);
        assertThat(eventIds).doesNotHaveDuplicates();
    }
}
