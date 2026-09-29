package com.jlas.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.util.AntPathMatcher;

/**
 * [JLAS-W0193] RC Task 8 hardening — a disclosed-limitation regression test for
 * {@link SecurityConfig#securityFilterChain}, in the same spirit as {@code QuizSessionTypeConstraintTest}
 * (RC Task 6): this codebase has no existing {@code MockMvc}/full-security-context test anywhere (verified by
 * search), so a real end-to-end "unauthenticated request gets 401" assertion is not available without adding a
 * new, heavier test style this RC did not otherwise need. Instead this test parses the one real
 * {@code authorizeHttpRequests} chain and asserts, with a real {@link AntPathMatcher} (not a naive substring
 * check), that none of the routes RC Tasks 5-7 added — Reading/Listening/Dialogue study pages and APIs, the V2
 * path summary/certificate pages, and the mock-test-carrying summary API — are covered by any
 * {@code permitAll()} matcher, and that the chain still ends in {@code anyRequest().authenticated()} as its
 * catch-all. This re-verifies RC Task 5's own self-report rather than assuming it: every one of these routes
 * falls through to that catch-all, not to a matcher of its own, so this test is the only guard against a future
 * edit accidentally adding one of them (or a prefix that covers them) to the permitAll list.
 */
class SecurityConfigInteractiveV2RouteTest {

    /** [JLAS-W0193] Every page/API route RC Tasks 5-7 added, or that depends on their existence (the summary
     * page's Mock Test card). Kept as literal example paths (no ids) since {@link AntPathMatcher} matches a
     * concrete path against a pattern, not pattern-against-pattern. */
    private static final List<String> RC_TASK_5_TO_7_ROUTES = List.of(
            "/learning/v2/reading", "/learning/v2/listening", "/learning/v2/dialogue",
            "/api/learning/v2/activities/1/reading/study", "/api/learning/v2/activities/1/listening/study",
            "/api/learning/v2/activities/1/reading/micro-check/issue",
            "/api/learning/v2/activities/1/listening/micro-check/submit",
            "/api/learning/v2/activities/1/dialogue/heartbeat",
            "/api/learning/v2/activities/1/dialogue/reached-bottom",
            "/learning/path/v2/summary", "/api/learning/v2/paths/current/summary",
            "/learning/v2/certificate");

    @Test void noPermitAllMatcherCoversAnyRcTask5To7Route() throws IOException {
        List<String> permitAllPatterns = extractPermitAllPatterns();
        assertFalse(permitAllPatterns.isEmpty(), "expected at least one permitAll() matcher group to parse");

        AntPathMatcher matcher = new AntPathMatcher();
        for (String route : RC_TASK_5_TO_7_ROUTES) {
            for (String pattern : permitAllPatterns) {
                assertFalse(matcher.match(pattern, route),
                        "permitAll pattern '" + pattern + "' unexpectedly covers RC route '" + route
                                + "' — this route must fall through to anyRequest().authenticated() instead");
            }
        }
    }

    @Test void chainStillEndsInAnyRequestAuthenticated() throws IOException {
        String source = readSecurityConfigSource();
        String tail = source.substring(source.lastIndexOf(".requestMatchers("));
        assertTrue(tail.contains(".anyRequest().authenticated()"),
                "the authorizeHttpRequests chain must still end in anyRequest().authenticated() as the catch-all "
                        + "every RC Task 5-7 route relies on");
    }

    /** Extracts the string literals inside every {@code .requestMatchers(...).permitAll()} call in
     * {@code SecurityConfig}, converting them to plain Ant patterns (HTTP-method-scoped matchers are included
     * too, since {@code permitAll()} on a GET-only matcher still exempts that path from authentication for
     * that verb, which is exactly what this test must rule out for the RC routes). */
    private List<String> extractPermitAllPatterns() throws IOException {
        String source = readSecurityConfigSource();
        List<String> patterns = new ArrayList<>();
        Matcher block = Pattern.compile(
                "\\.requestMatchers\\(((?:[^()]|\\([^()]*\\))*)\\)\\s*(?:\\.hasAnyRole\\([^)]*\\)\\s*)?\\.permitAll\\(\\)",
                Pattern.DOTALL).matcher(source);
        while (block.find()) {
            Matcher literal = Pattern.compile("\"([^\"]*)\"").matcher(block.group(1));
            while (literal.find()) {
                patterns.add(literal.group(1));
            }
        }
        return patterns;
    }

    private String readSecurityConfigSource() throws IOException {
        Path path = repoRoot().resolve("src/main/java/com/jlas/config/SecurityConfig.java");
        assertTrue(Files.exists(path), "expected " + path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private Path repoRoot() {
        return Path.of("").toAbsolutePath();
    }
}
