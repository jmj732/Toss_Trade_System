package com.jmj.trade.broker.toss;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(TossCredentialProvider.class)
@EnableConfigurationProperties(TossApiProperties.class)
public class TossBrokerConfiguration {
}
