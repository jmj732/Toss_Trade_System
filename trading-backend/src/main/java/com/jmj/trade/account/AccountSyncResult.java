package com.jmj.trade.account;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccountSyncResult(UUID runId, Instant completedAt) {

    public AccountSyncResult {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(completedAt, "completedAt");
    }
}
