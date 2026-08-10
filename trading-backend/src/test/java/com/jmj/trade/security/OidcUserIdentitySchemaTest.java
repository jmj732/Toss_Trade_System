package com.jmj.trade.security;

import com.jmj.trade.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OidcUserIdentitySchemaTest extends PostgresIntegrationTest {

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
    void extendsUsersForUniqueIssuerSubjectMappingWithoutBreakingLegacyUsers() throws Exception {
        flyway.migrate();

        var legacyId = UUID.randomUUID();
        execute("INSERT INTO users (id) VALUES (?)", legacyId);
        var mappedId = UUID.randomUUID();
        execute("""
                INSERT INTO users (id, oidc_issuer, oidc_subject)
                VALUES (?, 'https://issuer.example', 'subject-1')
                """, mappedId);

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("40");
        assertThat(queryId("https://issuer.example", "subject-1")).isEqualTo(mappedId);
    }

    private UUID queryId(String issuer, String subject) throws Exception {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement("""
                     SELECT id FROM users WHERE oidc_issuer = ? AND oidc_subject = ?
                     """)) {
            statement.setString(1, issuer);
            statement.setString(2, subject);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getObject("id", UUID.class);
            }
        }
    }

    private void execute(String sql, Object... args) throws Exception {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement(sql)) {
            for (var i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.execute();
        }
    }
}
