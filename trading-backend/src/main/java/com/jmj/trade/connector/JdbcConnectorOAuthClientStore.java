package com.jmj.trade.connector;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;

final class JdbcConnectorOAuthClientStore implements ConnectorOAuthClientStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    JdbcConnectorOAuthClientStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void save(ConnectorMcpOAuthService.RegisteredClient client) {
        jdbc.update("INSERT INTO connector_oauth_clients (client_id, redirect_uris) VALUES (?, ?)",
                client.clientId(), mapper.writeValueAsString(client.redirectUris()));
    }

    @Override
    public ConnectorMcpOAuthService.RegisteredClient find(String clientId) {
        return jdbc.query("SELECT client_id, redirect_uris FROM connector_oauth_clients WHERE client_id = ?",
                (rs, row) -> new ConnectorMcpOAuthService.RegisteredClient(rs.getString("client_id"),
                        Arrays.asList(mapper.readValue(rs.getString("redirect_uris"), String[].class))),
                clientId).stream().findFirst().orElse(null);
    }
}
