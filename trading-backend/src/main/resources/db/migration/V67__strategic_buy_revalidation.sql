ALTER TABLE order_intents
    ADD COLUMN investment_buy_decision_id UUID,
    ADD CONSTRAINT fk_order_intent_buy_decision
        FOREIGN KEY (user_id, investment_buy_decision_id)
        REFERENCES investment_decision_ledger (user_id, decision_id),
    ADD CONSTRAINT ck_order_intent_buy_decision_link CHECK (
        investment_buy_decision_id IS NULL
        OR (side = 'BUY' AND investment_decision_id IS NULL AND investment_risk_event_id IS NULL)
    );

CREATE INDEX ix_order_intent_buy_decision
    ON order_intents (user_id, investment_buy_decision_id)
    WHERE investment_buy_decision_id IS NOT NULL;

CREATE UNIQUE INDEX uq_active_order_intent_buy_decision
    ON order_intents (user_id, investment_buy_decision_id)
    WHERE investment_buy_decision_id IS NOT NULL
      AND status IN ('PROPOSED','APPROVED','REVALIDATING','SUBMISSION_PENDING',
                     'RECONCILIATION_REQUIRED','MANUAL_REVIEW_REQUIRED','ACTIVE','PARTIALLY_COMPLETED');

CREATE TABLE investment_strategic_buy_revalidations (
    review_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    decision_id UUID NOT NULL,
    ticker VARCHAR(32) NOT NULL CHECK (ticker = upper(ticker)),
    investment_horizon VARCHAR(16) NOT NULL CHECK (investment_horizon = 'STRATEGIC'),
    mandate_updated_at TIMESTAMPTZ NOT NULL,
    thesis_revision_id UUID NOT NULL,
    verdict VARCHAR(16) NOT NULL CHECK (verdict IN ('PASS','BLOCKED')),
    provider VARCHAR(120) NOT NULL,
    model VARCHAR(160) NOT NULL,
    rationale TEXT NOT NULL,
    source_as_of TIMESTAMPTZ NOT NULL,
    sources JSONB NOT NULL CHECK (jsonb_typeof(sources) = 'array' AND jsonb_array_length(sources) >= 2),
    assessment JSONB NOT NULL CHECK (jsonb_typeof(assessment) = 'object'),
    reviewed_market_price NUMERIC(24,8) NOT NULL CHECK (reviewed_market_price > 0),
    market_observed_at TIMESTAMPTZ NOT NULL,
    market_health VARCHAR(24) NOT NULL,
    price_risk_state VARCHAR(32) NOT NULL,
    min_price NUMERIC(24,8) NOT NULL CHECK (min_price > 0),
    max_price NUMERIC(24,8) NOT NULL CHECK (max_price >= min_price),
    max_quantity NUMERIC(28,10) NOT NULL CHECK (max_quantity > 0),
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > source_as_of),
    authenticated_actor_user_id UUID NOT NULL REFERENCES users (id),
    authenticated_actor_key_id UUID,
    body_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_buy_revalidation_decision FOREIGN KEY (user_id, decision_id)
        REFERENCES investment_decision_ledger (user_id, decision_id),
    CONSTRAINT fk_buy_revalidation_thesis_revision FOREIGN KEY (thesis_revision_id, user_id, ticker)
        REFERENCES investment_thesis_revisions (id, user_id, ticker),
    CONSTRAINT ck_buy_revalidation_actor_owner CHECK (authenticated_actor_user_id = user_id)
);

CREATE INDEX ix_investment_strategic_buy_revalidation_latest
    ON investment_strategic_buy_revalidations (user_id, decision_id, created_at DESC);

CREATE TRIGGER trg_investment_strategic_buy_revalidations_append_only
BEFORE UPDATE OR DELETE ON investment_strategic_buy_revalidations
FOR EACH ROW EXECUTE FUNCTION reject_investment_snapshot_change();
