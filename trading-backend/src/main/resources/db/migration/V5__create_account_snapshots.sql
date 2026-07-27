ALTER TABLE broker_connections
    ADD CONSTRAINT uq_broker_connections_owner_id UNIQUE (user_id, id);

CREATE TABLE account_sync_runs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    broker_connection_id UUID NOT NULL,
    credential_revision BIGINT NOT NULL CHECK (credential_revision > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    error_code VARCHAR(60),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_account_sync_run_connection
        FOREIGN KEY (user_id, broker_connection_id)
        REFERENCES broker_connections (user_id, id),
    CONSTRAINT ck_account_sync_run_completion CHECK (
        (status = 'RUNNING' AND completed_at IS NULL AND error_code IS NULL)
        OR (status = 'SUCCEEDED' AND completed_at IS NOT NULL AND error_code IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL AND error_code IS NOT NULL)
    ),
    CONSTRAINT uq_account_sync_run_owner_id
        UNIQUE (user_id, broker_connection_id, id)
);

CREATE UNIQUE INDEX uq_account_sync_run_running
    ON account_sync_runs (user_id, broker_connection_id)
    WHERE status = 'RUNNING';

CREATE INDEX ix_account_sync_run_latest_success
    ON account_sync_runs (user_id, broker_connection_id, completed_at DESC, id DESC)
    WHERE status = 'SUCCEEDED';

CREATE TABLE account_snapshots (
    id UUID PRIMARY KEY,
    sync_run_id UUID NOT NULL UNIQUE,
    user_id UUID NOT NULL,
    broker_connection_id UUID NOT NULL,
    account_type VARCHAR(40) NOT NULL,
    display_account_number VARCHAR(40) NOT NULL,
    total_purchase_amounts JSONB NOT NULL,
    market_value_amounts JSONB NOT NULL,
    market_value_after_cost_amounts JSONB NOT NULL,
    profit_loss_amounts JSONB NOT NULL,
    profit_loss_after_cost_amounts JSONB NOT NULL,
    daily_profit_loss_amounts JSONB NOT NULL,
    profit_loss_rate NUMERIC NOT NULL,
    profit_loss_rate_after_cost NUMERIC NOT NULL,
    daily_profit_loss_rate NUMERIC NOT NULL,
    cash_balance_status VARCHAR(20) NOT NULL CHECK (cash_balance_status = 'UNKNOWN'),
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_account_snapshot_run
        FOREIGN KEY (user_id, broker_connection_id, sync_run_id)
        REFERENCES account_sync_runs (user_id, broker_connection_id, id)
);

CREATE TABLE position_snapshots (
    id UUID PRIMARY KEY,
    sync_run_id UUID NOT NULL,
    user_id UUID NOT NULL,
    broker_connection_id UUID NOT NULL,
    symbol VARCHAR(30) NOT NULL,
    name VARCHAR(200) NOT NULL,
    market_country VARCHAR(20) NOT NULL,
    quantity NUMERIC NOT NULL CHECK (quantity >= 0),
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('KRW', 'USD')),
    average_price NUMERIC NOT NULL CHECK (average_price >= 0),
    last_price NUMERIC NOT NULL CHECK (last_price >= 0),
    purchase_amount NUMERIC NOT NULL CHECK (purchase_amount >= 0),
    market_value_amount NUMERIC NOT NULL CHECK (market_value_amount >= 0),
    market_value_after_cost NUMERIC NOT NULL CHECK (market_value_after_cost >= 0),
    profit_loss_amount NUMERIC NOT NULL,
    profit_loss_after_cost NUMERIC NOT NULL,
    profit_loss_rate NUMERIC NOT NULL,
    profit_loss_rate_after_cost NUMERIC NOT NULL,
    daily_profit_loss_amount NUMERIC NOT NULL,
    daily_profit_loss_rate NUMERIC NOT NULL,
    commission NUMERIC NOT NULL CHECK (commission >= 0),
    tax NUMERIC CHECK (tax >= 0),
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_position_snapshot_run
        FOREIGN KEY (user_id, broker_connection_id, sync_run_id)
        REFERENCES account_sync_runs (user_id, broker_connection_id, id)
);

CREATE INDEX ix_position_snapshot_run
    ON position_snapshots (sync_run_id);

CREATE TABLE account_capacity_snapshots (
    id UUID PRIMARY KEY,
    sync_run_id UUID NOT NULL,
    user_id UUID NOT NULL,
    broker_connection_id UUID NOT NULL,
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('KRW', 'USD')),
    cash_buying_power NUMERIC NOT NULL CHECK (cash_buying_power >= 0),
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_account_capacity_snapshot_run
        FOREIGN KEY (user_id, broker_connection_id, sync_run_id)
        REFERENCES account_sync_runs (user_id, broker_connection_id, id),
    CONSTRAINT uq_account_capacity_snapshot_currency
        UNIQUE (sync_run_id, currency)
);

CREATE FUNCTION enforce_account_sync_run_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'RUNNING' OR NEW.status = 'RUNNING' THEN
        RAISE EXCEPTION 'account sync run has already finished';
    END IF;
    IF NEW.id <> OLD.id
        OR NEW.user_id <> OLD.user_id
        OR NEW.broker_connection_id <> OLD.broker_connection_id
        OR NEW.credential_revision <> OLD.credential_revision
        OR NEW.started_at <> OLD.started_at THEN
        RAISE EXCEPTION 'account sync run identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_enforce_account_sync_run_transition
BEFORE UPDATE ON account_sync_runs
FOR EACH ROW
EXECUTE FUNCTION enforce_account_sync_run_transition();

CREATE FUNCTION enforce_account_snapshot_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    run_status VARCHAR(20);
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'account snapshots are append-only';
    END IF;

    SELECT status INTO run_status
      FROM account_sync_runs
     WHERE id = NEW.sync_run_id;
    IF run_status <> 'SUCCEEDED' THEN
        RAISE EXCEPTION 'account snapshots require a successful sync run';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_account_snapshots_append_only
BEFORE INSERT OR UPDATE OR DELETE ON account_snapshots
FOR EACH ROW EXECUTE FUNCTION enforce_account_snapshot_immutability();

CREATE TRIGGER trg_position_snapshots_append_only
BEFORE INSERT OR UPDATE OR DELETE ON position_snapshots
FOR EACH ROW EXECUTE FUNCTION enforce_account_snapshot_immutability();

CREATE TRIGGER trg_account_capacity_snapshots_append_only
BEFORE INSERT OR UPDATE OR DELETE ON account_capacity_snapshots
FOR EACH ROW EXECUTE FUNCTION enforce_account_snapshot_immutability();
