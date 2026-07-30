# 예측 ingestion API key delta

## 범위

- session 인증 사용자가 외부 prediction producer용 API key를 발급·조회·rotation·폐기한다.
- API key 인증은 기존 batch prediction POST에만 허용한다.
- 단건 POST, session 기반 batch POST, GET, 채점, 집계 의미는 변경하지 않는다.
- UI, rate limit, 모델 실행, 자동 예측, 주문 연동은 추가하지 않는다.

## key와 저장

- 원문은 `tpik_` prefix와 256-bit 난수로 만들고 발급·rotation 응답에서 한 번만 반환한다.
- PostgreSQL에는 사용자, model/contract scope, SHA-256 hash, 표시용 prefix, 상태와
  `createdAt`, `lastUsedAt`, `revokedAt`만 저장한다.
- 목록 응답과 오류에는 원문과 hash를 포함하지 않는다.
- audit/log에 Authorization 값, 원문 key, hash, request payload를 기록하지 않는다.

## 인증과 scope

- batch 경로의 `Authorization: Bearer <key>`만 API key 인증 대상으로 처리한다.
- 다른 API는 기존 session 인증만 허용한다.
- session batch 요청은 기존 CSRF 요구와 응답 contract를 유지한다.
- API key의 model/contract scope와 다른 batch 항목은
  `API_KEY_SCOPE_MISMATCH` 항목 실패로 처리하며 quote나 write를 수행하지 않는다.
- scope가 맞아도 기존 규칙대로 등록된 `ACTIVE` model/contract만 생성할 수 있다.
- 활성 key 인증이 성공한 경우에만 `lastUsedAt`을 갱신한다.

## lifecycle과 동시성

- rotation은 한 transaction에서 기존 ACTIVE key를 즉시 REVOKED로 바꾸고 같은 scope의
  새 key를 발급한다.
- 폐기와 rotation은 이미 비활성인 key에 대해 상태를 되돌리지 않는다.
- hash unique 제약과 상태/시각 check 제약으로 저장 불변식을 보장한다.
- model/contract foreign key로 scope가 존재하도록 하고 사용 이력을 삭제로 훼손하지 않는다.

## TDD와 검증

- 원문 1회 반환, DB hash-only 저장, 목록 비노출
- 사용자별 조회와 소유권 격리
- rotation 즉시 폐기와 새 key 인증
- 폐기 key/잘못된 key 인증 거부 및 `lastUsedAt` 비갱신
- batch 전용 접근, session/CSRF 회귀
- model/contract 항목별 scope enforcement와 quote/write 차단
- Flyway V22 제약과 최신 버전 assertion
- 구현 전체 code review 1회
- backend `./mvnw clean verify`, dashboard `npm test`,
  prediction 변경 파일의 `com.jmj.trade.order` import 0건
