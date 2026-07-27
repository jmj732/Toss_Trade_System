package com.jmj.trade.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubmissionAttemptRepository extends JpaRepository<SubmissionAttempt, UUID> {
    Optional<SubmissionAttempt> findTopByOrderIntentIdAndClientOrderIdOrderByAttemptNumberDesc(
            UUID orderIntentId,
            String clientOrderId);
}
