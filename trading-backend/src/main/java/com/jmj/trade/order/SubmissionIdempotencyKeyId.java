package com.jmj.trade.order;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class SubmissionIdempotencyKeyId implements Serializable {

    private UUID brokerAccountId;
    private String clientOrderId;

    protected SubmissionIdempotencyKeyId() {
    }

    public SubmissionIdempotencyKeyId(UUID brokerAccountId, String clientOrderId) {
        this.brokerAccountId = brokerAccountId;
        this.clientOrderId = clientOrderId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SubmissionIdempotencyKeyId that)) {
            return false;
        }
        return Objects.equals(brokerAccountId, that.brokerAccountId)
                && Objects.equals(clientOrderId, that.clientOrderId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(brokerAccountId, clientOrderId);
    }
}
