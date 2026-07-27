package com.jmj.trade.broker.connection;

public record EncryptedCredentials(byte[] ciphertext, byte[] nonce, int keyVersion) {

    public EncryptedCredentials {
        if (ciphertext == null || ciphertext.length <= 16) {
            throw new IllegalArgumentException("ciphertext must be longer than authentication tag");
        }
        if (nonce == null || nonce.length != 12) {
            throw new IllegalArgumentException("nonce must be 12 bytes");
        }
        if (keyVersion <= 0) {
            throw new IllegalArgumentException("keyVersion must be positive");
        }
        ciphertext = ciphertext.clone();
        nonce = nonce.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }
}
