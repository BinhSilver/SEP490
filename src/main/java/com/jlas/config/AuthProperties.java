package com.jlas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime settings for the stateless access token and persisted refresh token. */
@ConfigurationProperties(prefix = "jlas.auth")
public record AuthProperties(String jwtSecret, long accessTokenTtl, long refreshTokenTtl) {
    public AuthProperties {
        if (jwtSecret == null || jwtSecret.length() < 32) {
            throw new IllegalArgumentException("jlas.auth.jwt-secret must contain at least 32 characters");
        }
        if (accessTokenTtl <= 0 || refreshTokenTtl <= 0) {
            throw new IllegalArgumentException("Token TTL values must be positive");
        }
    }
}
