# 예측 symbol 정규화 delta

## 범위

- 단건·batch prediction 입력의 symbol을 `trim` 후 `Locale.ROOT` 대문자로 정규화한다.
- canonical symbol은 검증, batch 멱등성 비교, quote 조회·coalescing key, 신규 저장에
  동일하게 사용한다.
- 기존 prediction 조회, outcome 채점·집계, API key scope, 주문 모듈 경계는 변경하지
  않는다.
- migration과 기존 데이터 backfill은 하지 않는다. 기존 저장 row는 GET에서 저장값
  그대로 반환한다.

## 허용 형식

- canonical symbol 길이는 `1..30`이다.
- ASCII 영문·숫자 구간과 단일 `.` 또는 `-` 구분자만 허용한다.
- 형식은 `[A-Z0-9]+([.-][A-Z0-9]+)*`이며 빈 값, 내부 공백, 비ASCII, 선행·후행·연속
  구분자는 `INVALID_INPUT`으로 거부한다.
- 거래소 추론, ticker alias 변환, 요청 간 캐시를 추가하지 않는다.

## 처리 불변식

- 단건은 canonical symbol로 ACTIVE version 확인 전 입력을 확정하고 quote·저장을
  수행한다.
- batch는 canonical command로 scope와 `(user, clientRequestId)` replay를 판정한다.
  같은 canonical symbol의 신규 항목은 기존 batch-local quote cache 하나를 공유한다.
- 기존 비canonical row와 canonical 요청의 replay 비교는 저장값을 바꾸지 않으므로
  conflict일 수 있다. backfill 없는 호환성 선택이다.

## TDD와 검증

- 단건 입력의 trim·대문자 정규화가 quote와 저장에 함께 적용
- 대소문자·주변 공백만 다른 batch 항목의 quote 1회 coalescing
- canonical symbol 기준 idempotent replay
- 빈 값과 허용되지 않은 형식의 quote 없는 거부
- 기존 비canonical 저장 row의 GET 값 보존
- 구현 전체 code review 1회
- backend `./mvnw clean verify`, dashboard `npm test`,
  prediction 변경 파일의 `com.jmj.trade.order` import 0건
