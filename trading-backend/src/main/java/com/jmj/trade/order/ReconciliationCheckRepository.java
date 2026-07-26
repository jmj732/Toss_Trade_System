package com.jmj.trade.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReconciliationCheckRepository extends JpaRepository<ReconciliationCheck, UUID> {
    Optional<ReconciliationCheck> findTopBySubmissionAttemptIdOrderByCheckNumberDesc(UUID submissionAttemptId);
}
