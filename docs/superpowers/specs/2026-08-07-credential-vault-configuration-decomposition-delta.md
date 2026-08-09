# Credential vault configuration decomposition delta

## 배경

`broker/connection/CredentialVaultConfiguration.java`(336줄)가 order·prediction·account·broker의
빈 22개를 조립하는 전역 조립소였다. 이 파일 하나가 `broker→order`(20종), `broker→prediction`(10종),
`broker→account`(3종) 의존 간선의 거의 전부를 만들었고, 그 결과
`{account, analysis, broker, observability, order, prediction, security}` 7개 모듈이 하나의 순환
덩어리를 이뤘다. 참조 중 다수가 `import` 없는 인라인 FQN이라 일반 grep으로는 보이지 않았다.

부수 피해로 order 클래스 다수가 **외부 조립소에서 `new` 되기 위해서만** public이었다.

ArchUnit이 `@Configuration`을 통째로 면제하기 때문에(`ModuleBoundaryTest.java:137-139`) 이 위반은
전부 무검사로 통과하고 있었다.

## 결정 델타

- **빈을 소유 모듈로 이관.** 22개 중 17개를 옮기고 broker/connection 소유 6개만 잔류(336 → 53줄).
  - `credentialSecureRandom` → `security/CredentialRandomnessConfiguration`.
    `predictionIngestionApiKeyService`와 `orderApprovalStepUpService`가 공유하던 빈이라 어느 도메인
    모듈에도 자연스럽게 속하지 않는다. `order→security`·`prediction→security`·`broker→security`가
    전부 허용 의존이므로 security가 합법적 소유자다.
  - `accountSync{Transactions,Service}` → `account/AccountSyncConfiguration`
  - order 빈 8개 → `order/CredentialedOrderStackConfiguration`
  - prediction 빈 7개 → `prediction/PredictionIngestionConfiguration`,
    `prediction/AnalysisPredictionConfiguration`

- **조건은 축자 복제로 보존.** 새 설정 5개 전부에 클래스 레벨
  `@ConditionalOnProperty(prefix="broker.credentials", name="enabled", havingValue="true")`를 문자
  그대로 붙인다. `compose.yaml:79`가 `BROKER_CREDENTIALS_ENABLED: "false"`이므로 기본 프로파일에서
  이 빈들은 생성되지 않아야 하고, 조건을 놓치면 주문 스택 전체가 켜진다.
  선례는 `refresh/ScheduledPortfolioRefreshConfiguration.java:16-21`. 모듈별 프로퍼티 신설은
  하지 않는다 — 이 프로퍼티는 이미 12개 클래스가 직접 참조하는 credentialed 스택 전역 스위치다.

- **`@Bean` 레벨 조건을 중첩 클래스로 승격하지 않는다.** `real-order.enabled`,
  `prediction.evaluation.enabled`, `prediction.ingestion-api-key.cleanup.enabled` 모두 원래 구조 그대로
  옮긴다. 조건 평가 순서와 `@ConditionalOnBean` 상호작용이 미묘하게 달라질 여지를 없앤다.

- **`tossCredentialProvider`는 broker/connection 잔류.** `broker/toss/TossBrokerConfiguration.java:16`이
  `@ConditionalOnBean(TossCredentialProvider.class)`라, 이 빈이 일반 `@Configuration` 밖으로 나가면
  브로커 어댑터 전체가 비활성화된다.

- **`@Bean` 메서드명 불변.** 빈 이름이 곧 동작 보존 오라클의 키다.

- **회수한 가시성.** 조립소가 사라져 외부 참조가 0이 된 order 클래스 7개를 package-private로 강등:
  `AccountOwnershipRevalidationCheck`, `SellableQuantityRevalidationCheck`,
  `SameSymbolOpenOrderRevalidationCheck`, `KillSwitchRevalidationCheck`,
  `BrokerOrderPortReconciliationProbe`, `PreSubmitRevalidationCheck`, `PreSubmitContext`.
  이것이 해체의 목적이다 — 조립소는 캡슐화를 강제로 열어두고 있었다.

## 검증

- `BeanInventoryDefaultProfileTest`(69빈)와 `BeanInventoryCredentialedProfileTest`의 골든 목록이
  **한 줄도 바뀌지 않고** 통과한다. 빈 이름·타입 집합이 동일하다는 것이 동작 보존의 정의다.
  골든을 고쳐야 하는 상황은 동작을 바꾼 것이므로 되돌린다.
- 가시성 강등은 `mvnw test-compile`이 최종 판정한다. 컴파일이 통과하면 선언 패키지 밖 참조가 없다.
- `./mvnw clean verify` 665 tests, 0 failures 유지.

## 미해소 (후속)

- `@Configuration` 면제(`ModuleBoundaryTest.java:137-139`)를 별도 프로즌 규칙으로 좁히는 작업.
  해체 후 남는 config 위반은 `security.SecurityConfiguration -> prediction`과
  `prediction.AnalysisPredictionConfiguration -> broker` 2건으로 예상된다.
- `broker↔account` 순환의 잔여분(`BrokerConnectionController`·`BrokerConnectionErrorHandler`)은
  `FROZEN_MODULE_EDGES`에 이미 고정돼 있고 connection 모듈 분리 시 해소된다.
