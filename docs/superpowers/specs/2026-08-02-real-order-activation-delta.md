# Real Order Activation Delta — Toss OpenAPI 1.2.5

## 기준 계약

- 공식 source of truth: `https://developers.tossinvest.com/llms.txt`
- canonical document: `https://openapi.tossinvest.com/openapi-docs/latest/openapi.json`
- 구현 기준: OpenAPI `1.2.5` (2026-08-02 확인)
- 주문 API는 `/api/v1/orders` 생성·목록, `/api/v1/orders/{orderId}` 단건 조회,
  `/modify`, `/cancel`을 사용한다.
- 계좌 주문 호출에는 `Authorization: Bearer`와 `X-Tossinvest-Account`를 보낸다.
- `clientOrderId`는 서버가 생성하지 않는 주문 멱등 키다. 최대 36자이며
  `[A-Za-z0-9_-]+`만 허용되고 유효기간은 10분이다. 생성 응답의
  `clientOrderId` echo가 내부 키와 다르면 주문을 연결하지 않고 `MANUAL_REVIEW`로 보낸다.
- 주문 상태는 `PENDING`, `PENDING_CANCEL`, `PENDING_REPLACE`, `PARTIAL_FILLED`,
  `FILLED`, `CANCELED`, `REJECTED`, `CANCEL_REJECTED`, `REPLACE_REJECTED`,
  `REPLACED`다. 미지 상태는 성공으로 매핑하지 않는다.
- `OPEN`은 전량 응답이고 `CLOSED`는 `nextCursor`/`hasNext`를 따라 모든 페이지를 읽는다.
- 생성은 quantity 방식만 내부 주문에서 사용한다. `LIMIT`은 price를 함께 보내고,
  `MARKET`은 price를 보내지 않는다. `DAY`를 명시하며 high-value 확인은 내부 한도에서
  먼저 차단한다.

## 목표

승인된 `OrderIntent`만 별도 live dispatch 경로로 Toss에 제출한다. paper broker,
analysis, LLM 경로에는 이 포트를 노출하지 않는다.

## 안전 불변식

1. `broker.credentials.enabled=true`와 명시적 `real-order.enabled=true`가 아니면 live
   dispatch bean과 endpoint가 없다.
2. 사용자·활성 connection·내부 `broker_account_id`·Toss `accountSeq`가 등록된
   allowlist 행으로 하나의 계좌에 매핑되지 않으면 차단한다.
3. step-up 토큰은 실제 live dispatch 직전에 소비하며, OIDC authorization request에
   `max_age`를 넣어 최근 재인증을 요구한다.
4. approval decision, 제출 직전 portfolio 재조회, allowlist/소유권/kill switch,
   주문 한도와 일일 누적 한도를 모두 통과해야 외부 POST를 호출한다.
5. 내부 멱등 키와 Toss `clientOrderId`는 같은 canonical 값을 사용한다. echo 누락·불일치,
   계좌 불일치, 알 수 없는 상태는 `MANUAL_REVIEW_REQUIRED`로 전환한다.
6. timeout, network/5xx, partial fill, reject, cancel 결과는 자동 재전송하지 않는다.
   UNKNOWN은 기존 reconciliation ledger와 runbook으로만 조정한다.

## TDD 수용 기준

- 1.2.5 manifest/version test가 주문 경로·필드·상태·client ID 규칙을 고정한다.
- Toss adapter contract test가 정확한 URL, method, account header, request body,
  response mapping, CLOSED pagination을 검증한다.
- 생성 timeout과 client ID mismatch가 추가 HTTP 호출 없이 manual review 경로로 간다.
- 승인되지 않은 intent, paper intent, allowlist 누락, kill switch, stale/partial/
  sellable-quantity unknown, 주문/일일 한도 초과는 Toss POST를 호출하지 않는다.
- OIDC authorization request에 `max_age`가 포함되고, analysis/paper 패키지에는 live
  dispatch 참조가 없다.
- WireMock 장애 주입과 등록된 소액 계좌의 full workflow가 실행된다. 기본 mock stack은
  live order mapping을 로드하지 않는다.

## 제외 및 잔여 운영 작업

- 실제 Toss 자격증명으로 주문을 보내는 운영 E2E는 테스트에 포함하지 않는다. 소액 allowlist
  테스트는 명시적 mock/환경 플래그에서만 실행된다.
- UNKNOWN 후 재전송은 reconciler가 완전한 OPEN/CLOSED 조회와 10분 window를 확인한 뒤
  기존 runbook 정책에 따라 수행한다. dispatch 호출 자체가 재시도하지 않는다.
- 부분 체결 이후 자동 추가 주문·자동 취소·자동 정정은 만들지 않는다.
