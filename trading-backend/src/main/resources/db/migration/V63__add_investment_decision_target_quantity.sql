-- Optional, externally authored remaining-position target for an event-linked REDUCE/EXIT decision.
-- Existing decision rows remain unchanged and NULL.
ALTER TABLE investment_decision_ledger
    ADD COLUMN target_remaining_quantity NUMERIC(28, 6),
    ADD COLUMN target_broker_connection_id UUID,
    ADD CONSTRAINT ck_investment_decision_target_quantity CHECK (
        target_remaining_quantity IS NULL
        OR (target_remaining_quantity >= 0 AND action IN ('REDUCE', 'EXIT'))
    ),
    ADD CONSTRAINT ck_investment_exit_target_zero CHECK (
        target_remaining_quantity IS NULL
        OR action <> 'EXIT'
        OR target_remaining_quantity = 0
    ),
    ADD CONSTRAINT ck_investment_reduce_target_positive CHECK (
        target_remaining_quantity IS NULL
        OR action <> 'REDUCE'
        OR target_remaining_quantity > 0
    ),
    ADD CONSTRAINT ck_investment_target_account_pair CHECK (
        (target_remaining_quantity IS NULL) = (target_broker_connection_id IS NULL)
    ),
    ADD CONSTRAINT fk_investment_decision_target_connection
        FOREIGN KEY (user_id, target_broker_connection_id)
        REFERENCES broker_connections (user_id, id);
