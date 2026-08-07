# Module boundary safety net delta

## 배경

`broker/connection/CredentialVaultConfiguration.java`(336줄)는 order·prediction·account·broker의
빈 22개를 조립하는 전역 조립소다. 다음 PR에서 이를 모듈별로 해체하는데, 해체가 동작을 바꾸지
않았음을 증명할 오라클이 지금 없다. 기존 618개 테스트는 "빈이 존재한다"만 확인할 뿐 "빈 집합이
동일하다"를 검증하지 않는다. 이 PR은 프로덕션 코드를 한 줄도 건드리지 않고 그 오라클만 만든다.

## 결정

- **빈 인벤토리 골든.** 컨텍스트의 모든 빈 중 타입 FQN이 `com.jmj.trade.`로 시작하고 설정 루트가
  아닌 것을 `"빈이름 : 타입FQN"`으로 만들어 정렬한 리스트를, 클래스 상수로 얼린 목록과
  `containsExactlyElementsOf`로 대조한다. 타입은 `context.getType(beanName)`으로 얻고 null이면
  건너뛰며, AOP 프록시는 `ClassUtils.getUserClass`로 원본 타입을 복원한다.
- **설정 루트 제외.** `@Configuration`/`@ConfigurationProperties`를 (메타 애노테이션 포함)
  가진 클래스는 오라클에서 뺀다. 조립소를 쪼개면 설정 클래스가 자기 이름(FQN)으로 등록하는 빈의
  이름이 반드시 바뀌기 때문(예: 내부 정적 `...$PredictionIngestionApiKeyCleanupConfiguration`이
  이사하면 이름이 달라짐)이다 — 이는 의도된 변경이라 신호에서 빼야 한다. 반대로 `@Bean` 메서드가
  만든 빈의 이름·타입은 메서드가 다른 설정 클래스로 이동해도 바뀌지 않으므로, 그 집합이 동일하게
  유지되는 것이 곧 "동작 보존"의 정의다. 판정은 `AnnotatedElementUtils.hasAnnotation`으로 하여
  `@SpringBootApplication`·`@AutoConfiguration`(예: `TossBrokerConfiguration`)·`@TestConfiguration`
  같은 메타 설정 루트가 인벤토리에 새는 것도 막는다.
- **두 프로파일.** 기본 프로파일(`broker.credentials.enabled` 미설정)은 조립소 빈이 생성되지 않은
  상태(운영 기본값), credentialed 프로파일은 `broker.credentials.enabled`와 prediction 스케줄러
  플래그로 조립소가 켜는 빈이 살아있는 최대 부팅 가능 상태를 얼린다. 두 골든의 차이가 곧 조립소가
  조건부로 조립하는 빈 집합이다.
- **`real-order.enabled`는 켜지 않는다.** 이 플래그를 전체 컨텍스트에서 켜면 `tossOrderPort`
  (`TossInvestBrokerAdapter`, `BrokerOrderPort`이자 `BrokerAdapter`)가 `tossBrokerAdapter`와 함께
  두 번째 `BrokerAdapter` 후보가 되어, `@Primary`/`@Qualifier` 없이 단일 `BrokerAdapter`를
  주입받는 `paperOrderWorkflowService`에서 모호성으로 부팅이 실패한다(프로덕션 선재 결함;
  `src/main` 불가침). 따라서 credentialed 골든은 real-order 게이트 빈 2개
  (`liveOrderSafetyLedger`, `liveOrderActivationService`)를 뺀 최대 부팅 가능 집합이다.
  — **후속(`fix/broker-adapter-ambiguity`)**: `tossOrderPort`에 `@Bean(defaultCandidate = false)`를
  붙여 이 선재 결함을 해소했고(`@Primary`는 통합 테스트의 `BrokerAdapter` 목 배선을 뒤집어 폐기),
  credentialed 골든을 real-order 활성 상태까지 확장했다(105→109빈). 근거는
  `2026-08-07-broker-adapter-ambiguity-delta.md` 참고.
- **`tossOrderPort` 이름 결합 고정.** `@Qualifier("tossOrderPort")` 문자열이
  `CredentialVaultConfiguration`(2곳)·`RealOrderCanaryConfiguration`(1곳)에 하드코딩돼 있고
  정의처는 `TossBrokerConfiguration`다. 컴파일러도 ArchUnit도 못 잡는 결합이라, 부팅 가능한
  `ApplicationContextRunner` 슬라이스로 빈 이름=`tossOrderPort`·타입=`BrokerOrderPort` 할당가능을
  못 박는다(다음 PR에서 참조가 이사해도 정의 계약이 유지되는지 감시).

## 검증

- `BeanInventoryDefaultProfileTest`(기본, 69빈)와 `BeanInventoryCredentialedProfileTest`
  (credentialed, 105빈)가 정렬된 실제 빈 집합을 얼린 골든과 정확히 대조한다. credentialed가 켜는
  36빈 차이 안에 조립소 핵심 22빈이 포함된다.
- vacuous 방지: 얼린 골든에서 한 줄을 지우면 `containsExactlyElementsOf`가 즉시 실패함을
  확인한다(실제 수행). 빈 골든에 실제 집합을 대조하는 부트스트랩 실패 출력으로 최초 목록을 확보했고
  임시 출력 코드는 제거했다.
- `TossOrderPortWiringTest`가 `real-order.enabled=true` 슬라이스에서 `tossOrderPort` 빈의
  이름·타입 계약을 단언한다.
- `./mvnw clean verify` 전체 그린으로 세 테스트가 기존 스위트와 함께 통과함을 확인한다.
