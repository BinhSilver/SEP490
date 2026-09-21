package com.jlas.auth.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Small dependency-free HS256 JWT implementation used for access tokens. */
public final class JwtUtil {
    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    private JwtUtil() {
    }

    public static String generateToken(String email, String secret, long ttlSeconds) {
        long expiresAt = Instant.now().plusSeconds(ttlSeconds).getEpochSecond();
        String header = encode(HEADER);
        String payload = encode("{\"sub\":\"" + escape(email) + "\",\"exp\":" + expiresAt + "}");
        return header + "." + payload + "." + sign(header + "." + payload, secret);
    }

    public static String extractEmail(String token, String secret) {
        return parseAndVerify(token, secret).get("sub");
    }

    public static boolean isTokenValid(String token, String secret) {
        try {
            parseAndVerify(token, secret);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static Map<String, String> parseAndVerify(String token, String secret) {
        if (token == null || secret == null) {
            throw new IllegalArgumentException("Token and secret are required");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !MessageDigest.isEqual(
                sign(parts[0] + "." + parts[1], secret).getBytes(StandardCharsets.US_ASCII),
                parts[2].getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Invalid JWT signature");
        }
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String subject = jsonString(payload, "sub");
        long expiresAt = Long.parseLong(jsonNumber(payload, "exp"));
        if (subject == null || subject.isBlank() || Instant.now().getEpochSecond() >= expiresAt) {
            throw new IllegalArgumentException("Expired JWT");
        }
        return Map.of("sub", subject, "exp", Long.toString(expiresAt));
    }

    private static String sign(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not sign JWT", exception);
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String jsonString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String jsonNumber(String json, String key) {
        String marker = "\"" + key + "\":";
        int start = json.indexOf(marker);
        if (start < 0) throw new IllegalArgumentException("Missing JWT claim");
        start += marker.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return json.substring(start, end);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
