ALTER TABLE order_intents
    ADD CONSTRAINT uq_order_intent_workflow_owner UNIQUE (id, user_id);

CREATE TABLE paper_order_workflow_commands (
    user_id UUID NOT NULL REFERENCES users (id),
    idempotency_key VARCHAR(80) NOT NULL,
    order_intent_id UUID NOT NULL,
    action VARCHAR(10) NOT NULL CHECK (action IN ('PROPOSE', 'APPROVE', 'CANCEL')),
    channel VARCHAR(10) NOT NULL CHECK (channel IN ('WEB', 'MESSENGER', 'API')),
    actor VARCHAR(200) NOT NULL,
    request_fingerprint VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, idempotency_key),
    CONSTRAINT fk_paper_workflow_intent_owner
        FOREIGN KEY (order_intent_id, user_id)
        REFERENCES order_intents (id, user_id)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX ix_paper_workflow_commands_intent
    ON paper_order_workflow_commands (order_intent_id, created_at);

CREATE FUNCTION reject_paper_order_workflow_command_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'paper order workflow commands are append-only';
END;
$$;

CREATE TRIGGER trg_reject_paper_order_workflow_command_change
BEFORE UPDATE OR DELETE ON paper_order_workflow_commands
FOR EACH ROW
EXECUTE FUNCTION reject_paper_order_workflow_command_change();
