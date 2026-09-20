package com.jmj.trade.sheets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentOsSheetPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "investment-os.sheet.enabled=true",
                    "investment-os.sheet.spreadsheet-id=sheet-1",
                    "investment-os.sheet.user-id=11111111-1111-1111-1111-111111111111",
                    "investment-os.sheet.connection-id=22222222-2222-2222-2222-222222222222",
                    "investment-os.sheet.account-label=ACCOUNT_2"
            );

    @Test
    void bindsSheetPropertiesWhenRecordHasCompatibilityConstructor() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            var properties = context.getBean(InvestmentOsSheetProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.spreadsheetId()).isEqualTo("sheet-1");
            assertThat(properties.accountLabel()).isEqualTo("ACCOUNT_2");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(InvestmentOsSheetProperties.class)
    static class PropertiesConfiguration {
    }
}
