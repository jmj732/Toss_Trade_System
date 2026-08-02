# UNKNOWN attempt 조정 delta

## 범위

- `UNKNOWN` 제출 시도의 조정 절차를 완성한다. MVP 완료 기준 7번(SPEC:1055) —
  "UNKNOWN attempt 는 CLOSED 미검색을 미접수로 오판하지 않고 조정된다".
- 수동 조정 API, 운영 알림, 계좌별 신규 주문 잠금(SPEC:1099-1100, SPEC:1121).
- `MANUAL_REVIEW_REQUIRED` 해제는 운영자의 명시 행위로만 가능하게 한다.
- `docs/ops/order-reconciliation-runbook.md` 를 쓰고 **장애 주입으로 실제 검증**한다.
- 실거래 진행이 확정됐다(Phase 0 Q2). 이 항목은 E6 의 마지막 선행 필수다.

## 급소 — CLOSED 미검색 오판

`ReconciliationDecision` 은 `BROKER_ORDER_FOUND`, `RETRY_SAME_KEY_ALLOWED`,
`MANUAL_REVIEW_REQUIRED` 중 하나를 고른다. 위험은 명확하다.

**OPEN 만 조회하고 못 찾았다는 이유로 `RETRY_SAME_KEY_ALLOWED` 를 고르면, 실제로는 이미
체결된 주문을 재전송해 실거래에서 중복 주문이 나간다.** 되돌릴 수 없는 손실이다.

- 따라서 `RETRY_SAME_KEY_ALLOWED` 는 **OPEN 과 CLOSED 를 모두 조회해 양쪽 모두에서 확실히
  없음을 확인한 경우에만** 고를 수 있다. B4 의 `BrokerOrderPort.getOrders(OPEN|CLOSED)` 를 쓴다.
- 조회가 하나라도 **실패·부분 성공·미확정이면 `MANUAL_REVIEW_REQUIRED`** 다. 재시도가 아니다.
- "찾지 못함" 과 "찾을 수 없었음" 을 구분한다. 후자는 절대 미접수로 해석하지 않는다.

## 처리 불변식

- 조정은 멱등하다. 같은 attempt 를 여러 번 조정해도 브로커 주문이 늘지 않는다.
- 조정 결과와 상태 전이는 같은 트랜잭션에서 커밋한다.
- `MANUAL_REVIEW_REQUIRED` 진입 시 **해당 계좌의 신규 주문을 잠근다.** E4 의 `ACCOUNT` 범위
  kill switch 를 재사용한다. 새 잠금 메커니즘을 만들지 않는다.
- **해제는 운영자의 명시 행위로만** 가능하다. 시간 경과·재시도·스케줄러가 자동 해제하지
  않는다. 해제는 E2 의 step-up 재인증을 요구한다.
- 조정 진입·판정·해제 전부 감사 레코드를 남긴다. 행위자·시각·사유·근거를 포함한다.
- 운영 알림은 기존 notification outbox 경로를 재사용한다. 새 알림 인프라를 만들지 않는다.
- 브로커 조회 응답의 원문 식별자·자격증명을 로그·메트릭 label 에 남기지 않는다(SPEC:1151).

## 런북

`docs/ops/order-reconciliation-runbook.md` 에 다음을 쓴다.

- UNKNOWN 이 발생하는 조건과 관측 방법
- 운영자가 확인해야 할 것(OPEN/CLOSED 조회 결과, 계좌 잠금 상태)
- 잠금 해제 절차와 그 전에 반드시 확인할 항목
- 해제하면 안 되는 상황
- 문서에 적힌 절차를 **장애 주입 테스트가 실제로 따라간다.** 문서와 테스트가 어긋나면 실패다.

## TDD와 검증

- OPEN 에 없고 CLOSED 에 **있으면** `BROKER_ORDER_FOUND` — 재전송하지 않음
- OPEN·CLOSED 모두 조회 성공 + 양쪽 모두 없음 → `RETRY_SAME_KEY_ALLOWED`
- **CLOSED 조회 실패 → `MANUAL_REVIEW_REQUIRED`** (미접수로 오판하지 않음)
- **OPEN 만 조회하고 CLOSED 를 건너뛴 상태로는 `RETRY_SAME_KEY_ALLOWED` 에 도달 불가**
- 조정 멱등 — 반복 조정이 브로커 주문을 늘리지 않음
- `MANUAL_REVIEW_REQUIRED` 진입 시 해당 계좌 신규 주문 잠김
- 자동 해제가 일어나지 않음(시간 경과·재시도·스케줄러)
- 해제에 step-up 필요, 없으면 401
- 해제 후 신규 주문 재개
- 조정 진입·판정·해제 감사 레코드
- 운영 알림 발행
- **장애 주입**으로 MVP 완료 기준 7번 재현 및 검증
- backend `./mvnw clean verify`
