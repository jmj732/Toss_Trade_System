package com.jmj.trade.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrderSubmissionOutboxEventRepository extends JpaRepository<OrderSubmissionOutboxEvent, UUID> {
}
