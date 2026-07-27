package com.jmj.trade.broker;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public class BrokerException extends RuntimeException {

    private final BrokerErrorCategory category;
    private final Integer httpStatus;
    private final String brokerErrorCode;
    private final String requestId;
    private final Duration retryAfter;
    private final boolean retriable;

    public BrokerException(
            BrokerErrorCategory category,
            Integer httpStatus,
            String brokerErrorCode,
            String requestId,
            Duration retryAfter,
            boolean retriable,
            String message) {
        super(BrokerPreconditions.nonBlank(message, "message"));
        this.category = Objects.requireNonNull(category, "category");
        if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("httpStatus must be between 100 and 599 when present");
        }
        this.httpStatus = httpStatus;
        this.brokerErrorCode = BrokerPreconditions.nullableNonBlank(brokerErrorCode, "brokerErrorCode");
        this.requestId = BrokerPreconditions.nullableNonBlank(requestId, "requestId");
        this.retryAfter = retryAfter == null ? null : BrokerPreconditions.nonNegative(retryAfter, "retryAfter");
        this.retriable = retriable;
    }

    public BrokerErrorCategory category() {
        return category;
    }

    public Optional<Integer> httpStatus() {
        return Optional.ofNullable(httpStatus);
    }

    public Optional<String> brokerErrorCode() {
        return Optional.ofNullable(brokerErrorCode);
    }

    public Optional<String> requestId() {
        return Optional.ofNullable(requestId);
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    public boolean isRetriable() {
        return retriable;
    }
}
