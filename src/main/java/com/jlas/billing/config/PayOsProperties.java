package com.jlas.billing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * PayOS merchant channel (JLAS2-49). Keys live only in the ignored application-local.yaml or env vars.
 *
 * @param clientId          x-client-id of the PayOS channel
 * @param apiKey            x-api-key of the PayOS channel
 * @param checksumKey       HMAC-SHA256 key that signs requests and verifies responses
 * @param baseUrl           PayOS merchant API
 * @param appBaseUrl        public URL of this app; PayOS sends the buyer back here after paying or cancelling
 * @param linkTtlMinutes    how long a payment link stays payable
 * @param checkThrottleSeconds minimum gap between two PayOS status checks of one order
 */
@ConfigurationProperties(prefix = "payos")
public record PayOsProperties(
        @DefaultValue("") String clientId,
        @DefaultValue("") String apiKey,
        @DefaultValue("") String checksumKey,
        @DefaultValue("https://api-merchant.payos.vn") String baseUrl,
        @DefaultValue("http://localhost:8080") String appBaseUrl,
        @DefaultValue("15") int linkTtlMinutes,
        @DefaultValue("3") int checkThrottleSeconds) {

    public boolean configured() {
        return !clientId.isBlank() && !apiKey.isBlank() && !checksumKey.isBlank();
    }
}
