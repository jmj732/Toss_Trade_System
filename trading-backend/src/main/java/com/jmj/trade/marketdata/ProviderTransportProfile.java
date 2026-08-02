package com.jmj.trade.marketdata;

record ProviderTransportProfile(
        String defaultApiKeyHeader,
        String defaultApiKeyQueryParameter,
        boolean userAgentRequired
) {
}
