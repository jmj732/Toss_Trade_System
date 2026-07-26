package com.jmj.trade.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
public class OrderIntentTransitionService {

    private final OrderIntentRepository orderIntentRepository;
    private final OrderIntentAuditLogRepository auditLogRepository;
    private final OrderIntentOutboxEventRepository outboxEventRepository;

    public OrderIntentTransitionService(
            OrderIntentRepository orderIntentRepository,
            OrderIntentAuditLogRepository auditLogRepository,
            OrderIntentOutboxEventRepository outboxEventRepository
    ) {
        this.orderIntentRepository = orderIntentRepository;
        this.auditLogRepository = auditLogRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional
    public void approve(UUID orderIntentId, String actor) {
        transition(orderIntentId, actor, intent -> intent.approve(), null);
    }

    @Transactional
    public void startRevalidation(UUID orderIntentId, String actor) {
        transition(orderIntentId, actor, intent -> intent.startRevalidation(), null);
    }

    @Transactional
    public void markSubmissionPending(UUID orderIntentId, String actor) {
        transition(orderIntentId, actor, intent -> intent.markSubmissionPending(), null);
    }

    @Transactional
    public void activate(UUID orderIntentId, String actor) {
        transition(orderIntentId, actor, intent -> intent.activate(), null);
    }

    @Transactional
    public void terminate(
            UUID orderIntentId,
            OrderIntentStatus terminalStatus,
            String terminalReason,
            Instant terminalAt,
            BigDecimal finalFilledQuantity,
            String actor
    ) {
        transition(
                orderIntentId,
                actor,
                intent -> intent.terminate(terminalStatus, terminalReason, terminalAt, finalFilledQuantity),
                terminalReason);
    }

    private void transition(
            UUID orderIntentId,
            String actor,
            TransitionCommand command,
            String terminalReason
    ) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }
        var intent = orderIntentRepository.findById(orderIntentId)
                .orElseThrow(() -> new IllegalArgumentException("order intent not found: " + orderIntentId));
        var fromStatus = intent.getStatus();
        command.apply(intent);
        var occurredAt = Instant.now();
        auditLogRepository.save(OrderIntentAuditLog.statusChanged(
                intent.getId(),
                fromStatus,
                intent.getStatus(),
                actor,
                terminalReason,
                occurredAt));
        outboxEventRepository.save(OrderIntentOutboxEvent.statusChanged(
                intent.getId(),
                fromStatus,
                intent.getStatus(),
                actor,
                terminalReason,
                occurredAt));
    }

    @FunctionalInterface
    private interface TransitionCommand {
        void apply(OrderIntent intent);
    }
}
