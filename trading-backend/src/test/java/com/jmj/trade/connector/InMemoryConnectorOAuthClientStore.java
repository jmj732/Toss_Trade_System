package com.jmj.trade.connector;

import java.util.concurrent.ConcurrentHashMap;

final class InMemoryConnectorOAuthClientStore implements ConnectorOAuthClientStore {
    private final ConcurrentHashMap<String, ConnectorMcpOAuthService.RegisteredClient> clients = new ConcurrentHashMap<>();

    public void save(ConnectorMcpOAuthService.RegisteredClient client) {
        clients.put(client.clientId(), client);
    }

    public ConnectorMcpOAuthService.RegisteredClient find(String clientId) {
        return clients.get(clientId);
    }
}
