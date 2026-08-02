# 실거래 canary 운영 런북

대상: 명시적으로 활성화된 `feature/real-order-canary-readiness` canary 실행.
Toss 주문 요청·응답 필드와 상태는 저장소에 고정된 Toss OpenAPI 1.2.5 계약과 adapter를 따른다.
계약에 없는 값은 운영자가 추측해 입력하지 않는다.

## 사전 점검

1. `REAL_ORDER_CANARY_ENABLED=true`인지 확인한다.
2. `REAL_ORDER_CANARY_CONNECTION_ID`와 `REAL_ORDER_CANARY_BROKER_ACCOUNT_ID`가 등록된 본인 계좌 allowlist와 정확히 일치하는지 확인한다.
3. 암호화 자격증명, active connection, 계좌별 주문·일일 한도, kill switch 해제 상태를 확인한다.
4. canary 상한은 수량 10 이하, KRW 100,000 이하, USD 100 이하이며 실제 설정은 이보다 작아야 한다.
5. 최근 OIDC 재인증 세션과 신선한 quote를 준비한다. preflight가 하나라도 실패하면 주문은 발생하지 않는다.

## 1회 실행

`Idempotency-Key: <operator-run-key>`를 포함해 `POST /api/v1/live-order-canary/run`을 한 번 호출한다.
키가 없으면 실행하지 않는다. 같은 키의 완료 요청은 기존 결과를 반환하고, 다른 키의 동시 실행은
계좌 잠금으로 `CANARY_RUN_IN_PROGRESS`를 반환한다.

서버가 다음 순서로 실행한다.

`preflight → propose → approve → submit 1회 → OPEN/CLOSED 조회 → 미체결 OPEN이면 cancel 1회 → OPEN/CLOSED 최종 조회 → reconciliation`

각 관문에서 kill switch, step-up, exact account mapping, canary limits, quote freshness를 다시 확인한다.
자동 retry·resend는 없다. `RUNNING` 원장이 남아도 새 키로 재시작하지 말고 운영자가 원장과 브로커를
대조해 수동 종료한다. 결과의 `runId`로 감사 원장을 조회한다.

## 결과 처리

- `PREFLIGHT_ONLY`: 자격증명·설정·allowlist·step-up·quote 등 사전 조건 부족. 브로커 주문 없음.
- `FINAL_RECONCILED`: CLOSED 최종 상태와 내부 projection을 반영. 감사 원장과 브로커 조회 결과를 대조한다.
- `MANUAL_REVIEW_REQUIRED`: timeout/UNKNOWN, 부분 체결 잔존, 취소 거부, 조회 불완전, mapping 불일치 등. 재전송하지 않고 [UNKNOWN 조정 런북](order-reconciliation-runbook.md)을 따른다.
- `REJECTED`: 브로커 거부. 자동 재제출하지 않고 broker response의 안전한 reason code와 감사 event를 확인한다.

감사 원장 `real_order_canary_audit_events`에는 내부 참조, 상태, 성공 여부, reason code와 client/broker order ID 해시만 남는다. 자격증명, 토큰, 계좌번호, raw broker response body는 저장하지 않는다.

## 중단 기준

다음 중 하나면 실행을 중단하고 계좌를 수동 검토 대상으로 둔다.

- OPEN 또는 CLOSED 조회가 UNKNOWN/불완전하다.
- cancel 응답이 UNKNOWN이거나 최종 상태가 CLOSED로 확정되지 않는다.
- 내부 계좌와 브로커 계좌, idempotency key와 Toss client order ID 매핑이 일치하지 않는다.
- kill switch, step-up, 한도, quote freshness 재검증이 실패한다.
