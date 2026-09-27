CREATE TABLE telegram_notification_deliveries (
    outbox_event_id UUID PRIMARY KEY REFERENCES notification_outbox_events (id),
    user_id UUID NOT NULL REFERENCES users (id),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'SENT')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    last_error VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_telegram_notification_delivery_due
    ON telegram_notification_deliveries (user_id, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'IN_FLIGHT');
