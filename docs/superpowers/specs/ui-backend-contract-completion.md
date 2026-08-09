# UI–Backend Contract Completion

## 범위

- Dashboard가 호출하는 연결 소유 시세, 호가, 캔들, 환율, 캘린더, 랭킹, 경고,
  투자자 동향, 수수료, 매수가능금액, 매도가능수량 경로를 Spring 계약으로 고정한다.
- Toss/provider가 제공하지 않는 값은 합성하지 않고 `UNAVAILABLE`과
  `PROVIDER_UNSUPPORTED`를 반환한다. 실제 스냅샷이 오래됐거나 일부 필드가 없으면
  실제 데이터와 `DEGRADED` 품질 플래그를 함께 반환한다.
- 연결 소유권과 `ACTIVE` 준비 상태를 서버에서 검증하고, 프론트는 dashboard 준비 후
  연결 종속 API를 조회한다.
- 주문 정정은 `POST /api/v1/live-orders/{id}/modify`를 사용하며, step-up,
  allowlist/risk, audit, append-only idempotency ledger를 거친다. 같은 키의
  `IN_FLIGHT` 재시도는 두 번째 provider 호출 없이 `UNKNOWN`으로 재생한다.
- 조건주문은 현재 Toss/provider 및 주문 원장에 근거가 없어 UI 호출과 가짜 목록을 제거한다.
- E2E fixture는 등록되지 않은 frontend API를 빈 성공 응답으로 처리하지 않고 실패시킨다.

## 검증 기준

- backend contract/unit/integration test가 응답 envelope, provider unsupported,
  owner/readiness, live modify replay를 검증한다.
- frontend API/unit test가 live modify 경로, idempotency, step-up header를 검증한다.
- E2E state matrix가 loading/empty/error/partial/stale/unsupported를 360, 768,
  1280, 1440 viewport에서 확인하고 미등록 API를 허용하지 않는다.
