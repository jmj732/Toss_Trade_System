package com.jmj.trade.investment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentFundamentalSourcePolicyValidatorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(InvestmentFundamentalSourcePolicyValidator.class);

    @Test
    void providersPolicyStartsWithAnyProviderConfiguration() {
        runner.withPropertyValues("investment.data.fundamental-source-policy=PROVIDERS",
                        "stock-analysis.providers.sec.enabled=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void defaultPolicyStartsWhenPropertyIsAbsent() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void tossOnlyStartsWhenNoNonTossFundamentalProviderIsEnabled() {
        runner.withPropertyValues("investment.data.fundamental-source-policy=TOSS_ONLY")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void tossOnlyFailsStartupWhenSecAlphaVantageOrFmpIsEnabled() {
        for (var enabled : List.of("stock-analysis.providers.sec.enabled=true",
                "stock-analysis.providers.alpha-vantage.enabled=true",
                "stock-analysis.providers.fmp.enabled=true")) {
            runner.withPropertyValues("investment.data.fundamental-source-policy=TOSS_ONLY", enabled)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void tossOnlyFailureNamesTheConflictingPolicy() {
        runner.withPropertyValues("investment.data.fundamental-source-policy=TOSS_ONLY",
                        "stock-analysis.providers.fmp.enabled=true")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("TOSS_ONLY requires"));
    }

    @Test
    void unknownPolicyFailsStartupInsteadOfFallingBackToProviders() {
        runner.withPropertyValues("investment.data.fundamental-source-policy=toss")
                .run(context -> assertThat(context).hasFailed());
    }
}
