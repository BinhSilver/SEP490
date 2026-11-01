package com.jlas.jlptpath.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * [JLAS-W0108] Settings of the topic-based JLPT path ({@code learning.jlpt-path.*}).
 *
 * @param enabled             off = /learning keeps the old path; old data is never touched
 * @param previewDraft        local trial only: also show DRAFT items that have not passed the content checks
 * @param forceReleasedLevels levels treated as released whatever their status (N1..N5, normalised, no duplicates)
 * @param audioBaseUrl        public prefix of the content audio store
 * @param reminder            daily study reminder
 */
@ConfigurationProperties("learning.jlpt-path")
public record JpathProperties(
        boolean enabled,
        boolean previewDraft,
        List<String> forceReleasedLevels,
        String audioBaseUrl,
        Reminder reminder) {

    private static final Set<String> LEVELS = Set.of("N1", "N2", "N3", "N4", "N5");
    private static final String DEFAULT_AUDIO_BASE_URL = "/uploads/content-audio/";

    public JpathProperties {
        forceReleasedLevels = normalizeLevels(forceReleasedLevels);
        audioBaseUrl = audioBaseUrl == null || audioBaseUrl.isBlank() ? DEFAULT_AUDIO_BASE_URL : audioBaseUrl.trim();
        reminder = reminder == null ? new Reminder(true, false, Reminder.DEFAULT_TIME) : reminder;
    }

    private static List<String> normalizeLevels(List<String> raw) {
        List<String> levels = new ArrayList<>();
        if (raw == null) {
            return List.of();
        }
        for (String value : raw) {
            if (value == null) {
                continue;
            }
            String level = value.trim().toUpperCase(Locale.ROOT);
            if (LEVELS.contains(level) && !levels.contains(level)) {
                levels.add(level);
            }
        }
        return List.copyOf(levels);
    }

    /**
     * @param jobEnabled   runs the reminder job
     * @param emailEnabled also sends the reminder by email
     * @param defaultTime  reminder time (HH:mm) for learners who have not chosen one
     */
    public record Reminder(
            @DefaultValue("true") boolean jobEnabled,
            boolean emailEnabled,
            String defaultTime) {

        static final String DEFAULT_TIME = "20:00";

        public Reminder {
            defaultTime = defaultTime == null || defaultTime.isBlank() ? DEFAULT_TIME : defaultTime.trim();
        }
    }
}
