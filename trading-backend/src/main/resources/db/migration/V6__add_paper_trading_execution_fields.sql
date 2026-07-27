ALTER TABLE order_intents
    ADD COLUMN side VARCHAR(10),
    ADD COLUMN order_type VARCHAR(10),
    ADD COLUMN symbol VARCHAR(30),
    ADD COLUMN limit_price NUMERIC(28, 10),
    ADD COLUMN trading_currency VARCHAR(3),
    ADD CONSTRAINT ck_order_intents_paper_side CHECK (side IS NULL OR side IN ('BUY', 'SELL')),
    ADD CONSTRAINT ck_order_intents_paper_type CHECK (order_type IS NULL OR order_type IN ('MARKET', 'LIMIT')),
    ADD CONSTRAINT ck_order_intents_paper_currency CHECK (
        trading_currency IS NULL OR trading_currency IN ('KRW', 'USD')
    ),
    ADD CONSTRAINT ck_order_intents_paper_fields_complete CHECK (
        (side IS NULL AND order_type IS NULL AND symbol IS NULL
            AND limit_price IS NULL AND trading_currency IS NULL)
        OR (side IS NOT NULL AND order_type IS NOT NULL AND symbol IS NOT NULL
            AND symbol <> '' AND trading_currency IS NOT NULL)
    ),
    ADD CONSTRAINT ck_order_intents_paper_limit CHECK (
        order_type IS NULL
        OR (order_type = 'MARKET' AND limit_price IS NULL)
        OR (order_type = 'LIMIT' AND limit_price > 0)
    );

ALTER TABLE execution_snapshots
    ADD COLUMN average_filled_price NUMERIC(28, 10),
    ADD COLUMN filled_amount NUMERIC(28, 10),
    ADD COLUMN commission NUMERIC(28, 10),
    ADD COLUMN tax NUMERIC(28, 10),
    ADD COLUMN currency VARCHAR(3),
    ADD CONSTRAINT ck_execution_snapshots_paper_amounts CHECK (
        (average_filled_price IS NULL OR average_filled_price >= 0)
        AND (filled_amount IS NULL OR filled_amount >= 0)
        AND (commission IS NULL OR commission >= 0)
        AND (tax IS NULL OR tax >= 0)
        AND (currency IS NULL OR currency IN ('KRW', 'USD'))
    ),
    ADD CONSTRAINT ck_execution_snapshots_paper_fields_complete CHECK (
        (currency IS NULL AND average_filled_price IS NULL AND filled_amount IS NULL
            AND commission IS NULL AND tax IS NULL)
        OR (currency IS NOT NULL AND filled_amount IS NOT NULL
            AND commission IS NOT NULL AND tax IS NOT NULL
            AND (filled_quantity = 0 OR average_filled_price IS NOT NULL))
    );

CREATE FUNCTION enforce_order_intent_paper_fields_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status <> 'PROPOSED' AND (
        NEW.side,
        NEW.order_type,
        NEW.symbol,
        NEW.limit_price,
        NEW.trading_currency
    ) IS DISTINCT FROM (
        OLD.side,
        OLD.order_type,
        OLD.symbol,
        OLD.limit_price,
        OLD.trading_currency
    ) THEN
        RAISE EXCEPTION 'approved order intent paper fields are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_enforce_order_intent_paper_fields_immutability
BEFORE UPDATE ON order_intents
FOR EACH ROW
EXECUTE FUNCTION enforce_order_intent_paper_fields_immutability();
