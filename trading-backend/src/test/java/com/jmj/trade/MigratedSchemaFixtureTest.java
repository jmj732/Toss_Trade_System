package com.jmj.trade;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class MigratedSchemaFixtureTest extends PostgresIntegrationTest {
    @Test
    void restoresCleanSchemaDataAndOriginalMigrationHistoryAfterMutations() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        var first = freshMigratedSchema();
        first.validate();
        var history = jdbc.queryForList("SELECT version, checksum, installed_on FROM flyway_schema_history ORDER BY installed_rank");
        var user = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id) VALUES (?)", user);
        jdbc.execute("ALTER TABLE users ADD COLUMN ci_fixture_probe INTEGER");
        jdbc.execute("CREATE TABLE ci_fixture_extra (id INTEGER)");

        var restored = freshMigratedSchema();
        restored.validate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?", Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='users' AND column_name='ci_fixture_probe'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='ci_fixture_extra'", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT version, checksum, installed_on FROM flyway_schema_history ORDER BY installed_rank")).isEqualTo(history);
    }
}
