-- Reuse the append-only monitoring transition history for thesis risk events.
ALTER TABLE monitoring_current_states
    DROP CONSTRAINT monitoring_current_states_scope_check,
    ADD CONSTRAINT monitoring_current_states_scope_check
        CHECK (scope IN ('MARKET', 'PORTFOLIO', 'WATCHLIST', 'EVENT', 'THESIS_REVIEW'));

ALTER TABLE monitoring_state_history
    DROP CONSTRAINT monitoring_state_history_scope_check,
    ADD CONSTRAINT monitoring_state_history_scope_check
        CHECK (scope IN ('MARKET', 'PORTFOLIO', 'WATCHLIST', 'EVENT', 'THESIS_REVIEW')),
    ADD CONSTRAINT uq_monitoring_state_history_id_user UNIQUE (id, user_id);

CREATE INDEX ix_monitoring_state_history_event_page
    ON monitoring_state_history (user_id, scope, observed_at DESC, id DESC);

CREATE TABLE investment_risk_event_decision_links (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    ticker VARCHAR(32) NOT NULL CHECK (ticker = upper(ticker)),
    event_id UUID NOT NULL,
    decision_id UUID NOT NULL,
    thesis_revision_id UUID,
    linked_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_investment_risk_event_decision_link UNIQUE (user_id, event_id, decision_id),
    CONSTRAINT fk_risk_event_decision_link_event
        FOREIGN KEY (event_id, user_id) REFERENCES monitoring_state_history (id, user_id),
    CONSTRAINT fk_risk_event_decision_link_decision
        FOREIGN KEY (user_id, decision_id) REFERENCES investment_decision_ledger (user_id, decision_id),
    CONSTRAINT fk_risk_event_decision_link_revision
        FOREIGN KEY (thesis_revision_id, user_id, ticker)
        REFERENCES investment_thesis_revisions (id, user_id, ticker)
);

CREATE INDEX ix_investment_risk_event_decision_links_event
    ON investment_risk_event_decision_links (user_id, event_id, linked_at DESC);

CREATE TRIGGER trg_investment_risk_event_decision_links_append_only
BEFORE UPDATE OR DELETE ON investment_risk_event_decision_links
FOR EACH ROW EXECUTE FUNCTION reject_investment_snapshot_change();
