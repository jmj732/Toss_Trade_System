package com.jmj.trade.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모듈 경계 아키텍처 테스트 (플랜 원장 A5).
 *
 * <p>SPEC(2026-07-26-us-equity-trading-platform-design.md):57 은 "Spring Modulith 대신 패키지
 * 경계와 아키텍처 테스트로 모듈성을 지킨다" 고 명시한다. 이 테스트가 그 아키텍처 테스트다.
 * 규칙은 delta 스펙(2026-07-31-module-boundary-archunit-delta.md)의 계약을 따른다.
 *
 * <p>SPEC 모듈명 ↔ 실제 패키지 매핑: identity→security, event→intelligence,
 * performance→prediction. portfolio/proposal 은 아직 없어 규칙을 쓰지 않는다.
 * audit/outbox 는 독립 패키지가 없고 order/notification 안에 있다.
 *
 * <p>vacuous pass 방지: 각 규칙은 대상 클래스가 실제로 존재함을 단언하거나
 * {@code allowEmptyShould(false)} 로 빈 집합 통과를 차단한다. 얼린(frozen) 위반은 코드가 아닌
 * 명시적 목록으로 선언하고, "실제 위반 집합 == 목록" 을 단언해 목록이 조용히 늘거나(신규 위반)
 * 낡은 채로 남지(phantom) 못하게 한다.
 */
class ModuleBoundaryTest {

    private static final String BASE = "com.jmj.trade";

    /**
     * SPEC:65-80 허용 의존 표를 실제 패키지 기준으로, 존재하는 모듈에 한해 옮긴 것.
     * 표에 없는 대상(audit/outbox/marketdata/portfolio)은 아직 독립 모듈이 아니므로 제외한다.
     * key 는 도메인 모듈, value 는 그 모듈이 의존해도 되는 도메인 모듈 집합.
     */
    private static final Map<String, Set<String>> ALLOWED_DEPENDENCIES = Map.ofEntries(
            Map.entry("security", Set.of()),                                  // identity → audit(없음)
            Map.entry("broker", Set.of("security")),                          // → identity, audit(없음)
            Map.entry("account", Set.of("broker", "security")),              // → broker, identity, audit/outbox(없음)
            Map.entry("analysis", Set.of("marketdata")),                   // → marketdata, outbox(없음)
            Map.entry("marketdata", Set.of()),                               // → broker는 generic provider 경계 밖
            Map.entry("intelligence", Set.of()),                             // event → marketdata/outbox(없음)
            Map.entry("risk", Set.of("account")),                            // → account, portfolio/marketdata(없음)
            Map.entry("order", Set.of("risk", "broker", "account", "inbox")),// → risk, broker, account, inbox, audit/outbox/marketdata(없음)
            Map.entry("prediction", Set.of("analysis")),                     // performance → analysis, marketdata(없음)
            Map.entry("inbox", Set.of()));                                    // → 없음

    private static final Set<String> DOMAIN_MODULES = ALLOWED_DEPENDENCIES.keySet();

    /**
     * 모듈 재설계 없이는 못 고치는, 명시적으로 얼린 도메인 간 의존(규칙 1).
     * 각 항목: "선언클래스 -> 대상모듈". 실제 위반 집합이 이 목록과 정확히 일치해야 한다.
     *
     * <ul>
     *   <li>broker.connection.BrokerConnection{Controller,ErrorHandler} -> account:
     *       브로커 연결/온보딩 애플리케이션 계층이 broker.connection 하위에 있어 account 동기화·
     *       조회·예외를 직접 오케스트레이션한다. 연결/온보딩 애플리케이션 모듈 분리 전까지 유지.
     *       (해소 예정: connection-module-extraction)</li>
     *   <li>prediction.AnalysisPrediction{Service,Controller} -> broker:
     *       marketdata 모듈 미분리. Quote/Currency/BrokerAdapter 가 broker 에 상주해 performance
     *       (prediction) 가 marketdata 대신 broker 를 참조한다.
     *       (해소 예정: B1-marketdata-extraction)</li>
     *   <li>intelligence.EventIntelligence{Service,Controller} -> analysis:
     *       event 재분석의 outbox 경유(AnalysisRecalculationRequested)가 아직 없어 analysis 를
     *       직접 참조한다.
     *       (해소 예정: E1-event-outbox-recalc)</li>
     * </ul>
     */
    private static final List<String> FROZEN_MODULE_EDGES = List.of(
            "com.jmj.trade.broker.connection.BrokerConnectionController -> account",
            "com.jmj.trade.broker.connection.BrokerConnectionErrorHandler -> account",
            "com.jmj.trade.prediction.AnalysisPredictionService -> broker",
            "com.jmj.trade.prediction.AnalysisPredictionController -> broker",
            "com.jmj.trade.intelligence.EventIntelligenceService -> analysis",
            "com.jmj.trade.intelligence.EventIntelligenceController -> analysis");

    /** 상한: 얼린 도메인 간 의존 항목 수가 이 값을 넘으면 실패(debt 누적 차단). */
    private static final int FROZEN_MODULE_EDGE_CAP = 6;

    /**
     * 규칙 3: intelligence → analysis 애플리케이션 서비스 직접 호출을 얼린 목록.
     * outbox 경유 재분석(E1) 구현 시 해소.
     */
    private static final Set<String> FROZEN_EVENT_TO_ANALYSIS_SERVICE =
            Set.of("com.jmj.trade.intelligence.EventIntelligenceService");

    /**
     * 규칙 5: 단일 인자 {@code findById(ID)} 호출을 얼린 선언 클래스 목록(전부 order 모듈).
     * order aggregate 조회를 userId 스코프 repository API 로 옮기기 전까지 유지.
     * (해소 예정: order-userid-scoping)
     */
    private static final Set<String> FROZEN_FIND_BY_ID_CALLERS = Set.of(
            "com.jmj.trade.order.LiveOrderActivationService",
            "com.jmj.trade.order.OrderIntentTransitionService",
            "com.jmj.trade.order.PaperTradingBroker",
            "com.jmj.trade.order.OrderSubmissionService",
            "com.jmj.trade.order.RealOrderCanaryService");

    private static final String SPRING_DATA_REPOSITORY = "org.springframework.data.repository.Repository";
    private static final String CONFIGURATION = "org.springframework.context.annotation.Configuration";
    private static final String ENTITY = "jakarta.persistence.Entity";

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    // ---------------------------------------------------------------------
    // 규칙 1: 의존 방향 (SPEC:65-80)
    // ---------------------------------------------------------------------
    @Test
    void domainModulesRespectAllowedDependencyDirection() {
        Set<String> actualDisallowed = new TreeSet<>();
        long scannedDomainClasses = 0;
        boolean sawAllowedCrossModuleEdge = false;

        for (JavaClass origin : production) {
            String originModule = moduleOf(origin);
            if (!DOMAIN_MODULES.contains(originModule)) {
                continue; // 도메인 모듈이 아닌 인프라/횡단(dashboard, notification, refresh 등)은 SPEC 표 밖
            }
            if (isConfiguration(origin)) {
                continue; // Spring @Configuration 은 composition root — 모듈을 넘나드는 조립이 본분
            }
            scannedDomainClasses++;
            Set<String> allowed = ALLOWED_DEPENDENCIES.get(originModule);
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                String targetModule = moduleOf(dependency.getTargetClass());
                if (!DOMAIN_MODULES.contains(targetModule) || targetModule.equals(originModule)) {
                    continue;
                }
                if (allowed.contains(targetModule)) {
                    sawAllowedCrossModuleEdge = true;
                    continue;
                }
                actualDisallowed.add(topLevelName(origin) + " -> " + targetModule);
            }
        }

        // vacuous pass 방지: 도메인 클래스가 실제로 스캔됐고, 허용 의존 규칙이 실제 간선을 판정함을 확인.
        assertThat(scannedDomainClasses)
                .as("도메인 모듈 클래스가 스캔돼야 규칙이 유효(vacuous 방지)")
                .isGreaterThan(0);
        assertThat(sawAllowedCrossModuleEdge)
                .as("허용된 모듈 간 의존(예: account->broker, order->account)이 실제로 존재해야 규칙이 살아있음")
                .isTrue();

        // debt 누적 차단.
        assertThat(FROZEN_MODULE_EDGES)
                .as("얼린 도메인 간 의존 상한")
                .hasSize(FROZEN_MODULE_EDGE_CAP);

        // 실제 위반 == 얼린 목록: 신규 위반이면 초과, 코드가 고쳐졌으면 목록에서 사라져야(phantom 방지) 실패.
        assertThat(actualDisallowed)
                .as("도메인 간 위반은 얼린 목록과 정확히 일치해야 한다. "
                        + "새 위반은 프로덕션 코드를 고쳐 없애고, 해소된 항목은 목록에서 지울 것")
                .containsExactlyInAnyOrderElementsOf(FROZEN_MODULE_EDGES);
    }

    // ---------------------------------------------------------------------
    // 규칙 2: analysis -> order, broker 금지 (SPEC:95)
    // ---------------------------------------------------------------------
    @Test
    void analysisMustNotDependOnOrderOrBroker() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..analysis..")
                .and().areNotAnnotatedWith(CONFIGURATION)
                .should().dependOnClassesThat().resideInAnyPackage("..order..", "..broker..")
                .allowEmptyShould(false); // analysis 클래스가 0개면 실패시켜 vacuous pass 차단

        rule.check(production);
    }

    // ---------------------------------------------------------------------
    // 규칙 3: intelligence -> analysis 애플리케이션 서비스 직접 호출 금지 (SPEC:96)
    // ---------------------------------------------------------------------
    @Test
    void intelligenceMustNotCallAnalysisApplicationServicesDirectly() {
        Set<String> actualServiceViolations = new TreeSet<>();
        boolean sawIntelligenceClass = false;

        for (JavaClass origin : production) {
            if (!moduleOf(origin).equals("intelligence") || isConfiguration(origin)) {
                continue;
            }
            sawIntelligenceClass = true;
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                if (moduleOf(target).equals("analysis") && target.getSimpleName().endsWith("Service")) {
                    actualServiceViolations.add(topLevelName(origin));
                }
            }
        }

        assertThat(sawIntelligenceClass)
                .as("intelligence 모듈 클래스가 존재해야 규칙이 유효(vacuous 방지)")
                .isTrue();
        assertThat(FROZEN_EVENT_TO_ANALYSIS_SERVICE)
                .as("얼린 event->analysis 서비스 호출 상한")
                .hasSize(1);
        assertThat(actualServiceViolations)
                .as("intelligence 가 직접 호출하는 analysis 애플리케이션 서비스는 얼린 목록과 정확히 일치해야 한다. "
                        + "재분석은 outbox(AnalysisRecalculationRequested) 경유로 옮길 것")
                .containsExactlyInAnyOrderElementsOf(FROZEN_EVENT_TO_ANALYSIS_SERVICE);
    }

    // ---------------------------------------------------------------------
    // 규칙 4: JPA @Entity 의 선언 패키지 외부 노출 금지 (SPEC:101)
    // ---------------------------------------------------------------------
    @Test
    void jpaEntitiesAreNotReferencedOutsideTheirDeclaringPackage() {
        List<String> violations = new ArrayList<>();
        int entityCount = 0;

        for (JavaClass entity : production) {
            if (!entity.isAnnotatedWith(ENTITY)) {
                continue;
            }
            entityCount++;
            String entityPackage = entity.getPackageName();
            for (Dependency dependency : entity.getDirectDependenciesToSelf()) {
                JavaClass dependent = dependency.getOriginClass();
                if (!dependent.getPackageName().equals(entityPackage)) {
                    violations.add(dependent.getName() + " -> entity " + entity.getName());
                }
            }
        }

        assertThat(entityCount)
                .as("@Entity 대상이 존재해야 규칙이 유효(vacuous 방지)")
                .isGreaterThan(0);
        assertThat(violations)
                .as("JPA entity 는 선언 패키지 밖에서 참조되면 안 된다. 모듈 간 공유는 공개 서비스와 불변 ID/DTO 로 제한")
                .isEmpty();
    }

    // ---------------------------------------------------------------------
    // 규칙 5: 단일 인자 findById(ID) 호출 금지 (SPEC:100)
    // ---------------------------------------------------------------------
    @Test
    void springDataFindByIdSingleArgIsNotCalled() {
        Set<String> actualCallers = new TreeSet<>();
        boolean sawSpringDataRepository = production.stream()
                .anyMatch(candidate -> candidate.isInterface()
                        && candidate.isAssignableTo(SPRING_DATA_REPOSITORY));

        for (JavaClass origin : production) {
            for (JavaMethodCall call : origin.getMethodCallsFromSelf()) {
                if (!call.getTarget().getName().equals("findById")
                        || call.getTarget().getRawParameterTypes().size() != 1) {
                    continue;
                }
                if (call.getTarget().getOwner().isAssignableTo(SPRING_DATA_REPOSITORY)) {
                    actualCallers.add(topLevelName(call.getOriginOwner()));
                }
            }
        }

        assertThat(sawSpringDataRepository)
                .as("Spring Data repository 가 존재해야 규칙이 유효(vacuous 방지)")
                .isTrue();
        assertThat(FROZEN_FIND_BY_ID_CALLERS)
                .as("얼린 findById 호출 클래스 상한")
                .hasSize(5);
        assertThat(actualCallers)
                .as("단일 인자 findById(ID) 호출자는 얼린 목록과 정확히 일치해야 한다. "
                        + "사용자 소유 aggregate 조회는 userId 를 필수 인자로 받는 repository API 로 옮길 것")
                .containsExactlyInAnyOrderElementsOf(FROZEN_FIND_BY_ID_CALLERS);
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    /** com.jmj.trade.&lt;module&gt;[...] 에서 최상위 모듈 세그먼트를 뽑는다. 없으면 빈 문자열. */
    private static String moduleOf(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        if (packageName.equals(BASE)) {
            return "";
        }
        String prefix = BASE + ".";
        if (!packageName.startsWith(prefix)) {
            return "";
        }
        String rest = packageName.substring(prefix.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    /** 내부(nested) 클래스의 간선을 감싸는 최상위 클래스에 귀속시킨다. */
    private static String topLevelName(JavaClass javaClass) {
        String name = javaClass.getName();
        int inner = name.indexOf('$');
        return inner < 0 ? name : name.substring(0, inner);
    }

    private static boolean isConfiguration(JavaClass javaClass) {
        return javaClass.isAnnotatedWith(CONFIGURATION);
    }
}
