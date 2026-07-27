package com.jmj.trade.broker.connection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.UUID;

@Entity
@DynamicUpdate
@Table(name = "broker_connections")
public class BrokerConnection {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "broker_type", nullable = false, length = 40)
    private BrokerType brokerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BrokerConnectionStatus status;

    @Column(name = "credential_ciphertext")
    private byte[] credentialCiphertext;

    @Column(name = "credential_nonce")
    private byte[] credentialNonce;

    @Column(name = "credential_key_version")
    private Integer credentialKeyVersion;

    @Column(name = "credential_revision", nullable = false)
    private long credentialRevision;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected BrokerConnection() {
    }

    private BrokerConnection(UUID id, UUID userId, EncryptedCredentials encrypted, Instant now) {
        this.id = requireId(id, "id");
        this.userId = requireId(userId, "userId");
        this.brokerType = BrokerType.TOSS_INVEST;
        this.status = BrokerConnectionStatus.UNVERIFIED;
        this.credentialRevision = 1;
        this.createdAt = requireInstant(now, "now");
        this.updatedAt = now;
        applyCredentials(encrypted);
    }

    public static BrokerConnection create(
            UUID id,
            UUID userId,
            EncryptedCredentials encrypted,
            Instant now
    ) {
        return new BrokerConnection(id, userId, encrypted, now);
    }

    public void replaceCredentials(EncryptedCredentials encrypted, Instant now) {
        var updatedAt = requireInstant(now, "now");
        requireOpen();
        applyCredentials(encrypted);
        credentialRevision++;
        status = BrokerConnectionStatus.UNVERIFIED;
        lastValidatedAt = null;
        this.updatedAt = updatedAt;
    }

    public void markValidated(long expectedRevision, Instant now) {
        var validatedAt = requireInstant(now, "now");
        requireExpectedRevision(expectedRevision);
        status = BrokerConnectionStatus.ACTIVE;
        lastValidatedAt = validatedAt;
        updatedAt = validatedAt;
    }

    public void markInvalid(long expectedRevision, Instant now) {
        var invalidatedAt = requireInstant(now, "now");
        requireExpectedRevision(expectedRevision);
        status = BrokerConnectionStatus.INVALID;
        lastValidatedAt = invalidatedAt;
        updatedAt = invalidatedAt;
    }

    public void delete(Instant now) {
        var deletedAt = requireInstant(now, "now");
        requireOpen();
        credentialRevision++;
        status = BrokerConnectionStatus.DELETED;
        credentialCiphertext = null;
        credentialNonce = null;
        credentialKeyVersion = null;
        lastValidatedAt = null;
        this.deletedAt = deletedAt;
        updatedAt = deletedAt;
    }

    private void applyCredentials(EncryptedCredentials encrypted) {
        if (encrypted == null) {
            throw new IllegalArgumentException("encrypted credentials are required");
        }
        credentialCiphertext = encrypted.ciphertext();
        credentialNonce = encrypted.nonce();
        credentialKeyVersion = encrypted.keyVersion();
    }

    private void requireExpectedRevision(long expectedRevision) {
        requireOpen();
        if (credentialRevision != expectedRevision) {
            throw new IllegalStateException("credential revision mismatch");
        }
    }

    private void requireOpen() {
        if (status == BrokerConnectionStatus.DELETED) {
            throw new IllegalStateException("deleted broker connection is immutable");
        }
    }

    private static UUID requireId(UUID value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    private static Instant requireInstant(Instant value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public BrokerType getBrokerType() {
        return brokerType;
    }

    public BrokerConnectionStatus getStatus() {
        return status;
    }

    public EncryptedCredentials getEncryptedCredentials() {
        if (credentialCiphertext == null) {
            return null;
        }
        return new EncryptedCredentials(credentialCiphertext, credentialNonce, credentialKeyVersion);
    }

    public long getCredentialRevision() {
        return credentialRevision;
    }

    public Instant getLastValidatedAt() {
        return lastValidatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public long getVersion() {
        return version;
    }
}
