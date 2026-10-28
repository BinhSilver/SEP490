package com.jlas.learning.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@Component @ConfigurationProperties(prefix = "learning.interactive-v2")
public class InteractiveLearningV2Properties {
    private boolean enabled;
    /** Spec §2.1 readiness gate for JLPT stages: {@code REPORT} records a level's missing skills as non-blocking
     * notes; {@code ENFORCE} refuses to create a partial path. application.yaml ships ENFORCE (owner OD-48,
     * 2026-10-20); REPORT stays the code default so unit tests opt in explicitly. */
    private String readinessGate = "REPORT";
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getReadinessGate() { return readinessGate; }
    public void setReadinessGate(String readinessGate) { this.readinessGate = readinessGate; }
    public boolean enforcesReadiness() { return "ENFORCE".equalsIgnoreCase(readinessGate == null ? "" : readinessGate.trim()); }
}
