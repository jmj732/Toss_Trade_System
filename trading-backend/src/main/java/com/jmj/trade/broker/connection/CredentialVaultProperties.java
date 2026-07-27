package com.jmj.trade.broker.connection;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties("broker.credentials")
public record CredentialVaultProperties(
        boolean enabled,
        int activeKeyVersion,
        Map<Integer, String> keys
) {

    public CredentialVaultProperties {
        keys = keys == null ? Map.of() : Map.copyOf(keys);
    }
}
