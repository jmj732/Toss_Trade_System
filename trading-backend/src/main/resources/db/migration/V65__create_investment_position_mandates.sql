CREATE TABLE investment_position_mandates (
    user_id UUID NOT NULL REFERENCES users (id),
    ticker VARCHAR(32) NOT NULL CHECK (ticker = upper(ticker)),
    investment_horizon VARCHAR(16) NOT NULL
        CHECK (investment_horizon IN ('TACTICAL', 'STRATEGIC')),
    risk_price_role VARCHAR(24) NOT NULL
        CHECK (risk_price_role IN ('EXIT_EVALUATION', 'REVIEW_TRIGGER')),
    source VARCHAR(24) NOT NULL CHECK (source = 'USER_CONFIRMED'),
    effective_date DATE NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, ticker),
    CONSTRAINT ck_investment_position_mandate_horizon_role CHECK (
        (investment_horizon = 'TACTICAL' AND risk_price_role = 'EXIT_EVALUATION')
        OR (investment_horizon = 'STRATEGIC' AND risk_price_role = 'REVIEW_TRIGGER')
    )
);
