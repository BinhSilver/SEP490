package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.*;

import com.jlas.learning.dto.ComprehensionExerciseSource;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.service.impl.engine.ComprehensionSourceSnapshot;
import org.junit.jupiter.api.Test;

/** [JLAS-W0108] The replay source is whatever was frozen when the session was issued; unreadable or missing means
 * unavailable, never a lookup of today's canonical content. Fixture strings are synthetic. */
class ComprehensionSourceSnapshotTest {
    @Test void readingRoundTripsThePassageAndRevision() {
        String json = ComprehensionSourceSnapshot.toJson(LearningActivityType.READING, 2, "rev-9",
                new ComprehensionExerciseSource("Passage A", null));
        var parsed = ComprehensionSourceSnapshot.parse(json).orElseThrow();
        assertEquals("Passage A", parsed.passage());
        assertNull(parsed.audioUrl());
        assertTrue(json.contains("\"batchIndex\":2"));
        assertTrue(json.contains("rev-9"));
    }

    @Test void listeningRoundTripsTheAudioUrl() {
        String json = ComprehensionSourceSnapshot.toJson(LearningActivityType.LISTENING, 0, "rev-1",
                new ComprehensionExerciseSource(null, "/audio/a.mp3"));
        var parsed = ComprehensionSourceSnapshot.parse(json).orElseThrow();
        assertEquals("/audio/a.mp3", parsed.audioUrl());
        assertNull(parsed.passage());
    }

    @Test void missingBlankOrBrokenSnapshotsAreUnavailable() {
        assertTrue(ComprehensionSourceSnapshot.parse(null).isEmpty());
        assertTrue(ComprehensionSourceSnapshot.parse("  ").isEmpty());
        assertTrue(ComprehensionSourceSnapshot.parse("{not json").isEmpty());
        assertTrue(ComprehensionSourceSnapshot.parse("{\"passage\":null,\"audioUrl\":null}").isEmpty());
    }
}
