package com.jmj.trade.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SubmissionAttemptRepository extends JpaRepository<SubmissionAttempt, UUID> {
    Optional<SubmissionAttempt> findTopByOrderIntentIdAndClientOrderIdOrderByAttemptNumberDesc(
            UUID orderIntentId,
            String clientOrderId);

    /**
     * 운영자 조정 진입점에서 attempt 를 id 로 로드한다. 사용자 소유 스코프 조회가 아닌 운영자
     * 행위이므로 userId 스코프를 걸지 않는다. 단일 인자 {@code findById} 를 얼린 목록
     * ({@code ModuleBoundaryTest}) 밖에서 재사용하지 않으려고 별도 이름의 명시 쿼리로 둔다.
     */
    @Query("select a from SubmissionAttempt a where a.id = :id")
    Optional<SubmissionAttempt> loadForReconciliation(@Param("id") UUID id);
}
