package com.jmj.trade.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/**
 * Read-through view of the portfolio: synchronizes the account from the broker before every
 * user-facing read, then rereads the persisted snapshot. The configured snapshot max-age is used
 * only to classify a fallback as stale; it never suppresses a live read.
 *
 * <p>The refresh is best effort. A broker outage, a concurrent sync, or a rotated credential
 * degrades to the last usable snapshot, which {@link PortfolioReadService} already flags as stale;
 * it never turns a readable portfolio into a failed request. {@code AccountSyncService} only exists
 * when the credential vault is enabled, so without it this is a plain snapshot read.
 */
@Service
public final class FreshPortfolioReadService {

    private static final Logger LOG = LoggerFactory.getLogger(FreshPortfolioReadService.class);
    private static final String OPERATION = "portfolio_read_through_sync";

    private final PortfolioReadService reads;
    private final ObjectProvider<AccountSyncService> syncs;
    private final ConcurrentHashMap<SyncKey, CompletableFuture<SyncOutcome>> inFlight =
            new ConcurrentHashMap<>();

    @Autowired
    public FreshPortfolioReadService(PortfolioReadService reads, ObjectProvider<AccountSyncService> syncs) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.syncs = Objects.requireNonNull(syncs, "syncs");
    }

    public PortfolioReadService.PortfolioView read(UUID userId, UUID connectionId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(connectionId, "connectionId");
        var sync = syncs.getIfAvailable();
        if (sync == null) {
            return reads.read(userId, connectionId);
        }

        var outcome = refresh(sync, userId, connectionId);
        if (outcome.succeeded()) {
            return reads.read(userId, connectionId);
        }

        return degradedFallback(userId, connectionId, outcome);
    }

    private PortfolioReadService.PortfolioView degradedFallback(
            UUID userId,
            UUID connectionId,
            SyncOutcome outcome
    ) {
        var current = reads.read(userId, connectionId);
        if (current.stale()) {
            return current;
        }
        return new PortfolioReadService.PortfolioView(
                current.syncRunId(),
                current.completedAt(),
                true,
                outcome.reason(),
                current.partial(),
                current.missingSections(),
                current.unknownFields(),
                current.account(),
                current.positions(),
                current.buyingPower());
    }

    private SyncOutcome refresh(AccountSyncService sync, UUID userId, UUID connectionId) {
        var key = new SyncKey(userId, connectionId);
        var candidate = new CompletableFuture<SyncOutcome>();
        var shared = inFlight.putIfAbsent(key, candidate);
        if (shared != null) {
            return shared.join();
        }

        try {
            sync.sync(userId, connectionId);
            candidate.complete(SyncOutcome.SUCCESS);
        } catch (RuntimeException exception) {
            LOG.atInfo()
                    .addKeyValue("operation", OPERATION)
                    .addKeyValue("outcome", "failure")
                    .addKeyValue("connection_id", connectionId)
                    .addKeyValue("error_type", exception.getClass().getSimpleName())
                    .log("portfolio read-through sync failed; serving the last usable snapshot");
            candidate.complete(SyncOutcome.failure());
        } finally {
            inFlight.remove(key, candidate);
        }
        return candidate.join();
    }

    private record SyncKey(UUID userId, UUID connectionId) {
    }

    private record SyncOutcome(boolean succeeded, String reason) {

        private static final SyncOutcome SUCCESS = new SyncOutcome(true, null);

        private static SyncOutcome failure() {
            return new SyncOutcome(false, "LIVE_SYNC_FAILED");
        }
    }
}
