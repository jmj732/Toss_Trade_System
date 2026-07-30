# Prediction API key weighted rate limit delta

## 목표

- API key 기반 batch prediction 제한을 HTTP 요청 수가 아닌 제출 항목 수로 계산한다.
- 한 요청의 전체 항목 수가 잔여 한도를 넘으면 요청 전체를 부작용 없이 거부한다.

## 결정

- 기존 `prediction.ingestion-api-key.rate-limit.limit`는 구간당 허용 prediction 항목 수를
  뜻한다. `window`, 429 응답, `Retry-After`와 `retryAt` 계약은 유지한다.
- 인증 filter는 batch JSON의 `items` 배열 길이를 계산하고 원문 body를 controller에 그대로
  전달한다. session 인증 batch는 filter를 타지 않는다.
- Redis Lua는 현재 counter와 잔여량을 확인하고 허용되는 요청만 항목 수만큼 `INCRBY`한다.
  초과 요청은 quota를 소비하지 않는다. 증가, 최초 TTL 설정, 허용 판정을 한 script에서
  수행해 다중 인스턴스 원자성을 유지한다.
- 요청 weight가 잔여 한도를 넘으면 전체 요청을 429로 거부한다. controller를 호출하지
  않으므로 quote, prediction 저장, `lastUsedAt` 갱신이 없다.
- Redis 오류는 기존처럼 503 fail-closed로 처리한다. scope, idempotency, 항목별 batch 결과,
  session 인증과 단건 prediction 계약은 변경하지 않는다.

## 검증

- 여러 limiter 인스턴스가 가중치를 원자적으로 공유한다.
- 잔여 한도를 넘는 다항목 요청은 전체 429이며 `Retry-After`를 반환한다.
- 거부 시 quote, prediction 저장, `lastUsedAt` 갱신이 없다.
- session batch와 Redis 장애 fail-closed 회귀를 유지한다.
