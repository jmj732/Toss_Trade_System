package com.jmj.trade.broker.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
final class TossApiDtos {

    private TossApiDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OAuthTokenResponse(String access_token, String token_type, Long expires_in) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OAuthErrorResponse(String error, String error_description) {
    }

    record OAuthToken(String accessToken, java.time.Duration expiresIn) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegularErrorEnvelope(RegularError error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegularError(String requestId, String code, String message, Object data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountsEnvelope(List<Account> result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Account(String accountNo, String accountSeq, String accountType) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HoldingsEnvelope(Holdings result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Holdings(HoldingsSummary summary, List<HoldingItem> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HoldingsSummary(Money totalValue) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HoldingItem(
            String market,
            String symbol,
            String name,
            String quantity,
            String averagePrice,
            Money currentValue,
            Cost cost) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Cost(String commission, String tax) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PricesEnvelope(List<Price> result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Price(String symbol, String price, String timestamp) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BuyingPowerEnvelope(BuyingPower result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BuyingPower(String currency, String cashBuyingPower) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Money(String currency, String amount) {
    }
}
