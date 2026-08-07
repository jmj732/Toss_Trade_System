package com.jmj.trade.architecture;

import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.toss.TossBrokerConfiguration;
import com.jmj.trade.broker.toss.TossCredentialMetadata;
import com.jmj.trade.broker.toss.TossCredentialProvider;
import com.jmj.trade.broker.toss.TossCredentials;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code tossOrderPort} 빈 이름·타입 계약 고정 테스트.
 *
 * <p><b>고정하려는 결합.</b> {@code @Qualifier("tossOrderPort")} 문자열 리터럴이 여러 파일에
 * 하드코딩돼 있다: 정의처는 {@code broker.toss.TossBrokerConfiguration#tossOrderPort}, 참조처는
 * {@code CredentialVaultConfiguration}(reconciliationBrokerProbe·liveOrderActivationService)와
 * {@code order.RealOrderCanaryConfiguration}(realOrderCanaryService)다. 문자열 Qualifier 결합은
 * 컴파일러도 ArchUnit 도 잡지 못한다. 조립소 해체 시 이 참조들이 다른 파일로 이사하는데, 그 과정에서
 * 정의처의 빈 이름이나 타입 계약이 바뀌면 참조가 조용히 깨진다. 이 테스트가 이사 전에 정의처 계약을
 * 못으로 박는다: 빈 이름은 정확히 {@code "tossOrderPort"}, 타입은 {@code BrokerOrderPort} 에 할당
 * 가능.
 *
 * <p><b>왜 전체 {@code @SpringBootTest} 가 아니라 슬라이스인가.</b> {@code tossOrderPort} 는
 * {@code real-order.enabled=true} 일 때만 생성되는데, 그 플래그를 전체 애플리케이션 컨텍스트에서
 * 켜면 {@code tossOrderPort}(TossInvestBrokerAdapter)가 {@code tossBrokerAdapter} 와 함께 두 번째
 * {@code BrokerAdapter} 후보가 되어 {@code paperOrderWorkflowService} 주입이 모호해지고 컨텍스트가
 * 부팅되지 않는다({@link BeanInventoryCredentialedProfileTest} Javadoc 의 선재 결함 참고). 따라서
 * {@code paperOrderWorkflowService} 가 없는 {@code TossBrokerConfiguration} 슬라이스에서만 이
 * 계약을 부팅 가능하게 검증할 수 있다. 이는 기존
 * {@code broker.toss.TossBrokerConfigurationTest} 가 쓰는 것과 같은 패턴이다.
 */
class TossOrderPortWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TossBrokerConfiguration.class))
            .withUserConfiguration(StubCredentialProvider.class, StubRedis.class)
            .withPropertyValues("real-order.enabled=true");

    @Test
    void tossOrderPortBeanNameAndTypeContractIsPinned() {
        contextRunner.run(context -> {
            assertThat(context)
                    .as("tossOrderPort 빈 이름은 @Qualifier 문자열 결합의 대상이므로 이 이름으로 존재해야 한다")
                    .hasBean("tossOrderPort");
            assertThat(context.getType("tossOrderPort"))
                    .as("tossOrderPort 는 BrokerOrderPort 계약에 할당 가능해야 한다")
                    .isAssignableTo(BrokerOrderPort.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class StubCredentialProvider {

        @Bean
        TossCredentialProvider tossCredentialProvider() {
            return new TossCredentialProvider() {
                @Override
                public TossCredentialMetadata current(UUID brokerConnectionId) {
                    return new TossCredentialMetadata(1);
                }

                @Override
                public TossCredentials decrypt(UUID brokerConnectionId, long expectedRevision) {
                    return new TossCredentials("client-id", "client-secret");
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StubRedis {

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }
}
