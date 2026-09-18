ALTER TABLE order_intents
    ADD COLUMN proposal_reference_price NUMERIC(28,10);

ALTER TABLE order_intents
    ADD CONSTRAINT ck_order_intents_proposal_reference_price
        CHECK (proposal_reference_price IS NULL OR proposal_reference_price > 0);
