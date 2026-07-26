CREATE TABLE broker_accounts (
    id UUID PRIMARY KEY
);

CREATE TABLE order_intents (
    id UUID PRIMARY KEY,
    broker_account_id UUID NOT NULL REFERENCES broker_accounts (id),
    quantity NUMERIC(28, 10) NOT NULL CHECK (quantity > 0),
    status VARCHAR(40) NOT NULL CHECK (status IN (
        'PROPOSED',
        'APPROVED',
        'REVALIDATING',
        'SUBMISSION_PENDING',
        'RECONCILIATION_REQUIRED',
        'MANUAL_REVIEW_REQUIRED',
        'ACTIVE',
        'COMPLETED',
        'PARTIALLY_COMPLETED',
        'CANCELED',
        'REJECTED',
        'EXPIRED',
        'BLOCKED'
    )),
    terminal_reason VARCHAR(80),
    terminal_at TIMESTAMPTZ,
    final_filled_quantity NUMERIC(28, 10),
    remaining_quantity NUMERIC(28, 10),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT ck_order_intent_terminal_fields CHECK (
        (
            status IN (
                'COMPLETED',
                'PARTIALLY_COMPLETED',
                'CANCELED',
                'REJECTED',
                'EXPIRED',
                'BLOCKED'
            )
            AND terminal_reason IS NOT NULL
            AND terminal_reason <> ''
            AND terminal_at IS NOT NULL
            AND final_filled_quantity IS NOT NULL
            AND remaining_quantity IS NOT NULL
        )
        OR
        (
            status NOT IN (
                'COMPLETED',
                'PARTIALLY_COMPLETED',
                'CANCELED',
                'REJECTED',
                'EXPIRED',
                'BLOCKED'
            )
            AND terminal_reason IS NULL
            AND terminal_at IS NULL
            AND final_filled_quantity IS NULL
            AND remaining_quantity IS NULL
        )
    ),
    CONSTRAINT ck_order_intent_terminal_quantities CHECK (
        final_filled_quantity IS NULL
        OR (
            final_filled_quantity >= 0
            AND remaining_quantity >= 0
            AND final_filled_quantity + remaining_quantity = quantity
        )
    ),
    CONSTRAINT ck_order_intent_terminal_quantity_by_status CHECK (
        status NOT IN (
            'COMPLETED',
            'PARTIALLY_COMPLETED',
            'CANCELED',
            'REJECTED',
            'EXPIRED',
            'BLOCKED'
        )
        OR (status = 'COMPLETED'
            AND final_filled_quantity = quantity
            AND remaining_quantity = 0)
        OR (status = 'PARTIALLY_COMPLETED'
            AND final_filled_quantity > 0
            AND final_filled_quantity < quantity
            AND remaining_quantity > 0)
        OR (status IN ('CANCELED', 'REJECTED', 'EXPIRED', 'BLOCKED')
            AND final_filled_quantity = 0
            AND remaining_quantity = quantity)
    )
);

CREATE TABLE broker_orders (
    id UUID PRIMARY KEY,
    order_intent_id UUID NOT NULL REFERENCES order_intents (id),
    broker_account_id UUID NOT NULL REFERENCES broker_accounts (id),
    broker_order_id VARCHAR(200) NOT NULL,
    status VARCHAR(40) NOT NULL,
    UNIQUE (broker_account_id, broker_order_id)
);

CREATE TABLE execution_snapshots (
    id UUID PRIMARY KEY,
    broker_order_id UUID NOT NULL REFERENCES broker_orders (id),
    filled_quantity NUMERIC(28, 10) NOT NULL CHECK (filled_quantity >= 0),
    captured_at TIMESTAMPTZ NOT NULL
);

CREATE FUNCTION enforce_order_intent_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    observed_filled_quantity NUMERIC(28, 10);
BEGIN
    IF OLD.status IN (
        'COMPLETED',
        'PARTIALLY_COMPLETED',
        'CANCELED',
        'REJECTED',
        'EXPIRED',
        'BLOCKED'
    ) AND (
        NEW.status,
        NEW.terminal_reason,
        NEW.terminal_at,
        NEW.final_filled_quantity,
        NEW.remaining_quantity
    ) IS DISTINCT FROM (
        OLD.status,
        OLD.terminal_reason,
        OLD.terminal_at,
        OLD.final_filled_quantity,
        OLD.remaining_quantity
    ) THEN
        RAISE EXCEPTION 'terminal order intent % is immutable', OLD.id;
    END IF;

    IF NEW.status <> OLD.status AND NOT (
        (OLD.status = 'PROPOSED' AND NEW.status IN ('APPROVED', 'CANCELED', 'EXPIRED'))
        OR (OLD.status = 'APPROVED' AND NEW.status IN ('REVALIDATING', 'EXPIRED'))
        OR (OLD.status = 'REVALIDATING' AND NEW.status IN (
            'SUBMISSION_PENDING', 'BLOCKED', 'EXPIRED'
        ))
        OR (OLD.status = 'SUBMISSION_PENDING' AND NEW.status IN (
            'ACTIVE', 'REJECTED', 'RECONCILIATION_REQUIRED'
        ))
        OR (OLD.status = 'RECONCILIATION_REQUIRED' AND NEW.status IN (
            'ACTIVE', 'SUBMISSION_PENDING', 'MANUAL_REVIEW_REQUIRED'
        ))
        OR (OLD.status = 'ACTIVE' AND NEW.status IN (
            'COMPLETED', 'PARTIALLY_COMPLETED', 'CANCELED', 'MANUAL_REVIEW_REQUIRED'
        ))
        OR (OLD.status = 'MANUAL_REVIEW_REQUIRED' AND NEW.status IN (
            'ACTIVE', 'COMPLETED', 'PARTIALLY_COMPLETED', 'CANCELED', 'REJECTED'
        ))
    ) THEN
        RAISE EXCEPTION 'invalid order intent transition: % -> %', OLD.status, NEW.status;
    END IF;

    IF NEW.status IN ('REJECTED', 'EXPIRED', 'BLOCKED')
       AND EXISTS (
           SELECT 1
             FROM broker_orders
            WHERE order_intent_id = NEW.id
       ) THEN
        RAISE EXCEPTION 'pre-acceptance terminal state % is forbidden after broker order confirmation',
            NEW.status;
    END IF;

    IF NEW.status = 'PARTIALLY_COMPLETED' THEN
        IF NOT EXISTS (
            SELECT 1
              FROM broker_orders
             WHERE order_intent_id = NEW.id
        )
        OR EXISTS (
            SELECT 1
              FROM broker_orders
             WHERE order_intent_id = NEW.id
               AND status NOT IN ('FILLED', 'CANCELED', 'REJECTED', 'REPLACED')
        ) THEN
            RAISE EXCEPTION 'partially completed intent % has a fillable broker order', NEW.id;
        END IF;

        SELECT COALESCE(SUM(latest.filled_quantity), 0)
          INTO observed_filled_quantity
          FROM broker_orders bo
          JOIN LATERAL (
              SELECT es.filled_quantity
                FROM execution_snapshots es
               WHERE es.broker_order_id = bo.id
               ORDER BY es.captured_at DESC, es.id DESC
               LIMIT 1
          ) latest ON TRUE
         WHERE bo.order_intent_id = NEW.id;

        IF observed_filled_quantity <> NEW.final_filled_quantity THEN
            RAISE EXCEPTION
                'partially completed intent % filled quantity % differs from execution evidence %',
                NEW.id, NEW.final_filled_quantity, observed_filled_quantity;
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_enforce_order_intent_transition
BEFORE UPDATE ON order_intents
FOR EACH ROW
EXECUTE FUNCTION enforce_order_intent_transition();

CREATE FUNCTION enforce_broker_order_confirmation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    intent_status VARCHAR(40);
BEGIN
    IF TG_OP = 'UPDATE'
       AND NEW.order_intent_id IS DISTINCT FROM OLD.order_intent_id THEN
        RAISE EXCEPTION 'broker order intent relationship is immutable';
    END IF;

    SELECT status
      INTO intent_status
      FROM order_intents
     WHERE id = NEW.order_intent_id
     FOR UPDATE;

    IF intent_status IN ('REJECTED', 'EXPIRED', 'BLOCKED') THEN
        RAISE EXCEPTION
            'broker order confirmation is forbidden for terminal order intent % in state %',
            NEW.order_intent_id, intent_status;
    END IF;

    IF intent_status IN (
        'COMPLETED',
        'PARTIALLY_COMPLETED',
        'CANCELED',
        'REJECTED',
        'EXPIRED',
        'BLOCKED'
    ) AND NEW.status NOT IN ('FILLED', 'CANCELED', 'REJECTED', 'REPLACED') THEN
        RAISE EXCEPTION
            'broker order % cannot become fillable after order intent % terminated',
            NEW.id, NEW.order_intent_id;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_enforce_broker_order_confirmation
BEFORE INSERT OR UPDATE OF order_intent_id, status ON broker_orders
FOR EACH ROW
EXECUTE FUNCTION enforce_broker_order_confirmation();

CREATE FUNCTION enforce_execution_snapshot_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    intent_status VARCHAR(40);
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'execution snapshots are append-only';
    END IF;

    SELECT oi.status
      INTO intent_status
      FROM order_intents oi
      JOIN broker_orders bo ON bo.order_intent_id = oi.id
     WHERE bo.id = NEW.broker_order_id
     FOR UPDATE OF oi;

    IF intent_status IN (
        'COMPLETED',
        'PARTIALLY_COMPLETED',
        'CANCELED',
        'REJECTED',
        'EXPIRED',
        'BLOCKED'
    ) THEN
        RAISE EXCEPTION
            'execution snapshot cannot be added after order intent termination';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_enforce_execution_snapshot_immutability
BEFORE INSERT OR UPDATE OR DELETE ON execution_snapshots
FOR EACH ROW
EXECUTE FUNCTION enforce_execution_snapshot_immutability();
