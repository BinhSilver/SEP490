package com.jlas.e2e.support;

import java.util.Optional;

/**
 * Central place for E2E test configuration. All values are overridable via
 * environment variables so no credentials are ever hardcoded in source.
 */
public final class E2EConfig {

    private E2EConfig() {
    }

    public static String baseUrl() {
        return env("JLAS_BASE_URL", "http://localhost:8080");
    }

    public static String userEmail() {
        return required("JLAS_TEST_EMAIL");
    }

    public static String userPassword() {
        return required("JLAS_TEST_PASSWORD");
    }

    public static boolean headless() {
        return Boolean.parseBoolean(env("JLAS_HEADLESS", "false"));
    }

    /**
     * JWT value of the app's own "access_token" cookie, captured from a
     * browser DevTools session after a manual Google sign-in. When present,
     * tests skip the login form entirely and inject this cookie instead —
     * Google's own sign-in page is never automated.
     */
    public static Optional<String> sessionCookie() {
        String value = System.getenv("JLAS_SESSION_COOKIE");
        return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "Missing required env var " + key + ". Set it to a real J-LAS account before running the E2E suite.");
        }
        return value;
    }
}
