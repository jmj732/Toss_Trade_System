# Event Intelligence Foundation — Delta Spec

## Scope

- `BrokerConnection` 소유 이벤트를 수동 입력 API로 저장하고 조회한다.
- 이벤트 저장 입력은 `source`, `sourceEventId`, `type`, `summary`,
  `affectedSymbols`, `occurredAt`이다.
- `collectedAt`은 서버 UTC 시각으로 기록한다.
- 외부 뉴스 수집, LLM, 이벤트버스, 자동 주문, 주문 제안은 구현하지 않는다.

## Identity and ownership

- 이벤트 ID는 서버 생성 UUID다.
- 중복 기준은 `(userId, brokerConnectionId, source, sourceEventId)`다.
- 중복은 PostgreSQL unique constraint로 원자적으로 차단하고 HTTP 409를 반환한다.
- 모든 조회·재분석은 인증 사용자와 broker connection 소유권을 함께 검증한다.

## Read API

- 단건 조회와 최신순 목록 조회를 제공한다.
- 목록은 기본 50개, 최대 100개로 제한한다.
- 영향 종목은 정규화된 대문자 symbol 배열로 반환한다.

## Event-triggered analysis

- 재분석 명령은 기존 `PortfolioAnalysisWorkflowService`를 호출한다.
- 분석 입력 선택, stale/partial/unknown 처리, 외부 호출/트랜잭션 분리는 기존 동작을 유지한다.
- `analysis_runs.trigger_event_id`로 실행 원인을 기록한다.
- 같은 이벤트는 RUNNING 또는 SUCCEEDED 분석을 하나만 허용한다.
- FAILED 분석은 재시도할 수 있다.

## Comparison

- 재분석 직전 최신 성공 결과와 신규 성공 결과를 비교한다.
- 이전 결과가 없으면 baseline 없는 비교로 저장한다.
- 영향 종목과 통화 합계에 대해 이전값, 신규값, 차이를 JSONB로 저장한다.
- 비교 row는 이벤트당 하나이며 UPDATE/DELETE를 금지한다.
- 비교 조회는 저장된 결과만 반환하며 분석이나 주문을 다시 실행하지 않는다.

## Tests

- PostgreSQL에서 소유권, 중복 이벤트, append-only 비교, 이벤트 재분석 중복을 검증한다.
- WireMock으로 기존 FastAPI 계약 호출과 이전/신규 비교 저장을 검증한다.
