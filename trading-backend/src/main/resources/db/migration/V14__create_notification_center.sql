CREATE TABLE notification_outbox_events (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    event_type VARCHAR(60) NOT NULL,
    source_id UUID NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    CONSTRAINT uq_notification_outbox_event_source UNIQUE (event_type, source_id)
);

CREATE INDEX ix_notification_outbox_unprocessed
    ON notification_outbox_events (created_at, id)
    WHERE processed_at IS NULL;

CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    outbox_event_id UUID NOT NULL UNIQUE REFERENCES notification_outbox_events (id),
    user_id UUID NOT NULL REFERENCES users (id),
    type VARCHAR(60) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    source_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ
);

CREATE INDEX ix_notifications_user_list
    ON notifications (user_id, created_at DESC, id DESC);

CREATE INDEX ix_notifications_user_unread
    ON notifications (user_id, created_at DESC)
    WHERE read_at IS NULL;
