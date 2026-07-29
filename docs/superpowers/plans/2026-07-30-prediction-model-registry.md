# 예측 모델 레지스트리 구현 계획

**목표:** 사용자 단위 불변 모델·계약 버전 레지스트리를 추가하고 ACTIVE 조합만 기존
prediction 생성에 사용한다.

**제약:** prediction GET·outcome·집계·주문 동작 불변, 새 dependency 없음, V20 사용.

## 1. DB 계약을 TDD로 고정

- `PredictionModelRegistryIntegrationTest`에 V20 backfill, 불변 열, 단방향 deprecate,
  사용 중 삭제 금지 테스트를 먼저 추가하고 실패를 확인한다.
- `V20__create_prediction_model_registry.sql`에 registry table, check/unique/index,
  backfill, composite FK, immutability trigger를 최소 SQL로 추가한다.
- Flyway 최신 버전 assertion을 20으로 갱신한다.

## 2. Backend API를 TDD로 추가

- registry 통합 테스트에 등록·결정적 목록·중복·사용자 격리·idempotent deprecate·
  미사용 삭제·사용 중 삭제 충돌을 먼저 추가하고 실패를 확인한다.
- prediction 통합 테스트에 미등록/deprecated 거부와 quote 미호출, ACTIVE 성공,
  저장 transaction의 최종 잠금 검사를 먼저 추가하고 실패를 확인한다.
- `PredictionModelRegistryService`, controller, exception만 추가한다.
- 기존 `AnalysisPredictionService`는 registry 선검사와
  `TransactionTemplate` 기반 `FOR SHARE` 재검사만 추가한다.
- BrokerAdapter 의존 bean 조립은 `CredentialVaultConfiguration`에 유지한다.

## 3. Dashboard를 TDD로 추가

- dashboard 테스트에 registry API 요청, 등록/목록/deprecate/delete UI,
  ACTIVE 선택과 활성 항목 없음 상태를 먼저 추가하고 실패를 확인한다.
- `lib/api.js`, 기존 analysis outcome view와 page state만 최소 수정한다.
- 과거 prediction 조회 필터는 그대로 둔다.

## 4. 검증·리뷰·통합

- backend/dashboard targeted tests와 `git diff --check`를 실행한다.
- 구현 전체에 대해 code review를 정확히 1회 받고 blocker를 수정한다.
- `./mvnw clean verify`, `npm test`, 변경 파일의
  `com.jmj.trade.order` import 0건을 검증한다.
- feature 커밋을 만들고 base에 squash merge한 뒤 push한다.
- 원격 CI가 있으면 완료 상태를 확인한다.
