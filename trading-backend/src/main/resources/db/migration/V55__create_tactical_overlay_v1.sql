ALTER TABLE investment_decision_ledger
    ADD CONSTRAINT uq_investment_decision_owner_id UNIQUE (user_id, decision_id);

CREATE TABLE investment_tactical_overlay_inputs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    input_type VARCHAR(32) NOT NULL
        CHECK (input_type IN ('AVWAP_ANCHOR', 'THEME', 'THEME_MAPPING', 'PERFORMANCE_ENTRY')),
    entity_key VARCHAR(160) NOT NULL CHECK (length(btrim(entity_key)) BETWEEN 1 AND 160),
    ticker VARCHAR(32) CHECK (ticker IS NULL OR ticker = upper(ticker)),
    decision_id UUID,
    effective_date DATE NOT NULL,
    source VARCHAR(80) NOT NULL CHECK (length(btrim(source)) BETWEEN 1 AND 80),
    source_as_of TIMESTAMPTZ NOT NULL,
    active BOOLEAN NOT NULL,
    overlay_version VARCHAR(24) NOT NULL CHECK (overlay_version = 'TACTICAL_V1'),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    created_at TIMESTAMPTZ NOT NULL,
    input_order BIGSERIAL NOT NULL UNIQUE,
    CONSTRAINT fk_tactical_input_decision_owner FOREIGN KEY (user_id, decision_id)
        REFERENCES investment_decision_ledger (user_id, decision_id),
    CHECK (input_type <> 'AVWAP_ANCHOR' OR NOT active OR (
        payload->>'anchorType' IS NOT NULL AND payload->>'anchorType' IN (
            'EARNINGS_GAP', 'BREAKOUT_DAY', 'CATALYST_DAY', 'MAJOR_LOW', 'POSITION_ENTRY'))),
    CHECK (COALESCE(input_type <> 'PERFORMANCE_ENTRY' OR NOT active OR (
        payload ? 'entryDate'
        AND payload ? 'entryPrice'
        AND payload ? 'initialRiskPrice'
        AND jsonb_typeof(payload->'entryDate') = 'string'
        AND payload->>'entryDate' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
        AND jsonb_typeof(payload->'entryPrice') = 'number'
        AND jsonb_typeof(payload->'initialRiskPrice') = 'number'
        AND (payload->>'entryPrice')::numeric > 0
        AND (payload->>'initialRiskPrice')::numeric > 0
        AND (payload->>'entryPrice')::numeric > (payload->>'initialRiskPrice')::numeric
        AND (payload->'overlayEffect' IS NULL OR jsonb_typeof(payload->'overlayEffect') = 'null'
             OR payload->>'overlayEffect' IN (
                 'BETTER_ENTRY', 'AVOIDED_CHASE', 'AVOIDED_FAILED_BREAKOUT', 'PREMATURE_FILTER', 'NO_EFFECT'))
        AND (payload->'entrySetup' IS NULL OR jsonb_typeof(payload->'entrySetup') = 'null'
             OR (jsonb_typeof(payload->'entrySetup') = 'string'
                 AND length(btrim(payload->>'entrySetup')) BETWEEN 1 AND 128))
        AND (payload->'quantity' IS NULL OR jsonb_typeof(payload->'quantity') = 'null'
             OR (jsonb_typeof(payload->'quantity') = 'number' AND (payload->>'quantity')::numeric > 0)
        )
    ), false))
);

CREATE INDEX ix_tactical_overlay_inputs_latest
    ON investment_tactical_overlay_inputs (user_id, input_type, entity_key, effective_date DESC,
                                            created_at DESC, id DESC);

CREATE INDEX ix_tactical_overlay_inputs_ticker
    ON investment_tactical_overlay_inputs (user_id, ticker, input_type, effective_date DESC,
                                            created_at DESC, id DESC)
    WHERE ticker IS NOT NULL;

CREATE SEQUENCE investment_tactical_overlay_capture_order_seq;

CREATE TABLE investment_tactical_overlay_bar_snapshots (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    ticker VARCHAR(32) NOT NULL CHECK (ticker = upper(ticker)),
    bar_date DATE NOT NULL,
    source VARCHAR(24) NOT NULL CHECK (source = 'TOSS'),
    source_as_of TIMESTAMPTZ NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    origin_input_snapshot_id UUID,
    open_price NUMERIC(24, 8) NOT NULL CHECK (open_price > 0),
    high_price NUMERIC(24, 8) NOT NULL CHECK (high_price > 0),
    low_price NUMERIC(24, 8) NOT NULL CHECK (low_price > 0),
    close_price NUMERIC(24, 8) NOT NULL CHECK (close_price > 0),
    volume NUMERIC(30, 8) NOT NULL CHECK (volume >= 0),
    source_conflict BOOLEAN NOT NULL DEFAULT FALSE,
    capture_order BIGINT NOT NULL DEFAULT nextval('investment_tactical_overlay_capture_order_seq'),
    created_at TIMESTAMPTZ NOT NULL,
    CHECK (high_price >= greatest(open_price, low_price, close_price)),
    CHECK (low_price <= least(open_price, high_price, close_price)),
    CHECK (source_as_of <= captured_at),
    CONSTRAINT fk_tactical_bar_input FOREIGN KEY (user_id, origin_input_snapshot_id)
        REFERENCES analysis_input_snapshots (user_id, id)
);

CREATE INDEX ix_tactical_overlay_bars_history
    ON investment_tactical_overlay_bar_snapshots (user_id, ticker, bar_date DESC, captured_at DESC, id DESC);

CREATE TABLE investment_tactical_overlay_snapshots (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    snapshot_type VARCHAR(16) NOT NULL
        CHECK (snapshot_type IN ('SECURITY', 'THEME', 'MARKET', 'DECISION', 'POSITION')),
    entity_key VARCHAR(160) NOT NULL CHECK (length(btrim(entity_key)) BETWEEN 1 AND 160),
    ticker VARCHAR(32) CHECK (ticker IS NULL OR ticker = upper(ticker)),
    as_of DATE,
    source_as_of TIMESTAMPTZ,
    calculated_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(80),
    overlay_version VARCHAR(24) NOT NULL CHECK (overlay_version = 'TACTICAL_V1'),
    status VARCHAR(24) NOT NULL CHECK (status IN (
        'OK', 'PARTIAL', 'DATA_MISSING', 'INSUFFICIENT_HISTORY', 'SOURCE_CONFLICT',
        'STALE', 'NOT_CONFIGURED', 'UNVERIFIED', 'NOT_APPLICABLE')),
    reasons JSONB NOT NULL CHECK (jsonb_typeof(reasons) = 'array'),
    input_refs JSONB NOT NULL CHECK (jsonb_typeof(input_refs) = 'array'),
    properties_hash CHAR(64) NOT NULL CHECK (properties_hash ~ '^[0-9a-f]{64}$'),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    created_at TIMESTAMPTZ NOT NULL,
    snapshot_order BIGSERIAL NOT NULL UNIQUE
);

CREATE INDEX ix_tactical_overlay_snapshots_latest
    ON investment_tactical_overlay_snapshots (user_id, snapshot_type, entity_key,
                                               calculated_at DESC, id DESC);

CREATE TRIGGER trg_tactical_overlay_inputs_append_only
BEFORE UPDATE OR DELETE ON investment_tactical_overlay_inputs
FOR EACH ROW EXECUTE FUNCTION reject_investment_snapshot_change();

CREATE TRIGGER trg_tactical_overlay_bars_append_only
BEFORE UPDATE OR DELETE ON investment_tactical_overlay_bar_snapshots
FOR EACH ROW EXECUTE FUNCTION reject_investment_snapshot_change();

CREATE TRIGGER trg_tactical_overlay_snapshots_append_only
BEFORE UPDATE OR DELETE ON investment_tactical_overlay_snapshots
FOR EACH ROW EXECUTE FUNCTION reject_investment_snapshot_change();
