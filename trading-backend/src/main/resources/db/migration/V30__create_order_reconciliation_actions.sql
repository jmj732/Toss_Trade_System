-- UNKNOWN 제출 시도 조정 감사 원장 (플랜 원장 E5, SPEC:1055/1099-1100/1121).
--
-- 조정 절차의 진입·판정·계좌 잠금을 append-only 로 기록한다(kill_switch_ledger 패턴). 상태를
-- 덮어쓰지 않고 각 행이 행위자·시각·사유·근거를 담은 감사 레코드다. 판정 근거로 OPEN/CLOSED
-- 그룹 조회 결과를 각각 남겨, "찾지 못함(ABSENT)" 과 "찾을 수 없었음(UNAVAILABLE)" 을 사후에도
-- 구분할 수 있게 한다.
--
-- 해제(release)는 이 표가 아니라 kill_switch_ledger 의 disengage 행에 남는다(E4, step-up 필요).
-- 브로커 응답의 원문 식별자·자격증명은 여기에 넣지 않는다(SPEC:1151).
-- FK 를 걸지 않는다(kill_switch_ledger 선례). append-only 감사 원장이라 대상 행 truncate/삭제와
-- 결합하지 않으며, 무결성은 애플리케이션 계층이 보장한다.
CREATE TABLE order_reconciliation_actions (
    id UUID PRIMARY KEY,
    submission_attempt_id UUID NOT NULL,
    order_intent_id UUID NOT NULL,
    action VARCHAR(40) NOT NULL CHECK (action IN (
        'RECONCILIATION_ENTERED',
        'RECONCILIATION_DECIDED',
        'ACCOUNT_LOCK_ENGAGED'
    )),
    -- 판정 결과. 진입 시점(ENTERED)에는 아직 없어 NULL.
    decision VARCHAR(40) CHECK (decision IN (
        'BROKER_ORDER_FOUND',
        'RETRY_SAME_KEY_ALLOWED',
        'MANUAL_REVIEW_REQUIRED'
    )),
    -- 그룹 조회 결과. MATCHED=우리 주문 찾음, ABSENT=조회 성공+없음, UNAVAILABLE=조회 실패/미확정.
    open_query_status VARCHAR(16) CHECK (open_query_status IN ('MATCHED', 'ABSENT', 'UNAVAILABLE')),
    closed_query_status VARCHAR(16) CHECK (closed_query_status IN ('MATCHED', 'ABSENT', 'UNAVAILABLE')),
    actor VARCHAR(255) NOT NULL,
    reason VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_order_reconciliation_actions_attempt
    ON order_reconciliation_actions (submission_attempt_id, occurred_at);
