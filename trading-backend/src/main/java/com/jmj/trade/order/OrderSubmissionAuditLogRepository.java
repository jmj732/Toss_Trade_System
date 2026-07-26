package com.jmj.trade.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrderSubmissionAuditLogRepository extends JpaRepository<OrderSubmissionAuditLog, UUID> {
}
