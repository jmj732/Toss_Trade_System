-- 주문 승인 step-up 재인증 토큰 (플랜 원장 E2, SPEC:966/1151).
-- 사람이 최근에 OIDC 재인증했음(auth_time 신선도)을 근거로 발급되며, 짧은 수명 + 단일 사용 +
-- (사용자, 승인 대상) 바인딩이다. 원문 토큰은 절대 저장하지 않고 SHA-256 해시만 보관한다.
CREATE TABLE order_approval_step_up_tokens (
    token_hash CHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    order_intent_id UUID NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT fk_step_up_intent_owner
        FOREIGN KEY (order_intent_id, user_id)
        REFERENCES order_intents (id, user_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_step_up_expiry_after_issue CHECK (expires_at > issued_at)
);

CREATE INDEX ix_step_up_tokens_intent
    ON order_approval_step_up_tokens (order_intent_id);
