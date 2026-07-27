package com.jmj.trade.broker.connection;

import com.jmj.trade.broker.toss.TossCredentials;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;

public final class CredentialCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int FORMAT_VERSION = 1;
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;
    private static final int MAX_FIELD_BYTES = 4096;

    private final CredentialKeyring keyring;
    private final SecureRandom secureRandom;

    public CredentialCipher(CredentialKeyring keyring, SecureRandom secureRandom) {
        if (keyring == null) {
            throw new IllegalArgumentException("keyring is required");
        }
        if (secureRandom == null) {
            throw new IllegalArgumentException("secureRandom is required");
        }
        this.keyring = keyring;
        this.secureRandom = secureRandom;
    }

    public EncryptedCredentials encrypt(
            UUID connectionId,
            UUID userId,
            BrokerType brokerType,
            long credentialRevision,
            TossCredentials credentials
    ) {
        requireContext(connectionId, userId, brokerType, credentialRevision);
        if (credentials == null) {
            throw new IllegalArgumentException("credentials are required");
        }

        var nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            var keyVersion = keyring.activeVersion();
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keyring.activeKey(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad(connectionId, userId, brokerType, keyVersion, credentialRevision, FORMAT_VERSION));
            return new EncryptedCredentials(cipher.doFinal(payload(credentials)), nonce, keyVersion);
        } catch (GeneralSecurityException | IOException ex) {
            throw new CredentialUnavailableException();
        }
    }

    public TossCredentials decrypt(
            UUID connectionId,
            UUID userId,
            BrokerType brokerType,
            long credentialRevision,
            EncryptedCredentials encrypted
    ) {
        requireContext(connectionId, userId, brokerType, credentialRevision);
        if (encrypted == null) {
            throw new CredentialUnavailableException();
        }

        try {
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keyring.key(encrypted.keyVersion()),
                    new GCMParameterSpec(GCM_TAG_BITS, encrypted.nonce()));
            cipher.updateAAD(aad(connectionId, userId, brokerType, encrypted.keyVersion(),
                    credentialRevision, FORMAT_VERSION));
            return credentials(cipher.doFinal(encrypted.ciphertext()));
        } catch (AEADBadTagException | CredentialUnavailableException ex) {
            throw new CredentialUnavailableException();
        } catch (GeneralSecurityException | IOException | IllegalArgumentException ex) {
            throw new CredentialUnavailableException();
        }
    }

    static byte[] aad(
            UUID connectionId,
            UUID userId,
            BrokerType brokerType,
            int keyVersion,
            long credentialRevision,
            int formatVersion
    ) throws IOException {
        var out = new ByteArrayOutputStream();
        var data = new DataOutputStream(out);
        data.writeLong(connectionId.getMostSignificantBits());
        data.writeLong(connectionId.getLeastSignificantBits());
        data.writeLong(userId.getMostSignificantBits());
        data.writeLong(userId.getLeastSignificantBits());
        data.writeUTF(brokerType.name());
        data.writeInt(keyVersion);
        data.writeLong(credentialRevision);
        data.writeInt(formatVersion);
        data.flush();
        return out.toByteArray();
    }

    private static byte[] payload(TossCredentials credentials) throws IOException {
        var clientId = checkedBytes(credentials.clientId());
        var clientSecret = checkedBytes(credentials.clientSecret());
        var out = new ByteArrayOutputStream();
        var data = new DataOutputStream(out);
        data.writeByte(FORMAT_VERSION);
        data.writeInt(clientId.length);
        data.write(clientId);
        data.writeInt(clientSecret.length);
        data.write(clientSecret);
        data.flush();
        return out.toByteArray();
    }

    private static TossCredentials credentials(byte[] payload) throws IOException {
        var in = new ByteArrayInputStream(payload);
        var data = new DataInputStream(in);
        if (data.readUnsignedByte() != FORMAT_VERSION) {
            throw new IOException("unsupported credential payload");
        }
        var clientId = readField(data);
        var clientSecret = readField(data);
        if (in.available() != 0) {
            throw new IOException("trailing credential payload");
        }
        return new TossCredentials(text(clientId), text(clientSecret));
    }

    private static byte[] readField(DataInputStream data) throws IOException {
        var size = data.readInt();
        if (size < 0 || size > MAX_FIELD_BYTES) {
            throw new IOException("credential payload field size is invalid");
        }
        var bytes = new byte[size];
        try {
            data.readFully(bytes);
        } catch (EOFException ex) {
            throw new IOException("credential payload is truncated");
        }
        return bytes;
    }

    private static String text(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static byte[] checkedBytes(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("credential field must not be blank");
        }
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FIELD_BYTES) {
            throw new IllegalArgumentException("credential field is too long");
        }
        return bytes;
    }

    private static void requireContext(UUID connectionId, UUID userId, BrokerType brokerType, long credentialRevision) {
        if (connectionId == null || userId == null || brokerType == null || credentialRevision <= 0) {
            throw new IllegalArgumentException("credential context is invalid");
        }
    }
}
