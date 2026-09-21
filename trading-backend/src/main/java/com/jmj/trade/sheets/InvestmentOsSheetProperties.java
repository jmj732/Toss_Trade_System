package com.jmj.trade.sheets;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

@ConfigurationProperties("investment-os.sheet")
public record InvestmentOsSheetProperties(
        boolean enabled,
        String spreadsheetId,
        UUID userId,
        UUID connectionId,
        String accountLabel,
        Duration interval,
        Duration initialDelay,
        Duration lockTtl
) {
    public InvestmentOsSheetProperties(
            boolean enabled,
            String spreadsheetId,
            UUID userId,
            UUID connectionId,
            Duration interval,
            Duration initialDelay,
            Duration lockTtl
    ) {
        this(enabled, spreadsheetId, userId, connectionId, InvestmentOsSheetModel.ACCOUNT_1,
                interval, initialDelay, lockTtl);
    }

    @ConstructorBinding
    public InvestmentOsSheetProperties {
        if (enabled) {
            if (spreadsheetId == null || spreadsheetId.isBlank()) {
                throw new IllegalArgumentException("spreadsheetId is required when Sheet sync is enabled");
            }
            Objects.requireNonNull(userId, "userId is required when Sheet sync is enabled");
            Objects.requireNonNull(connectionId, "connectionId is required when Sheet sync is enabled");
        }
        accountLabel = normalizeAccountLabel(accountLabel);
        interval = interval == null ? Duration.ofMinutes(5) : interval;
        initialDelay = initialDelay == null ? Duration.ofMinutes(1) : initialDelay;
        lockTtl = lockTtl == null ? Duration.ofMinutes(10) : lockTtl;
        if (interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("interval must be positive");
        if (initialDelay.isNegative()) throw new IllegalArgumentException("initialDelay must not be negative");
        if (lockTtl.isZero() || lockTtl.isNegative()) throw new IllegalArgumentException("lockTtl must be positive");
    }

    private static String normalizeAccountLabel(String value) {
        var normalized = value == null || value.isBlank()
                ? InvestmentOsSheetModel.ACCOUNT_1
                : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!InvestmentOsSheetModel.ACCOUNT_1.equals(normalized)) {
            throw new IllegalArgumentException("Toss Sheet sync accountLabel must be ACCOUNT_1");
        }
        return normalized;
    }
}
