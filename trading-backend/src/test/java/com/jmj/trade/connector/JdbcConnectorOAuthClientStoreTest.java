package com.jmj.trade.connector;

import com.jmj.trade.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcConnectorOAuthClientStoreTest extends PostgresIntegrationTest {
    @Test
    void registrationSurvivesNewDatabaseConnectionAndStoreInstance() {
        var schema = "oauth_test_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())).execute("CREATE SCHEMA " + schema);
        var url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
        var source = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V43__create_connector_oauth_clients.sql")).execute(source);
        var client = new ConnectorMcpOAuthService.RegisteredClient("mcp_client_" + UUID.randomUUID(),
                List.of("https://chatgpt.com/connector/oauth/test", "http://localhost/callback?x=1&y=2"));
        new JdbcConnectorOAuthClientStore(jdbc, new ObjectMapper()).save(client);

        var reopened = new JdbcConnectorOAuthClientStore(new JdbcTemplate(new DriverManagerDataSource(
                url, POSTGRES.getUsername(), POSTGRES.getPassword())), new ObjectMapper());
        assertThat(reopened.find(client.clientId())).isEqualTo(client);
        assertThat(reopened.find("unknown-client")).isNull();
    }
}
