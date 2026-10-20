package com.jlas.learning;

import com.jlas.learning.config.InteractiveLearningV2Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OD-48 (owner 2026-10-20): new JLPT paths are refused when a stage level lacks a required skill (spec §2.1). The
 * shipped application config turns the gate on; tests keep the code default (REPORT) unless they opt in.
 */
class ReadinessGateConfigTest {

    @Test
    void theShippedApplicationConfigEnforcesTheJlptSkillGate() throws Exception {
        var sources = new YamlPropertySourceLoader().load("main", new FileSystemResource("src/main/resources/application.yaml"));
        Object raw = sources.stream().map(source -> source.getProperty("learning.interactive-v2.readiness-gate"))
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);

        assertEquals("${LEARNING_READINESS_GATE:ENFORCE}", raw == null ? null : raw.toString(),
                "application.yaml must ship the gate ON (ENFORCE), overridable only by LEARNING_READINESS_GATE");
        var environment = new StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addLast);
        if (System.getenv("LEARNING_READINESS_GATE") == null) {
            assertEquals("ENFORCE", environment.getProperty("learning.interactive-v2.readiness-gate"));
        }
    }

    @Test
    void theGateBindsFromConfigurationAndCanStillBeOverriddenToReport() {
        var enforced = new Binder(new MapConfigurationPropertySource(Map.of("learning.interactive-v2.readiness-gate", "ENFORCE")))
                .bind("learning.interactive-v2", InteractiveLearningV2Properties.class).get();
        assertTrue(enforced.enforcesReadiness());

        var report = new Binder(new MapConfigurationPropertySource(Map.of("learning.interactive-v2.readiness-gate", "REPORT")))
                .bind("learning.interactive-v2", InteractiveLearningV2Properties.class).get();
        assertFalse(report.enforcesReadiness());
        assertFalse(new InteractiveLearningV2Properties().enforcesReadiness(), "the code default stays REPORT for tests");
    }
}
