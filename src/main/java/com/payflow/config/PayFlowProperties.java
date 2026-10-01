package com.payflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payflow")
public record PayFlowProperties(
        String apiKey,
        String webhookSecret,
        long attemptTimeoutMillis,
        int maxGatewayAttempts) {

    public PayFlowProperties {
        if (attemptTimeoutMillis <= 0) attemptTimeoutMillis = 2000;
        if (maxGatewayAttempts <= 0) maxGatewayAttempts = 3;
    }
}
