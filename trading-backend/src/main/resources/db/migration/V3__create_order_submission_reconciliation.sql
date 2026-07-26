ALTER TABLE order_intents
    ADD CONSTRAINT uq_order_intents_id_broker_account UNIQUE (id, broker_account_id);

ALTER TABLE broker_orders
    ADD COLUMN client_order_id VARCHAR(36),
    ADD COLUMN replaces_broker_order_id UUID,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    ADD CONSTRAINT ck_broker_orders_client_order_id_required
        CHECK (client_order_id IS NOT NULL) NOT VALID,
    ADD CONSTRAINT ck_broker_orders_not_self_replacing CHECK (
        replaces_broker_order_id IS NULL OR replaces_broker_order_id <> id
    ),
    ADD CONSTRAINT ck_broker_orders_status CHECK (status IN (
        'PENDING',
        'PARTIALLY_FILLED',
        'FILLED',
        'CANCELED',
        'REJECTED',
        'REPLACED',
        'CANCELING',
        'REPLACING'
    )) NOT VALID,
    ADD CONSTRAINT uq_broker_orders_id_order_intent UNIQUE (id, order_intent_id),
    ADD CONSTRAINT fk_broker_orders_intent_account
        FOREIGN KEY (order_intent_id, broker_account_id)
        REFERENCES order_intents (id, broker_account_id)
        NOT VALID,
    ADD CONSTRAINT fk_broker_orders_replaces_same_intent
        FOREIGN KEY (replaces_broker_order_id, order_intent_id)
        REFERENCES broker_orders (id, order_intent_id);

CREATE FUNCTION enforce_broker_order_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF (
        NEW.order_intent_id,
        NEW.broker_account_id,
        NEW.broker_order_id,
        NEW.client_order_id,
        NEW.replaces_broker_order_id
    ) IS DISTINCT FROM (
        OLD.order_intent_id,
        OLD.broker_account_id,
        OLD.broker_order_id,
        OLD.client_order_id,
        OLD.replaces_broker_order_id
    ) THEN
        RAISE EXCEPTION 'broker order identity fields are immutable';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_enforce_broker_order_update
BEFORE UPDATE ON broker_orders
FOR EACH ROW
EXECUTE FUNCTION enforce_broker_order_update();

CREATE TABLE submission_idempotency_keys (
    broker_account_id UUID NOT NULL,
    client_order_id VARCHAR(36) NOT NULL,
    order_intent_id UUID NOT NULL,
    request_body_hash VARCHAR(128) NOT NULL,
    idempotency_expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (broker_account_id, client_order_id),
    UNIQUE (
        broker_account_id,
        client_order_id,
        order_intent_id,
        request_body_hash,
        idempotency_expires_at
    ),
    CONSTRAINT ck_submission_idempotency_client_order_id CHECK (
        client_order_id ~ '^[A-Za-z0-9_-]{1,36}$'
    ),
    CONSTRAINT ck_submission_idempotency_exact_expiry CHECK (
        idempotency_expires_at = created_at + INTERVAL '10 minutes'
    ),
    CONSTRAINT fk_submission_idempotency_intent_account
        FOREIGN KEY (order_intent_id, broker_account_id)
        REFERENCES order_intents (id, broker_account_id)
);

CREATE FUNCTION reject_submission_idempotency_key_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'submission idempotency keys are append-only';
END;
$$;

CREATE TRIGGER trg_reject_submission_idempotency_key_change
BEFORE UPDATE OR DELETE ON submission_idempotency_keys
FOR EACH ROW
EXECUTE FUNCTION reject_submission_idempotency_key_change();

CREATE TABLE submission_attempts (
    id UUID PRIMARY KEY,
    order_intent_id UUID NOT NULL,
    broker_account_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL CHECK (attempt_number >= 1),
    internal_idempotency_key VARCHAR(128) NOT NULL,
    client_order_id VARCHAR(36) NOT NULL,
    request_body_hash VARCHAR(128) NOT NULL,
    retry_of_attempt_id UUID REFERENCES submission_attempts (id),
    confirmed_broker_order_id UUID,
    status VARCHAR(40) NOT NULL CHECK (status IN (
        'CREATED',
        'DISPATCHING',
        'ACKNOWLEDGED',
        'BROKER_REJECTED',
        'UNKNOWN',
        'RECONCILING',
        'RECONCILED_NO_MATCH',
        'RECONCILIATION_FAILED'
    )),
    dispatch_evidence JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    idempotency_expires_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    last_reconciliation_check_number INTEGER NOT NULL DEFAULT 0
        CHECK (last_reconciliation_check_number >= 0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (id, order_intent_id),
    UNIQUE (order_intent_id, attempt_number),
    UNIQUE (internal_idempotency_key),
    CONSTRAINT ck_submission_attempt_client_order_id CHECK (
        client_order_id ~ '^[A-Za-z0-9_-]{1,36}$'
    ),
    CONSTRAINT ck_submission_attempt_confirmed_broker_order CHECK (
        (status = 'ACKNOWLEDGED' AND confirmed_broker_order_id IS NOT NULL)
        OR (status <> 'ACKNOWLEDGED' AND confirmed_broker_order_id IS NULL)
    ),
    CONSTRAINT fk_submission_attempt_intent_account
        FOREIGN KEY (order_intent_id, broker_account_id)
        REFERENCES order_intents (id, broker_account_id),
    CONSTRAINT fk_submission_attempt_canonical_key
        FOREIGN KEY (
            broker_account_id,
            client_order_id,
            order_intent_id,
            request_body_hash,
            idempotency_expires_at
        )
        REFERENCES submission_idempotency_keys (
            broker_account_id,
            client_order_id,
            order_intent_id,
            request_body_hash,
            idempotency_expires_at
        ),
    CONSTRAINT fk_submission_attempt_confirmed_broker_order
        FOREIGN KEY (confirmed_broker_order_id, order_intent_id)
        REFERENCES broker_orders (id, order_intent_id)
);

CREATE UNIQUE INDEX uq_submission_attempt_retry_parent
    ON submission_attempts (retry_of_attempt_id)
    WHERE retry_of_attempt_id IS NOT NULL;

CREATE TABLE reconciliation_checks (
    id UUID PRIMARY KEY,
    submission_attempt_id UUID NOT NULL REFERENCES submission_attempts (id),
    order_intent_id UUID NOT NULL,
    check_number INTEGER NOT NULL CHECK (check_number >= 1),
    open_orders_complete BOOLEAN NOT NULL,
    closed_orders_complete BOOLEAN NOT NULL,
    closed_window_start TIMESTAMPTZ,
    closed_window_end TIMESTAMPTZ,
    all_pages_read BOOLEAN NOT NULL,
    result_hash VARCHAR(128) NOT NULL,
    matched_broker_order_id UUID,
    decision VARCHAR(40) NOT NULL CHECK (decision IN (
        'BROKER_ORDER_FOUND',
        'RETRY_SAME_KEY_ALLOWED',
        'MANUAL_REVIEW_REQUIRED'
    )),
    checked_at TIMESTAMPTZ NOT NULL,
    UNIQUE (submission_attempt_id, check_number),
    CONSTRAINT fk_reconciliation_attempt_intent
        FOREIGN KEY (submission_attempt_id, order_intent_id)
        REFERENCES submission_attempts (id, order_intent_id),
    CONSTRAINT fk_reconciliation_matched_broker_order
        FOREIGN KEY (matched_broker_order_id, order_intent_id)
        REFERENCES broker_orders (id, order_intent_id),
    CONSTRAINT ck_reconciliation_broker_order_found_match CHECK (
        (decision = 'BROKER_ORDER_FOUND' AND matched_broker_order_id IS NOT NULL)
        OR (decision <> 'BROKER_ORDER_FOUND')
    )
);

CREATE FUNCTION validate_reconciliation_check_sequence(attempt_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    stored_counter INTEGER;
    check_count INTEGER;
    max_check_number INTEGER;
BEGIN
    SELECT last_reconciliation_check_number
      INTO stored_counter
      FROM submission_attempts
     WHERE id = attempt_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'submission attempt % does not exist', attempt_id;
    END IF;

    SELECT COUNT(*)::INTEGER, COALESCE(MAX(check_number), 0)
      INTO check_count, max_check_number
      FROM reconciliation_checks
     WHERE submission_attempt_id = attempt_id;

    IF stored_counter <> max_check_number OR check_count <> max_check_number THEN
        RAISE EXCEPTION
            'reconciliation check sequence mismatch for attempt %: counter %, count %, max %',
            attempt_id, stored_counter, check_count, max_check_number;
    END IF;
END;
$$;

CREATE FUNCTION enforce_reconciliation_check_sequence_from_check()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM validate_reconciliation_check_sequence(NEW.submission_attempt_id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION enforce_reconciliation_check_sequence_from_attempt()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM validate_reconciliation_check_sequence(NEW.id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION enforce_reconciliation_terminal_decision()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    latest_decision VARCHAR(40);
    expected_decision VARCHAR(40);
BEGIN
    PERFORM validate_reconciliation_check_sequence(NEW.id);

    SELECT decision
      INTO latest_decision
      FROM reconciliation_checks
     WHERE submission_attempt_id = NEW.id
     ORDER BY check_number DESC
     LIMIT 1;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'reconciliation terminal transition requires latest check';
    END IF;

    expected_decision := CASE NEW.status
        WHEN 'ACKNOWLEDGED' THEN 'BROKER_ORDER_FOUND'
        WHEN 'RECONCILED_NO_MATCH' THEN 'RETRY_SAME_KEY_ALLOWED'
        WHEN 'RECONCILIATION_FAILED' THEN 'MANUAL_REVIEW_REQUIRED'
    END;

    IF latest_decision IS DISTINCT FROM expected_decision THEN
        RAISE EXCEPTION
            'reconciliation terminal transition % requires latest decision %, got %',
            NEW.status,
            expected_decision,
            latest_decision;
    END IF;

    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_enforce_reconciliation_check_sequence_from_check
AFTER INSERT ON reconciliation_checks
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION enforce_reconciliation_check_sequence_from_check();

CREATE CONSTRAINT TRIGGER trg_enforce_reconciliation_check_sequence_from_attempt
AFTER UPDATE OF last_reconciliation_check_number ON submission_attempts
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION enforce_reconciliation_check_sequence_from_attempt();

CREATE CONSTRAINT TRIGGER trg_enforce_reconciliation_terminal_decision
AFTER UPDATE OF status ON submission_attempts
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
WHEN (
    OLD.status = 'RECONCILING'
    AND NEW.status IN (
        'ACKNOWLEDGED',
        'RECONCILED_NO_MATCH',
        'RECONCILIATION_FAILED'
    )
)
EXECUTE FUNCTION enforce_reconciliation_terminal_decision();

CREATE FUNCTION enforce_reconciliation_check_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    attempt_client_order_id VARCHAR(36);
    attempt_expires_at TIMESTAMPTZ;
    broker_client_order_id VARCHAR(36);
BEGIN
    SELECT client_order_id, idempotency_expires_at
      INTO attempt_client_order_id, attempt_expires_at
      FROM submission_attempts
     WHERE id = NEW.submission_attempt_id
       AND order_intent_id = NEW.order_intent_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'reconciliation check attempt does not match intent';
    END IF;

    IF NEW.decision = 'BROKER_ORDER_FOUND' THEN
        SELECT client_order_id
          INTO broker_client_order_id
          FROM broker_orders
         WHERE id = NEW.matched_broker_order_id
           AND order_intent_id = NEW.order_intent_id;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'matched broker order does not match reconciliation intent';
        END IF;

        IF broker_client_order_id IS DISTINCT FROM attempt_client_order_id THEN
            RAISE EXCEPTION 'matched broker order client_order_id differs from attempt';
        END IF;
    ELSIF NEW.decision = 'RETRY_SAME_KEY_ALLOWED' THEN
        IF NOT (
            NEW.open_orders_complete
            AND NEW.closed_orders_complete
            AND NEW.all_pages_read
            AND NEW.matched_broker_order_id IS NULL
            AND NEW.checked_at < attempt_expires_at
        ) THEN
            RAISE EXCEPTION 'retry same key decision requires complete no-match reconciliation before expiry';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_enforce_reconciliation_check_insert
BEFORE INSERT ON reconciliation_checks
FOR EACH ROW
EXECUTE FUNCTION enforce_reconciliation_check_insert();

CREATE TABLE order_submission_audit_logs (
    id UUID PRIMARY KEY,
    order_intent_id UUID NOT NULL REFERENCES order_intents (id),
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id UUID NOT NULL,
    action VARCHAR(120) NOT NULL,
    actor VARCHAR(200) NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE order_submission_outbox_events (
    id UUID PRIMARY KEY,
    order_intent_id UUID NOT NULL REFERENCES order_intents (id),
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    actor VARCHAR(200) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0)
);

CREATE FUNCTION enforce_submission_attempt_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    parent submission_attempts%ROWTYPE;
    latest_decision VARCHAR(40);
BEGIN
    IF NEW.status <> 'CREATED'
       OR NEW.confirmed_broker_order_id IS NOT NULL
       OR NEW.dispatch_evidence IS NOT NULL
       OR NEW.started_at IS NOT NULL
       OR NEW.finished_at IS NOT NULL
       OR NEW.last_reconciliation_check_number <> 0 THEN
        RAISE EXCEPTION 'submission attempt inserts must start in created state';
    END IF;

    IF NEW.retry_of_attempt_id IS NULL THEN
        IF NEW.attempt_number <> 1 THEN
            RAISE EXCEPTION 'initial submission attempt must be attempt 1';
        END IF;
        RETURN NEW;
    END IF;

    SELECT *
      INTO parent
      FROM submission_attempts
     WHERE id = NEW.retry_of_attempt_id
     FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'retry parent attempt % does not exist', NEW.retry_of_attempt_id;
    END IF;

    IF (
        NEW.order_intent_id,
        NEW.broker_account_id,
        NEW.client_order_id,
        NEW.request_body_hash,
        NEW.idempotency_expires_at
    ) IS DISTINCT FROM (
        parent.order_intent_id,
        parent.broker_account_id,
        parent.client_order_id,
        parent.request_body_hash,
        parent.idempotency_expires_at
    ) THEN
        RAISE EXCEPTION 'retry submission attempt identity differs from parent';
    END IF;

    IF NEW.attempt_number <> parent.attempt_number + 1 THEN
        RAISE EXCEPTION 'retry submission attempt number must follow parent';
    END IF;

    IF parent.status <> 'RECONCILED_NO_MATCH' THEN
        RAISE EXCEPTION 'retry submission attempt requires reconciled no-match parent';
    END IF;

    IF NEW.created_at >= parent.idempotency_expires_at THEN
        RAISE EXCEPTION 'retry submission attempt is outside idempotency window';
    END IF;

    IF parent.finished_at IS NULL OR NEW.created_at < parent.finished_at THEN
        RAISE EXCEPTION 'retry submission attempt must be created after parent completion';
    END IF;

    SELECT decision
      INTO latest_decision
      FROM reconciliation_checks
     WHERE submission_attempt_id = parent.id
     ORDER BY check_number DESC
     LIMIT 1;

    IF latest_decision IS DISTINCT FROM 'RETRY_SAME_KEY_ALLOWED' THEN
        RAISE EXCEPTION 'retry submission attempt requires latest retry-allowed reconciliation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_enforce_submission_attempt_insert
BEFORE INSERT ON submission_attempts
FOR EACH ROW
EXECUTE FUNCTION enforce_submission_attempt_insert();

CREATE FUNCTION enforce_submission_attempt_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    broker_client_order_id VARCHAR(36);
BEGIN
    IF NEW.started_at IS NOT NULL AND NEW.started_at < NEW.created_at THEN
        RAISE EXCEPTION 'submission attempt started_at is before created_at';
    END IF;

    IF NEW.finished_at IS NOT NULL THEN
        IF NEW.started_at IS NULL THEN
            RAISE EXCEPTION 'submission attempt finished_at requires started_at';
        END IF;
        IF NEW.finished_at < NEW.started_at THEN
            RAISE EXCEPTION 'submission attempt finished_at is before started_at';
        END IF;
    END IF;

    IF (
        NEW.order_intent_id,
        NEW.broker_account_id,
        NEW.attempt_number,
        NEW.internal_idempotency_key,
        NEW.client_order_id,
        NEW.request_body_hash,
        NEW.retry_of_attempt_id,
        NEW.created_at,
        NEW.idempotency_expires_at
    ) IS DISTINCT FROM (
        OLD.order_intent_id,
        OLD.broker_account_id,
        OLD.attempt_number,
        OLD.internal_idempotency_key,
        OLD.client_order_id,
        OLD.request_body_hash,
        OLD.retry_of_attempt_id,
        OLD.created_at,
        OLD.idempotency_expires_at
    ) THEN
        RAISE EXCEPTION 'submission attempt identity fields are immutable';
    END IF;

    IF NEW.confirmed_broker_order_id IS DISTINCT FROM OLD.confirmed_broker_order_id
       AND NOT (
           OLD.confirmed_broker_order_id IS NULL
           AND NEW.confirmed_broker_order_id IS NOT NULL
           AND OLD.status <> NEW.status
           AND NEW.status = 'ACKNOWLEDGED'
       ) THEN
        RAISE EXCEPTION 'confirmed broker order may only be set on acknowledgement';
    END IF;

    IF OLD.confirmed_broker_order_id IS NULL
       AND NEW.confirmed_broker_order_id IS NOT NULL
       AND NEW.status = 'ACKNOWLEDGED' THEN
        SELECT client_order_id
          INTO broker_client_order_id
          FROM broker_orders
         WHERE id = NEW.confirmed_broker_order_id
           AND order_intent_id = NEW.order_intent_id;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'confirmed broker order does not match submission attempt intent';
        END IF;

        IF broker_client_order_id IS DISTINCT FROM NEW.client_order_id THEN
            RAISE EXCEPTION 'confirmed broker order client_order_id differs from attempt';
        END IF;
    END IF;

    IF NEW.started_at IS DISTINCT FROM OLD.started_at
       AND NOT (
           OLD.started_at IS NULL
           AND NEW.started_at IS NOT NULL
           AND OLD.status = 'CREATED'
           AND NEW.status = 'DISPATCHING'
       ) THEN
        RAISE EXCEPTION 'submission attempt started_at may only be set on dispatch';
    END IF;

    IF NEW.finished_at IS DISTINCT FROM OLD.finished_at
       AND NOT (
           OLD.finished_at IS NULL
           AND NEW.finished_at IS NOT NULL
           AND OLD.status <> NEW.status
            AND NEW.status IN (
               'ACKNOWLEDGED',
               'BROKER_REJECTED',
               'UNKNOWN'
           )
       )
       AND NOT (
           OLD.finished_at IS NOT NULL
           AND NEW.finished_at IS NOT NULL
           AND NEW.finished_at > OLD.finished_at
           AND OLD.status = 'RECONCILING'
           AND NEW.status IN (
               'ACKNOWLEDGED',
               'RECONCILED_NO_MATCH',
               'RECONCILIATION_FAILED'
           )
       ) THEN
        RAISE EXCEPTION 'submission attempt finished_at may only be set on a finished transition';
    END IF;

    IF OLD.status = 'RECONCILING'
       AND NEW.status IN (
           'ACKNOWLEDGED',
           'RECONCILED_NO_MATCH',
           'RECONCILIATION_FAILED'
       )
       AND NEW.finished_at <= OLD.finished_at THEN
        RAISE EXCEPTION 'reconciliation terminal transition must replace finished_at';
    END IF;

    IF NEW.dispatch_evidence IS DISTINCT FROM OLD.dispatch_evidence
       AND (
           OLD.status = NEW.status
           OR OLD.status IN (
               'ACKNOWLEDGED',
               'BROKER_REJECTED',
               'RECONCILED_NO_MATCH',
               'RECONCILIATION_FAILED'
           )
       ) THEN
        RAISE EXCEPTION 'dispatch evidence may only change during a lifecycle transition';
    END IF;

    IF NEW.status <> OLD.status AND NOT (
        (OLD.status = 'CREATED' AND NEW.status = 'DISPATCHING')
        OR (OLD.status = 'DISPATCHING' AND NEW.status IN (
            'ACKNOWLEDGED',
            'BROKER_REJECTED',
            'UNKNOWN'
        ))
        OR (OLD.status = 'UNKNOWN' AND NEW.status = 'RECONCILING')
        OR (OLD.status = 'RECONCILING' AND NEW.status IN (
            'ACKNOWLEDGED',
            'RECONCILED_NO_MATCH',
            'RECONCILIATION_FAILED'
        ))
    ) THEN
        RAISE EXCEPTION 'invalid submission attempt transition: % -> %', OLD.status, NEW.status;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_enforce_submission_attempt_update
BEFORE UPDATE ON submission_attempts
FOR EACH ROW
EXECUTE FUNCTION enforce_submission_attempt_update();

CREATE FUNCTION reject_reconciliation_check_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'reconciliation checks are append-only';
END;
$$;

CREATE TRIGGER trg_reject_reconciliation_check_change
BEFORE UPDATE OR DELETE ON reconciliation_checks
FOR EACH ROW
EXECUTE FUNCTION reject_reconciliation_check_change();

CREATE FUNCTION reject_order_submission_audit_log_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'order submission audit logs are append-only';
END;
$$;

CREATE TRIGGER trg_reject_order_submission_audit_log_change
BEFORE UPDATE OR DELETE ON order_submission_audit_logs
FOR EACH ROW
EXECUTE FUNCTION reject_order_submission_audit_log_change();

CREATE FUNCTION reject_order_submission_outbox_event_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'order submission outbox events cannot be deleted';
    END IF;

    IF (
        NEW.id,
        NEW.order_intent_id,
        NEW.aggregate_type,
        NEW.aggregate_id,
        NEW.event_type,
        NEW.actor,
        NEW.payload,
        NEW.created_at
    ) IS DISTINCT FROM (
        OLD.id,
        OLD.order_intent_id,
        OLD.aggregate_type,
        OLD.aggregate_id,
        OLD.event_type,
        OLD.actor,
        OLD.payload,
        OLD.created_at
    ) THEN
        RAISE EXCEPTION 'order submission outbox event business fields are immutable';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_reject_order_submission_outbox_event_change
BEFORE UPDATE OR DELETE ON order_submission_outbox_events
FOR EACH ROW
EXECUTE FUNCTION reject_order_submission_outbox_event_change();
