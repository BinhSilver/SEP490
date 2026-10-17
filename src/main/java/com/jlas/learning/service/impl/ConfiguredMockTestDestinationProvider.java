package com.jlas.learning.service.impl;

import com.jlas.learning.config.MockTestProperties;
import com.jlas.learning.dto.MockTestDestination;
import com.jlas.learning.service.MockTestDestinationProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;

/**
 * [JLAS-W0193] RC Task 7 — the only shipped {@link MockTestDestinationProvider}. Unavailable
 * ({@code BLOCKED_INTEGRATION}) by default: no {@code learning.mock-test.destination-url} is configured
 * anywhere in this repository, because no real Mock Test owner contract exists (re-audited fresh at RC
 * Task 7 time — see docs/rules-and-business-logic.md).
 *
 * <p>If a destination is ever configured, it is accepted ONLY when it validates as either an internal
 * app-relative route ({@code "/mock-test/..."} — never protocol-relative {@code "//host/..."}, which
 * silently resolves to an external host) or an absolute HTTPS URL whose host is explicitly listed in
 * {@code learning.mock-test.approved-origins}. This is the open-redirect guard the RC plan requires: an
 * unapproved or external URL is rejected back to {@code BLOCKED_INTEGRATION} rather than ever being
 * handed to a caller.
 */
@Component
@RequiredArgsConstructor
public class ConfiguredMockTestDestinationProvider implements MockTestDestinationProvider {

    private final MockTestProperties properties;

    @Override
    public MockTestDestination resolveDestination(String targetLevel) {
        String configured = properties.getDestinationUrl();
        if (configured == null || configured.isBlank() || !isApprovedDestination(configured.trim())) {
            return MockTestDestination.blocked(targetLevel);
        }
        String destination = configured.trim();
        // The bundled JLPT library otherwise opens on N3 for every learner. Carry the server-owned
        // path target into its level filter; leave external provider contracts unchanged.
        if ("/jlpt".equals(destination) && targetLevel != null && targetLevel.matches("N[1-5]")) {
            destination = "/jlpt?level=" + targetLevel;
        }
        return new MockTestDestination(true, MockTestDestination.STATUS_AVAILABLE, destination, targetLevel);
    }

    /** Package-private so the RC Task 7 security test can exercise the validation rule directly. */
    boolean isApprovedDestination(String candidate) {
        if (candidate.startsWith("//")) return false; // protocol-relative — resolves to an external host
        if (candidate.startsWith("/")) return true;    // internal app-relative route
        try {
            URI uri = new URI(candidate);
            if (!uri.isAbsolute()) return false;
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            return host != null && approvedOrigins().contains(host);
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    private List<String> approvedOrigins() {
        String raw = properties.getApprovedOrigins();
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
