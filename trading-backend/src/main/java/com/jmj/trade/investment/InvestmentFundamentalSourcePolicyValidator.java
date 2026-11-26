package com.jmj.trade.investment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Fails startup for an unknown source policy, or TOSS_ONLY while a non-Toss fundamental provider is enabled. */
@Component
public class InvestmentFundamentalSourcePolicyValidator {

    public InvestmentFundamentalSourcePolicyValidator(
            @Value("${investment.data.fundamental-source-policy:PROVIDERS}") String policy,
            @Value("${stock-analysis.providers.sec.enabled:false}") boolean secEnabled,
            @Value("${stock-analysis.providers.alpha-vantage.enabled:false}") boolean alphaVantageEnabled,
            @Value("${stock-analysis.providers.fmp.enabled:false}") boolean fmpEnabled) {
        final String parsed;
        try {
            parsed = InvestmentBuyRiskGate.parsePolicy(policy);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
        if (InvestmentBuyRiskGate.POLICY_TOSS_ONLY.equals(parsed) && (secEnabled || alphaVantageEnabled || fmpEnabled)) {
            throw new IllegalStateException("investment.data.fundamental-source-policy=TOSS_ONLY requires "
                    + "stock-analysis.providers.sec, alpha-vantage and fmp to be disabled");
        }
    }
}
