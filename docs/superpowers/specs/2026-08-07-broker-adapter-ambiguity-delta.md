# Broker adapter ambiguity delta

## 배경

`real-order.enabled=true` 로 전체 애플리케이션 컨텍스트가 부팅되지 않는 프로덕션 선재 결함이 있었다.
`TossBrokerConfiguration` 은 `tossBrokerAdapter`(반환형 `TossInvestBrokerAdapter`) 빈과, 같은
인스턴스를 `@Qualifier("tossOrderPort")` 로 노출하는 `tossOrderPort`(반환형 `BrokerOrderPort`) 빈을
등록한다. 그런데 `TossInvestBrokerAdapter` 는 `BrokerAdapter` 와 `BrokerOrderPort` 를 동시에
구현하므로, real-order 를 켜면 `BrokerAdapter` 타입 후보가 `tossBrokerAdapter`·`tossOrderPort`
둘로 늘어난다. `CredentialVaultConfiguration.paperOrderWorkflowService(BrokerAdapter, ...)` 등
한정자 없는 단일 `BrokerAdapter` 주입 지점에서 `src/main` 전체에 `@Primary` 가 0건이라
`NoUniqueBeanDefinitionException` 으로 부팅이 실패한다.

배포는 `compose.staging.credentialed.yaml` 의 `REAL_ORDER_ENABLED: "false"` 라 노출되지 않았으나,
실거래를 켜는 순간 기동 실패한다.

## 결정

- **`tossOrderPort` 에 `@Bean(defaultCandidate = false)` 를 붙인다.** 문제의 본질은 `tossOrderPort`
  가 `@Qualifier` 전용 빈인데도 `BrokerAdapter`/`BrokerOrderPort` 타입 자동 주입 후보로 새어
  타입 해석을 오염시키는 것이다. `defaultCandidate = false` 는 그 빈을 <em>기본</em> 타입 주입
  후보에서만 빼고, `@Qualifier("tossOrderPort")` 같은 명시적 한정자 주입은 그대로 허용한다
  (Spring Framework 6.2+, 본 프로젝트는 Boot 4.1 → Framework 7.0). 즉 오염만 정확히 제거하고
  기존 배선은 보존한다. 빈 이름·반환형·조건 애노테이션은 불변이다.
- **`real-order.enabled=false` 경로는 동작 무변화다.** 그 경우 `tossOrderPort` 빈 자체가 생성되지
  않아 `BrokerAdapter` 후보가 `tossBrokerAdapter` 하나뿐이다.
- **`@Qualifier("tossOrderPort")` 참조 보존.** `CredentialVaultConfiguration`(`liveOrderActivationService`,
  `reconciliationBrokerProbe`)·`RealOrderCanaryConfiguration`(`realOrderCanaryService`) 의 3개
  한정자 참조는 그대로 해석된다. `TossOrderPortWiringTest` 가 부팅 슬라이스로 이를 못 박는다.

### 검토했으나 채택하지 않은 안

- **`tossBrokerAdapter` 에 `@Primary`.** 처음 시도했으나 회귀 67건을 유발해 폐기했다. 통합 테스트
  12곳이 `@Bean ... brokerAdapter()` 로 `BrokerAdapter` 목을 심고, 프로덕션 배선은 파라미터명
  (`BrokerAdapter brokerAdapter`) 매칭으로 그 목을 고른다. `tossBrokerAdapter` 에 `@Primary` 를
  붙이면 이 파라미터명 매칭보다 `@Primary` 가 우선해, credentialed 프로파일의 모든 `BrokerAdapter`
  주입이 실제 Toss 어댑터로 뒤바뀌어 페이퍼 트레이딩 스위트가 503/`Toss OAuth token cache
  could not be read` 로 깨진다. `@Primary` 는 전역 우선순위라 국소 오염 제거에 부적합하다.
- **`tossOrderPort` 에 `@Bean(autowireCandidate = false)`.** `defaultCandidate` 와 달리 명시적
  한정자 주입까지 후보에서 빼서, 위 3개 `@Qualifier("tossOrderPort")` 참조가 깨진다. 부적합.
- **`BrokerOrderPort` 만 노출하는 위임 래퍼 신설.** 가장 명시적이나 실거래 경로 변경폭이 크고 신규
  클래스가 추가된다. 이 결함 해소에는 과하다.

## 검증

- **골든 확장.** `BeanInventoryCredentialedProfileTest` properties 에 `real-order.enabled=true` 를
  추가했다. 추가로 필요한 프로퍼티는 없었다(`RealOrderCanaryProperties` 검증은 부팅 시점이 아닌
  런타임 호출 시점에만 돈다). 이제 credentialed 골든이 real-order 게이트 빈까지 포함한 최대 부팅
  가능 집합(105→109빈)을 얼린다. 새로 포함된 빈: `tossOrderPort`, `liveOrderSafetyLedger`,
  `liveOrderActivationService`, `liveOrderActivationController`. `defaultCandidate = false` 는 빈
  등록 자체는 막지 않으므로 `tossOrderPort` 는 인벤토리에 그대로 잡힌다. 다음 해체 PR 이
  `liveOrderSafetyLedger`·`liveOrderActivationService` 를 이동시켜도 이 오라클이 이름·타입 보존을
  덮는다.
- **결함 실효 증명.** `defaultCandidate = false` 를 제거(평범한 `@Bean` 으로 복원)하면
  `BeanInventoryCredentialedProfileTest` 가 `UnsatisfiedDependencyException` →
  `NoUniqueBeanDefinitionException`(`BrokerAdapter` 후보 2개: `tossBrokerAdapter`, `tossOrderPort`)로
  컨텍스트 부팅 실패한다. 원복하면 그린. 수정이 실효가 있음을 확인.
- `./mvnw clean verify` 전체 그린으로 기존 스위트와 함께 통과함을 확인한다.
