package com.jlas.learning.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0193] RC Task 7 — {@code destinationUrl} is empty by default, matching
 * {@link InteractiveLearningV2Properties}'s own "unavailable until explicitly configured" posture: no
 * real Mock Test owner contract exists in this codebase, so leaving this unset is the honest default.
 * Setting {@code destinationUrl} alone does not make it live — {@code ConfiguredMockTestDestinationProvider}
 * still validates it as an internal relative route or an origin listed in {@code approvedOrigins} before
 * ever handing it to a caller.
 */
@Component
@ConfigurationProperties(prefix = "learning.mock-test")
public class MockTestProperties {
    private String destinationUrl = "";
    /** Comma-separated list of hosts approved for an absolute HTTPS destination. */
    private String approvedOrigins = "";

    public String getDestinationUrl() { return destinationUrl; }
    public void setDestinationUrl(String destinationUrl) { this.destinationUrl = destinationUrl; }
    public String getApprovedOrigins() { return approvedOrigins; }
    public void setApprovedOrigins(String approvedOrigins) { this.approvedOrigins = approvedOrigins; }
}
