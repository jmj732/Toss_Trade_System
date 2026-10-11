-- Give pre-V57 thesis rows an auditable current revision without inventing a past actor,
-- review timestamp, source timestamp, policy approval, or proposer run ID.
ALTER TABLE investment_thesis_revisions
    DROP CONSTRAINT investment_thesis_revisions_actor_type_check,
    ALTER COLUMN actor_user_id DROP NOT NULL;

ALTER TABLE investment_thesis_revisions
    ADD CONSTRAINT investment_thesis_revisions_actor_type_check
        CHECK (actor_type IN ('USER_SESSION', 'CONNECTOR_MCP', 'TELEGRAM', 'AI_POLICY', 'LEGACY_BASELINE')),
    ADD CONSTRAINT investment_thesis_revisions_actor_identity_check
        CHECK ((actor_type = 'LEGACY_BASELINE' AND actor_user_id IS NULL AND actor_session_id IS NULL
                    AND source_as_of IS NULL AND policy_version IS NULL
                    AND verification_event_id IS NULL AND asserted_run_id IS NULL)
            OR (actor_type <> 'LEGACY_BASELINE' AND actor_user_id IS NOT NULL));

WITH unrevisioned AS (
    SELECT state.*, gen_random_uuid() AS revision_id
      FROM investment_thesis_states state
     WHERE NOT EXISTS (
         SELECT 1 FROM investment_thesis_revisions revision
          WHERE revision.user_id = state.user_id AND revision.ticker = state.ticker
     )
)
INSERT INTO investment_thesis_revisions (
    id, user_id, ticker, revision, previous_status, new_status,
    previous_trigger_price, new_trigger_price, thesis_snapshot,
    actor_type, actor_user_id, actor_session_id, source_as_of, reason, recorded_at,
    policy_version, verification_event_id, asserted_run_id
)
SELECT revision_id, user_id, ticker, 1, NULL, invalidation_status,
       NULL, price_risk_trigger_price,
       jsonb_build_object(
           'ticker', ticker,
           'coreThesis', core_thesis,
           'upsideDriver', upside_driver,
           'expectationsGap', expectations_gap,
           'fundamentalInvalidation', fundamental_invalidation,
           'revisionInvalidation', revision_invalidation,
           'priceRiskTrigger', price_risk_trigger,
           'priceRiskTriggerPrice', price_risk_trigger_price,
           'invalidationStatus', invalidation_status,
           'expandTrigger', expand_trigger,
           'exitOrDiscardTrigger', exit_or_discard_trigger,
           'classification', classification,
           'updatedAt', updated_at,
           'revisionId', revision_id,
           'revision', 1,
           'approvalActorType', 'LEGACY_BASELINE',
           'policyVersion', NULL,
           'verificationEventId', NULL,
           'assertedRunId', NULL
       ),
       'LEGACY_BASELINE', NULL, NULL, NULL,
       'LEGACY_BASELINE: migration-time state snapshot; historical actor, review time, source time, and proposer run are unknown.',
       transaction_timestamp(), NULL, NULL, NULL
  FROM unrevisioned
ON CONFLICT (user_id, ticker, revision) DO NOTHING;
