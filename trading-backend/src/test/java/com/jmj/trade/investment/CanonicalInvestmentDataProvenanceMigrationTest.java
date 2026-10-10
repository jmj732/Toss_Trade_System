package com.jmj.trade.investment;

import com.jmj.trade.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalInvestmentDataProvenanceMigrationTest extends PostgresIntegrationTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 10, 3, 10, 0, 0, 0, ZoneOffset.UTC);

    private Flyway flyway;

    @BeforeEach
    void prepareCleanDatabase() {
        flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
    }

    @Test
    void v53PreservesPopulatedV52SnapshotsAndTheirAppendOnlyTriggers() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("52"))
                .load()
                .migrate();

        var userId = UUID.randomUUID();
        var inputId = UUID.randomUUID();
        var priceId = UUID.randomUUID();
        var fundamentalId = UUID.randomUUID();
        var consensusId = UUID.randomUUID();
        var securityId = UUID.randomUUID();
        execute("INSERT INTO users (id) VALUES (?)", userId);
        execute("""
                INSERT INTO analysis_input_snapshots (
                    id, user_id, symbol, schema_version, payload, payload_hash, collected_at, created_at
                ) VALUES (?, ?, 'AVT', '1', '{}'::jsonb, ?, ?, ?)
                """, inputId, userId, "a".repeat(64), NOW, NOW);
        execute("""
                INSERT INTO investment_price_snapshots (
                    id, user_id, input_snapshot_id, ticker, as_of, session, latest_price,
                    latest_price_as_of, regular_close, regular_close_as_of, source, observed_at
                ) VALUES (?, ?, ?, 'AVT', ?, 'REGULAR_CLOSE', 101.25, ?, 100.50, ?, 'TOSS', ?)
                """, priceId, userId, inputId, NOW, NOW, NOW, NOW);
        execute("""
                INSERT INTO fundamental_snapshots (
                    id, user_id, input_snapshot_id, ticker, fiscal_period, fiscal_year,
                    fiscal_period_code, reported_at, as_of, source, market_cap, market_cap_as_of,
                    enterprise_value, enterprise_value_as_of, enterprise_value_source, cash, debt,
                    diluted_shares, diluted_shares_basis, revenue_ttm, revenue_growth_yoy,
                    ebitda_ttm, eps, fcf_ttm, created_at
                ) VALUES (?, ?, ?, 'AVT', '2025-12-31', '2025', 'FY', ?, ?, 'SEC',
                    150000, ?, 160000, ?, 'MARKET_CAP_PLUS_LATEST_DEBT_MINUS_LATEST_CASH',
                    1000, 2000, 5000, 'WEIGHTED_AVERAGE_Q', 25000, 0.12, 4000, 2.5, 3000, ?)
                """, fundamentalId, userId, inputId, NOW, NOW, NOW, NOW, NOW);
        execute("""
                INSERT INTO consensus_snapshots (
                    id, user_id, input_snapshot_id, ticker, as_of, horizon, revenue_consensus,
                    eps_consensus, ebitda_consensus, fcf_consensus, source, created_at
                ) VALUES (?, ?, ?, 'AVT', ?, '2027-12-31', 30000, 3.25, 5000, 4000, 'ALPHA_VANTAGE', ?)
                """, consensusId, userId, inputId, NOW, NOW);
        execute("""
                INSERT INTO investment_security_snapshots (id, user_id, ticker, as_of, payload, created_at)
                VALUES (?, ?, 'AVT', ?, '{"price":{"regularClose":100.50}}'::jsonb, ?)
                """, securityId, userId, NOW, NOW);

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("52");
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("59");
        var longSourceId = UUID.randomUUID();
        var longEbitdaSource = "SEC_INLINE_XBRL_" + "USD_PER_SHARE_DURATION_ENTITY_CONTEXT_".repeat(8);
        assertThat(longEbitdaSource.length()).isGreaterThan(160);
        execute("""
                INSERT INTO fundamental_snapshots (
                    id, user_id, input_snapshot_id, ticker, fiscal_period, reported_at, as_of,
                    source, ebitda_ttm_source, created_at
                ) VALUES (?, ?, ?, 'AVT', 'FY2025', ?, ?, 'SEC_INLINE_XBRL', ?, ?)
                """, longSourceId, userId, inputId, NOW, NOW, longEbitdaSource, NOW);

        assertThat(queryLong("SELECT count(*) FROM investment_price_snapshots WHERE id = ?", priceId)).isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM fundamental_snapshots WHERE id = ?", fundamentalId)).isEqualTo(1);
        assertThat(queryString("SELECT ebitda_ttm_source FROM fundamental_snapshots WHERE id = ?", longSourceId))
                .isEqualTo(longEbitdaSource);
        assertThat(queryLong("SELECT count(*) FROM consensus_snapshots WHERE id = ?", consensusId)).isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM investment_security_snapshots WHERE id = ?", securityId)).isEqualTo(1);
        assertThat(queryString("SELECT source FROM investment_price_snapshots WHERE id = ?", priceId)).isEqualTo("TOSS");
        assertThat(queryString("SELECT payload->'price'->>'regularClose' FROM investment_security_snapshots WHERE id = ?",
                securityId)).isEqualTo("100.50");
        assertThat(queryString("SELECT estimate_type FROM consensus_snapshots WHERE id = ?", consensusId))
                .isEqualTo("ANNUAL");
        assertThat(queryString("SELECT estimate_label FROM consensus_snapshots WHERE id = ?", consensusId)).isNull();
        assertThat(queryString("SELECT period_end::text FROM consensus_snapshots WHERE id = ?", consensusId)).isNull();
        assertThat(queryLong("SELECT count(*) FROM fundamental_snapshots WHERE id = ? AND market_cap = 150000"
                + " AND enterprise_value = 160000 AND diluted_shares = 5000", fundamentalId)).isEqualTo(1);

        assertThatThrownBy(() -> execute("UPDATE investment_price_snapshots SET source = 'SEC' WHERE id = ?", priceId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("DELETE FROM fundamental_snapshots WHERE id = ?", fundamentalId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("UPDATE consensus_snapshots SET source = 'FMP' WHERE id = ?", consensusId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("DELETE FROM investment_security_snapshots WHERE id = ?", securityId))
                .isInstanceOf(SQLException.class);
    }

    private long queryLong(String sql, UUID id) {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String queryString(String sql, UUID id) {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void execute(String sql, Object... args) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement(sql)) {
            for (var index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            statement.execute();
        }
    }
}
