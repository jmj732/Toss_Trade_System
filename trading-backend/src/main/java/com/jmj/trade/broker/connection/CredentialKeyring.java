package com.jmj.trade.broker.connection;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public final class CredentialKeyring {

    private final int activeVersion;
    private final Map<Integer, byte[]> keys;

    public CredentialKeyring(CredentialVaultProperties properties) {
        if (properties == null || !properties.enabled()) {
            throw new IllegalStateException("credential vault is not enabled");
        }
        if (properties.activeKeyVersion() <= 0) {
            throw new IllegalStateException("active credential key version is required");
        }

        var decoded = new HashMap<Integer, byte[]>();
        properties.keys().forEach((version, value) -> decoded.put(version, decodeKey(version, value)));
        if (!decoded.containsKey(properties.activeKeyVersion())) {
            throw new IllegalStateException("active credential key version is missing");
        }

        this.activeVersion = properties.activeKeyVersion();
        this.keys = Map.copyOf(decoded);
    }

    public int activeVersion() {
        return activeVersion;
    }

    public SecretKey activeKey() {
        return key(activeVersion);
    }

    public SecretKey key(int version) {
        var key = keys.get(version);
        if (key == null) {
            throw new CredentialUnavailableException();
        }
        return new SecretKeySpec(key.clone(), "AES");
    }

    private static byte[] decodeKey(Integer version, String value) {
        if (version == null || version <= 0 || value == null) {
            throw new IllegalStateException("credential key configuration is invalid");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("credential key configuration is invalid");
        }
        if (key.length != 32) {
            throw new IllegalStateException("credential key configuration is invalid");
        }
        return key;
    }
}
