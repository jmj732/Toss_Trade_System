package com.jmj.trade.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionIdempotencyKeyRepository
        extends JpaRepository<SubmissionIdempotencyKey, SubmissionIdempotencyKeyId> {
}
