-- 주문 kill switch 원장 (플랜 원장 E4, SPEC:886/962/1136/1159).
--
-- 비상 정지를 DB 원장으로 만든다. 상태를 덮어쓰지 않고 버전을 올려 추가만 하며(risk_policy_history
-- 패턴, V15), 각 행 자체가 행위자·시각·사유·범위·대상을 담은 감사 레코드다. 제출 worker 는 매
-- 제출마다 최신 버전을 다시 읽어(캐시 없음) 판정한다.
--
-- 범위 3종(GLOBAL/USER/ACCOUNT)은 서로 독립이다. "하나라도 켜져 있으면 차단" 이며, 좁은 범위의
-- 해제가 넓은 범위의 정지를 무효화하지 못한다. GLOBAL 은 대상이 없어 all-zeros sentinel 로 고정한다.
CREATE TABLE kill_switch_ledger (
    id UUID PRIMARY KEY,
    scope VARCHAR(16) NOT NULL CHECK (scope IN ('GLOBAL', 'USER', 'ACCOUNT')),
    -- 대상 ID. USER 는 users.id, ACCOUNT 는 broker_connections.id, GLOBAL 은 sentinel.
    -- 대상이 이질적이라 단일 FK 를 걸지 않고 애플리케이션 계층에서 소유권을 검증한다.
    target_id UUID NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    engaged BOOLEAN NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_kill_switch_scope_target_version UNIQUE (scope, target_id, version),
    -- GLOBAL 은 항상 고정 sentinel 대상이어야 범위 판정이 모호하지 않다.
    CONSTRAINT ck_kill_switch_global_target CHECK (
        scope <> 'GLOBAL' OR target_id = '00000000-0000-0000-0000-000000000000'
    )
);

-- 최신 버전 조회(범위·대상별 MAX(version))를 위한 인덱스.
CREATE INDEX ix_kill_switch_ledger_scope_target
    ON kill_switch_ledger (scope, target_id, version DESC);

-- 원장은 추가만 허용한다. 상태 덮어쓰기·삭제를 DB 경계에서 거부한다(risk_policy_history 와 동일).
CREATE FUNCTION reject_kill_switch_ledger_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'kill switch ledger is append-only';
END;
$$;

CREATE TRIGGER trg_reject_kill_switch_ledger_change
BEFORE UPDATE OR DELETE ON kill_switch_ledger
FOR EACH ROW
EXECUTE FUNCTION reject_kill_switch_ledger_change();

-- ---------------------------------------------------------------------------
-- E2 step-up 재인증 토큰의 바인딩 대상 일반화 (플랜 원장 E4; SPEC:1159).
--
-- kill switch 해제(disengage)는 주문이 아니라 "정책 대상"에 재인증을 요구한다. E2 의 단일 사용
-- step-up 메커니즘을 그대로 재사용하되, 바인딩 대상을 주문 intent 에서 일반 subject 로 넓힌다.
--
-- 최소·가산 변경: 기존 order_intent_id 컬럼과 복합 FK(fk_step_up_intent_owner)를 그대로 둔다.
-- 주문 승인 경로의 INSERT/SELECT/소비 SQL 은 한 줄도 바뀌지 않는다. subject_kind 는 기본값
-- 'ORDER_APPROVAL' 이라 컬럼을 명시하지 않는 기존 INSERT 는 자동으로 주문 승인 토큰이 된다.
-- 주문 외 subject(예: kill switch 대상)는 order_intent_id 를 NULL 로 두어 복합 FK 를 건너뛴다
-- (MATCH SIMPLE: 참조 컬럼 중 하나라도 NULL 이면 FK 미검사).
-- ---------------------------------------------------------------------------
ALTER TABLE order_approval_step_up_tokens
    ADD COLUMN subject_kind VARCHAR(32) NOT NULL DEFAULT 'ORDER_APPROVAL';

ALTER TABLE order_approval_step_up_tokens
    ADD COLUMN subject_ref UUID;

ALTER TABLE order_approval_step_up_tokens
    ALTER COLUMN order_intent_id DROP NOT NULL;

-- 정확히 하나의 바인딩만 유효하다: ORDER_APPROVAL 은 order_intent_id 로, 그 외 subject 는
-- subject_ref 로만 바인딩한다. 두 컬럼이 동시에 채워지거나 둘 다 비는 상태를 금지한다.
ALTER TABLE order_approval_step_up_tokens
    ADD CONSTRAINT ck_step_up_subject_binding CHECK (
        (subject_kind = 'ORDER_APPROVAL'
            AND order_intent_id IS NOT NULL AND subject_ref IS NULL)
        OR (subject_kind <> 'ORDER_APPROVAL'
            AND order_intent_id IS NULL AND subject_ref IS NOT NULL)
    );

CREATE INDEX ix_step_up_tokens_subject
    ON order_approval_step_up_tokens (subject_kind, subject_ref);
