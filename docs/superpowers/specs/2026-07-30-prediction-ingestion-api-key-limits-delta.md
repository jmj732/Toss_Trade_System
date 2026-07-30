# Prediction ingestion API key limits delta

## 목표

- prediction ingestion API key에 선택적 만료 시각을 추가한다.
- API key 기반 batch prediction 요청만 Redis 기반 분산 rate limit으로 보호한다.
- 거부 경로에서 quote, prediction 저장, `lastUsedAt` 갱신이 발생하지 않게 한다.

## 결정

- V23에서 `prediction_ingestion_api_keys.expires_at`을 nullable `TIMESTAMPTZ`로 추가한다.
  발급 요청은 선택적 `expiresAt`을 받고, rotation에서 생략하면 기존 key 만료 시각을
  상속한다. 새 만료 시각은 생성 시각보다 뒤여야 하며 이후 변경할 수 없다.
- rate limit은 Redis Lua로 counter 증가와 TTL 설정을 한 번에 수행한다. key별 허용량과
  구간은 `prediction.ingestion-api-key.rate-limit.limit` 및 `window`로 설정한다.
- 인증 순서는 ACTIVE key 조회와 만료 확인, Redis 제한 판정, DB의 ACTIVE/만료 재검증과
  `lastUsedAt` 갱신, batch 실행 순서다. session 인증 요청과 단건 API는 이 경로를 타지 않는다.
- 제한 초과는 `429 Too Many Requests`, `Retry-After` 초 단위 헤더와 ISO-8601
  `retryAt` 응답 필드를 반환한다.
- Redis 판정 실패는 fail-closed `503 Service Unavailable`로 반환한다. 이 경우에도
  quote, prediction 저장, `lastUsedAt` 갱신은 없다.
- 만료 및 rate-limit 거부는 append-only audit table에 key UUID, 사용자 UUID, 공개 prefix,
  고정 사유, 발생 시각만 기록한다. 원문 key, 전체 hash, 요청 payload는 저장하거나 로그하지 않는다.
- Micrometer counter `trade.prediction.ingestion.api.key.rejected`에 저카디널리티 `reason`
  태그(`expired`, `rate_limited`, `redis_unavailable`)를 사용한다.
- 기존 scope, rotation의 즉시 폐기, revoke, session/단건/batch 응답 의미와 주문 모듈
  경계는 유지한다. UI, 다른 API key 인증, 모델 실행, 주문 연동은 추가하지 않는다.

## 알려진 제한

- 고정 구간 counter는 구간 만료 직후 새 허용량이 열리므로 경계에서 순간 burst가 가능하다.
  실제 트래픽에서 더 정밀한 평활화가 필요할 때 token bucket으로 교체한다.

## 검증

- 발급·조회·rotation의 만료 시각 저장 및 schema 불변성
- 만료 key의 401 거부와 side effect 부재
- 다중 limiter 인스턴스의 원자적 동일 key 제한
- 429 `Retry-After`/`retryAt`과 side effect 부재
- Redis 장애 503 fail-closed와 기존 인증 데이터 보존
- session batch 비영향, scope·rotation·폐기 회귀
- audit/metric의 고정 안전 식별자와 secret/payload 비노출
