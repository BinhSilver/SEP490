package com.jlas.admin.service;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuditServiceTest {
    @Test
    void masksSensitiveKeysRecursivelyWithoutChangingInput() {
        Map<String, Object> nested = new LinkedHashMap<>();
        for (String key : List.of("passwordHash", "SECRET", "accessToken", "otp", "api_key",
                "api-key", "apiKey", "credential", "hash")) nested.put(key, "dummy-value");
        nested.put("status", "ACTIVE");
        nested.put("nullable", null);
        Map<String, Object> input = Map.of("items", List.of(Map.of("inner", nested)), "count", 1);
        Object masked = AdminAuditService.mask(input);
        Map<String, Object> expected = new LinkedHashMap<>();
        nested.forEach((key, value) -> expected.put(key, key.equals("status") || key.equals("nullable") ? value : "***"));
        assertThat(masked).isEqualTo(Map.of("items", List.of(Map.of("inner", expected)), "count", 1));
        assertThat(nested.get("passwordHash")).isEqualTo("dummy-value");
    }
}
