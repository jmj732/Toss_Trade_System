ALTER TABLE investment_strategic_buy_revalidations
    ADD COLUMN review_origin VARCHAR(24) NOT NULL DEFAULT 'CALLER_ASSERTED',
    ADD CONSTRAINT ck_buy_revalidation_review_origin
        CHECK (review_origin IN ('CALLER_ASSERTED', 'SERVER_GENERATED')),
    DROP CONSTRAINT investment_strategic_buy_revalidations_verdict_check,
    ADD CONSTRAINT ck_buy_revalidation_verdict
        CHECK (verdict IN ('PASS', 'BLOCKED', 'UNTRUSTED'));

COMMENT ON COLUMN investment_strategic_buy_revalidations.review_origin IS
    'CALLER_ASSERTED rows cannot authorize submission; SERVER_GENERATED is reserved for a server-managed reviewer.';
