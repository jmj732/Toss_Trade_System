package com.jmj.trade.connector;

interface ConnectorOAuthClientStore {
    void save(ConnectorMcpOAuthService.RegisteredClient client);

    ConnectorMcpOAuthService.RegisteredClient find(String clientId);
}
