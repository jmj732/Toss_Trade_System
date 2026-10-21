-- Link only risk-authorized defensive order proposals to their persisted decision and event.
-- NULL on generic and historical intents preserves existing rows without backfill.
ALTER TABLE order_intents
    ADD COLUMN investment_decision_id UUID,
    ADD COLUMN investment_risk_event_id UUID,
    ADD CONSTRAINT ck_order_intent_risk_decision_pair CHECK (
        (investment_decision_id IS NULL) = (investment_risk_event_id IS NULL)
    ),
    ADD CONSTRAINT fk_order_intent_investment_decision
        FOREIGN KEY (user_id, investment_decision_id)
        REFERENCES investment_decision_ledger (user_id, decision_id),
    ADD CONSTRAINT fk_order_intent_investment_risk_event
        FOREIGN KEY (investment_risk_event_id, user_id)
        REFERENCES monitoring_state_history (id, user_id);

CREATE INDEX ix_order_intent_defensive_investment_link
    ON order_intents (user_id, broker_connection_id, investment_decision_id, investment_risk_event_id)
    WHERE investment_decision_id IS NOT NULL;
