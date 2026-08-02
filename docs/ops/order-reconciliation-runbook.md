# UNKNOWN 제출 시도 조정 런북 (플랜 원장 E5)

대상: 제출 시도(`submission_attempts`)가 `UNKNOWN` 상태로 남은 주문의 조정. 이 절차는 실거래(E6)의
마지막 선행 필수이며, 통합 테스트
`trading-backend/src/test/java/com/jmj/trade/order/UnknownAttemptReconciliationIntegrationTest.java`
가 이 문서의 절차를 그대로 따라가 장애 주입으로 검증한다. 문서와 테스트가 어긋나면 실패다.

## 1. UNKNOWN 은 언제 생기고 어떻게 보는가

- 브로커에 주문을 전송했지만 응답이 미확정(timeout·연결 끊김 등)이면 attempt 는 `UNKNOWN` 이 되고
  order intent 는 `RECONCILIATION_REQUIRED` 로 간다(`OrderSubmissionService.markUnknown`).
- **급소**: 이미 체결된 주문이 브로커의 CLOSED 목록에만 있고 OPEN 목록에는 없을 수 있다. OPEN 만
  보고 "없다"고 판단해 같은 키로 재전송하면 실거래에서 중복 주문이 나간다 — 되돌릴 수 없다
  (SPEC:1055 MVP 완료 기준 7번).
- 관측:
  - `SELECT * FROM submission_attempts WHERE status = 'UNKNOWN';`
  - `SELECT * FROM order_intents WHERE status = 'RECONCILIATION_REQUIRED';`
  - 조정 이력: `SELECT * FROM order_reconciliation_actions WHERE submission_attempt_id = :attemptId ORDER BY occurred_at;`

## 2. 조정 실행 (운영자 명시 행위)

엔드포인트: `POST /api/v1/trading/order-reconciliation` (인증 주체 = 대상 연결 소유자).

```json
{
  "attemptId": "<submission_attempts.id>",
  "brokerConnectionId": "<broker_connections.id>",
  "brokerAccountId": "<브로커 계좌 식별자>",
  "accountType": "<계좌 유형>",
  "displayAccountNumber": "****1234",
  "reason": "<조정 사유>"
}
```

조정기는 한 트랜잭션에서 다음을 수행한다:

1. `RECONCILIATION_ENTERED` 감사 기록.
2. 브로커 **OPEN 과 CLOSED 를 모두** 조회한다. 각 조회 결과는 세 가지로 구분된다:
   - `MATCHED` — 우리 주문을 찾음.
   - `ABSENT` — 조회 성공 + 우리 주문 없음("찾지 못함", 확실히 없음).
   - `UNAVAILABLE` — 조회 실패·부분·미확정("찾을 수 없었음"). 절대 미접수로 해석하지 않는다.
3. 판정(`RECONCILIATION_DECIDED` 감사에 OPEN/CLOSED 상태와 함께 기록):
   - 한쪽 그룹에서라도 `MATCHED` → **`BROKER_ORDER_FOUND`**. 재전송하지 않고 브로커 주문으로 연결.
   - OPEN·CLOSED 모두 `ABSENT` 이고 멱등 창 이내 → **`RETRY_SAME_KEY_ALLOWED`**.
   - 어느 그룹이든 `UNAVAILABLE`, 또는 멱등 창 경과 → **`MANUAL_REVIEW_REQUIRED`**.
4. `MANUAL_REVIEW_REQUIRED` 이면:
   - 해당 계좌의 신규 주문을 **잠근다**(E4 ACCOUNT 범위 kill switch engage, `ACCOUNT_LOCK_ENGAGED`
     감사). 제출 직전 관문(`KillSwitchRevalidationCheck`)이 읽는 바로 그 상태다.
   - 기존 notification outbox 로 운영 알림을 발행한다.

**구조적 안전장치**: 재시도 판정은 CLOSED 그룹까지 확정적으로 조회해 없음을 확인한 경우에만
가능하다. CLOSED 조회 없이는 판정 근거(`ReconciliationEvidence`) 자체를 만들 수 없으므로, OPEN 만
본 상태로는 재전송에 도달할 수 없다.

## 3. 운영자가 확인할 것

- `order_reconciliation_actions` 의 `open_query_status` / `closed_query_status`. 하나라도
  `UNAVAILABLE` 이면 재시도가 아니라 수동 검토가 정답이다.
- 계좌 잠금 상태:
  `SELECT scope, target_id, engaged, version FROM kill_switch_ledger WHERE target_id = :connectionId ORDER BY version DESC LIMIT 1;`
- `BROKER_ORDER_FOUND` 후에는 `broker_orders` 에 정확히 하나의 연결 주문이 있고 intent 상태가
  체결 결과를 반영하는지 확인한다(재전송으로 주문이 늘지 않았음).

## 4. 계좌 잠금 해제 절차

해제는 **운영자의 명시 행위로만** 가능하다. 시간 경과·재시도·스케줄러 자동 해제 경로는 없다.

1. 잠금 원인이 해소됐는지 확인한다(아래 "해제하면 안 되는 상황" 참고).
2. step-up 재인증 토큰을 발급받는다:
   `POST /api/v1/trading/kill-switch/step-up` body `{"scope":"ACCOUNT","targetId":"<connectionId>"}`.
   최근 OIDC 재인증(auth_time)만이 근거이며, 없거나 오래되면 401.
3. 해제:
   `POST /api/v1/trading/kill-switch` header `X-Step-Up-Token: <발급 토큰>`,
   body `{"scope":"ACCOUNT","targetId":"<connectionId>","engaged":false,"reason":"<해제 사유>"}`.
   step-up 토큰이 없으면 401 이고 원장에 해제 행이 생기지 않는다.
4. 해제 후 해당 계좌의 신규 주문이 재개된다. 해제 감사는 `kill_switch_ledger` 의 `engaged=false` 행에
   남는다(행위자·시각·사유).

## 5. 해제하면 안 되는 상황

- OPEN/CLOSED 중 하나라도 아직 `UNAVAILABLE` 이라 주문의 실제 접수 여부가 확정되지 않은 경우.
- 브로커 측 체결/취소 여부가 다른 채널(체결 통보·명세)과 불일치하는 경우.
- 같은 계좌에서 원인 미상의 UNKNOWN 이 반복되는 중인 경우 — 근본 원인 파악 전 해제 금지.

## 6. 불변식

- 조정은 멱등하다. 조정은 읽기 전용 브로커 조회만 하며 주문을 전송하지 않으므로 반복해도 브로커
  주문이 늘지 않는다. 첫 조정으로 attempt 가 종결되면 이후 조정 요청은 409(상태 충돌)로 거부된다.
- 조정 결과·상태 전이·계좌 잠금은 같은 트랜잭션에서 커밋된다.
- 진입·판정·잠금은 `order_reconciliation_actions` 에, 해제는 `kill_switch_ledger` 에 감사로 남는다.
- 브로커 응답의 원문 식별자·자격증명은 로그·메트릭 label·알림 payload 에 남기지 않는다(SPEC:1151).
