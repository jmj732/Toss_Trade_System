# 예측 모델 레지스트리 delta

## 범위

- 사용자 단위로 예측 `modelVersion`과 `contractVersion` 조합을 등록하고 조회한다.
- 버전 조합은 생성 후 불변이며 상태만 `ACTIVE`에서 `DEPRECATED`로 한 번 전이한다.
- 기존 prediction, outcome, 집계 의미와 계산식은 변경하지 않는다.
- 모델 실행, 자동 예측, 주문 연동은 추가하지 않는다.

## 스키마

- Flyway V20에서 `prediction_model_versions`를 추가한다.
  - `id UUID PRIMARY KEY`
  - `user_id UUID NOT NULL REFERENCES users(id)`
  - `model_version VARCHAR(50) NOT NULL`
  - `contract_version VARCHAR(50) NOT NULL`
  - `status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'DEPRECATED'))`
  - `created_at TIMESTAMPTZ NOT NULL`
  - `deprecated_at TIMESTAMPTZ`
  - `UNIQUE (user_id, model_version, contract_version)`
- `deprecated_at`은 `ACTIVE`이면 `NULL`, `DEPRECATED`이면 필수다.
- 기존 `analysis_predictions`의 사용자·모델·계약 조합을 중복 제거해 `ACTIVE`로
  backfill한다.
- `analysis_predictions (user_id, model_version, contract_version)`에서 레지스트리의
  동일 열로 복합 외래 키를 추가한다. 사용 중인 버전 삭제는 PostgreSQL `RESTRICT`
  동작으로 거부한다.
- 외래 키 삭제 검사와 사용자·버전 조회를 위해
  `analysis_predictions (user_id, model_version, contract_version)` 인덱스를 추가한다.
- trigger는 `id`, `user_id`, `model_version`, `contract_version`, `created_at` 변경과
  `DEPRECATED`에서 다른 상태로의 변경을 거부한다. 허용되는 상태 변경은
  `ACTIVE → DEPRECATED`뿐이며 `deprecated_at`은 이 전이에서만 설정할 수 있고 이후
  변경할 수 없다.
- 별도 이력 테이블, soft delete, 사용 여부 캐시를 추가하지 않는다.

## API

- 기존 인증 사용자 경계를 사용한다. 다른 사용자의 항목은 조회·변경할 수 없다.
- 기존 broker credential feature 경계와 동일하게
  `broker.credentials.enabled=true`일 때 제공한다.
- endpoint:
  - `POST /api/v1/prediction-model-versions`
  - `GET /api/v1/prediction-model-versions`
  - `POST /api/v1/prediction-model-versions/{id}/deprecate`
  - `DELETE /api/v1/prediction-model-versions/{id}`
- 등록 요청은 `modelVersion`, `contractVersion`을 받는다. 공백 또는 50자 초과는
  `400 PREDICTION_MODEL_VERSION_INVALID_INPUT`, 중복은
  `409 PREDICTION_MODEL_VERSION_ALREADY_EXISTS`다.
- 응답은 `id`, `modelVersion`, `contractVersion`, `status`, `createdAt`,
  `deprecatedAt`을 포함한다.
- 목록은 `createdAt`, `id` 오름차순으로 결정적 정렬한다.
- deprecate는 이미 `DEPRECATED`여도 현재 항목을 반환하는 idempotent 명령이다.
- 존재하지 않거나 다른 사용자의 ID는 `404 PREDICTION_MODEL_VERSION_NOT_FOUND`다.
- 사용 중인 버전 삭제는 `409 PREDICTION_MODEL_VERSION_IN_USE`다.

## prediction 생성

- 기존 생성 요청·응답 필드는 유지한다.
- quote 호출 전 사용자 레지스트리에서 요청한 조합이 `ACTIVE`인지 확인한다.
  미등록 또는 `DEPRECATED`면
  `409 ANALYSIS_PREDICTION_MODEL_VERSION_NOT_ACTIVE`이며 quote를 호출하지 않는다.
- quote 획득 뒤 짧은 저장 transaction에서 요청한 `ACTIVE` 레지스트리 행을
  `SELECT ... FOR SHARE`로 다시 확인하고 prediction을 insert한다. 잠금 획득 전에
  deprecate가 완료되면 같은 오류로 종료하며, 잠금 뒤 deprecate/delete는 insert
  commit까지 대기한다.
- 기존 baseline quote 획득, prediction 저장값, GET 순수성, outcome 채점,
  D1→D5→D20 순서와 집계 수학은 변경하지 않는다.
- `BrokerAdapter` 의존 `AnalysisPredictionService` bean은 계속
  `CredentialVaultConfiguration`에서 조립한다.

## Dashboard

- 기존 prediction 영역에 사용자 레지스트리 등록 폼과 목록을 추가한다.
- 목록에서 `ACTIVE` 항목을 deprecate하고 각 항목의 삭제를 요청할 수 있다.
  사용 중 삭제의 `409` 오류는 기존 mutation 오류 표면에 표시한다.
- prediction 생성의 모델·계약 자유 입력을 `ACTIVE` 조합 선택으로 바꾼다.
- 활성 항목이 없으면 prediction 생성 버튼을 비활성화하고 먼저 버전을 등록하라는
  안내를 표시한다.
- 기존 prediction 조회 필터는 과거·deprecated 버전 조회를 위해 자유 입력을
  유지한다.

## 테스트와 회귀

- migration은 backfill, 불변 열, 단방향 상태 전이, 사용 중 삭제 거부를 검증한다.
- API는 등록, 사용자별 목록, 중복, idempotent deprecate, 다른 사용자 격리,
  미사용 삭제와 사용 중 삭제 충돌을 검증한다.
- prediction 생성은 미등록/deprecated 조합 거부, 거부 시 quote 미호출,
  `ACTIVE` 조합 성공, 저장 직전 상태 재검사를 검증한다.
- dashboard는 등록·목록·deprecate·delete 호출과 활성 조합 선택, 활성 항목 없음
  상태를 검증한다.
- 기존 prediction GET, 채점, 집계 회귀 테스트를 유지한다.
- Flyway 최신 버전 assertion을 V20으로 갱신한다.
- 변경 파일에서 `com.jmj.trade.order` import가 0개인지 검증한다.
- 최종 검증은 backend `./mvnw clean verify`와 dashboard `npm test`를 실행한다.
