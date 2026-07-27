package com.jmj.trade.broker.toss;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@AutoConfiguration
@ConditionalOnBean(TossCredentialProvider.class)
@EnableConfigurationProperties(TossApiProperties.class)
public class TossBrokerConfiguration {
}
