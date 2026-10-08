-- Preserve legacy states; accept externally authored briefing lifecycle states without coercion.
ALTER TABLE investment_thesis_states
    DROP CONSTRAINT investment_thesis_states_invalidation_status_check;
ALTER TABLE investment_thesis_states
    ADD CONSTRAINT investment_thesis_states_invalidation_status_check
    CHECK (invalidation_status IN (
        'NOT_REVIEWED', 'SUSPECTED', 'CONFIRMED', 'CLEARED',
        'AI_PROPOSED', 'UNVERIFIED', 'INVALIDATION_UNDEFINED'
    ));
