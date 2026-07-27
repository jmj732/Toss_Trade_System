CREATE TABLE event_reviews (
    event_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    broker_connection_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('CONFIRMED', 'HELD', 'IGNORED')),
    version BIGINT NOT NULL CHECK (version > 0),
    reviewed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_event_review_owner
        FOREIGN KEY (user_id, broker_connection_id, event_id)
        REFERENCES intelligence_events (user_id, broker_connection_id, id),
    CONSTRAINT uq_event_review_owner_version
        UNIQUE (user_id, broker_connection_id, event_id, version)
);

CREATE TABLE event_review_commands (
    user_id UUID NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    event_id UUID NOT NULL,
    broker_connection_id UUID NOT NULL,
    target_status VARCHAR(20) NOT NULL
        CHECK (target_status IN ('CONFIRMED', 'HELD', 'IGNORED')),
    expected_version BIGINT NOT NULL CHECK (expected_version >= 0),
    resulting_version BIGINT NOT NULL CHECK (resulting_version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, idempotency_key),
    CONSTRAINT fk_event_review_command_owner
        FOREIGN KEY (user_id, broker_connection_id, event_id)
        REFERENCES intelligence_events (user_id, broker_connection_id, id),
    CONSTRAINT uq_event_review_command_version
        UNIQUE (event_id, resulting_version)
);

CREATE FUNCTION enforce_event_review_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'event reviews cannot be deleted';
    END IF;
    IF NEW.event_id <> OLD.event_id
        OR NEW.user_id <> OLD.user_id
        OR NEW.broker_connection_id <> OLD.broker_connection_id
        OR NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'invalid event review transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_event_review_transition
BEFORE UPDATE OR DELETE ON event_reviews
FOR EACH ROW
EXECUTE FUNCTION enforce_event_review_transition();

CREATE TRIGGER trg_event_review_commands_append_only
BEFORE UPDATE OR DELETE ON event_review_commands
FOR EACH ROW
EXECUTE FUNCTION enforce_intelligence_append_only();
