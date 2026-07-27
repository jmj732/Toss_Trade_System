package com.jmj.trade.broker.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
}
