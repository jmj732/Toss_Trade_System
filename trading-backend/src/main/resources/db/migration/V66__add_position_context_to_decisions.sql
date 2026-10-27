ALTER TABLE investment_decision_ledger
    ADD COLUMN investment_horizon VARCHAR(16),
    ADD COLUMN price_risk_state VARCHAR(32),
    ADD COLUMN thesis_health VARCHAR(16),
    ADD COLUMN action_reason VARCHAR(2000);

ALTER TABLE investment_decision_ledger
    ADD CONSTRAINT ck_investment_decision_investment_horizon
        CHECK (investment_horizon IS NULL OR investment_horizon IN ('TACTICAL', 'STRATEGIC', 'UNKNOWN')),
    ADD CONSTRAINT ck_investment_decision_price_risk_state
        CHECK (price_risk_state IS NULL OR price_risk_state IN
            ('UNKNOWN', 'CLEAR', 'NEAR', 'INTRADAY_BREACH', 'PRICE_RISK_BREACH', 'RECOVERED')),
    ADD CONSTRAINT ck_investment_decision_thesis_health
        CHECK (thesis_health IS NULL OR thesis_health IN ('UNKNOWN', 'INTACT', 'IMPAIRED', 'INVALIDATED'));
