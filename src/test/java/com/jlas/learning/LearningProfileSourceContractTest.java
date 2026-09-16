package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.service.LearningProfileSource;

/**
 * Reusable contract assertions for any {@link LearningProfileSource} adapter.
 *
 * <p>{@link com.jlas.learning.service.impl.LearningServiceImpl} builds a path from the snapshot an
 * adapter returns, so every adapter — the in-memory fakes used in tests today and the production
 * assessment adapter wired in when Smart Test hands off — must satisfy the clauses asserted here.
 * A production adapter test plugs a real {@link LearningProfileSource} into these helpers and runs
 * the same assertions on real data, so the contract is verified once against the adapter that
 * actually ships, not only against a fixture.</p>
 *
 * <p>This class intentionally has no {@code @Test} methods: there is no production adapter to run
 * against yet. It is the executable specification that adapter will be judged against.</p>
 */
public final class LearningProfileSourceContractTest {

    private LearningProfileSourceContractTest() {
        // utility class
    }

    /**
     * Asserts the adapter returns a snapshot owned by {@code userId} for the known, finalized
     * attempt {@code attemptId}, with every field the service reads populated.
     */
    public static LearningProfileSnapshot assertLoadsOwnAttempt(
            LearningProfileSource source, Long userId, Long attemptId) {
        LearningProfileSnapshot snapshot = source.loadForUser(userId, attemptId);
        assertNotNull(snapshot, "adapter must return a snapshot for a known, finalized attempt");
        assertEquals(userId, snapshot.userId(), "snapshot must be owned by the requested user");
        assertEquals(attemptId, snapshot.assessmentAttemptId(),
                "snapshot must correspond to the requested assessment attempt");
        assertCompleteFields(snapshot);
        return snapshot;
    }

    /**
     * Asserts the adapter never leaks another user's attempt. Requesting a foreign attempt id
     * under {@code userId} must yield either {@code null} (which the service maps to FORBIDDEN) or
     * a snapshot still owned by {@code userId} — never the foreign user's data.
     */
    public static void assertDoesNotLeakForeignAttempt(
            LearningProfileSource source, Long userId, Long foreignAttemptId) {
        LearningProfileSnapshot snapshot = source.loadForUser(userId, foreignAttemptId);
        if (snapshot != null) {
            assertEquals(userId, snapshot.userId(),
                    "adapter must not return a snapshot owned by a different user");
        }
    }

    /** Asserts an unknown attempt id is reported as absent ({@code null}), not invented. */
    public static void assertUnknownAttemptAbsent(
            LearningProfileSource source, Long userId, Long missingAttemptId) {
        assertNull(source.loadForUser(userId, missingAttemptId),
                "adapter must return null for an attempt that does not exist");
    }

    private static void assertCompleteFields(LearningProfileSnapshot snapshot) {
        assertNotNull(snapshot.surveyId(), "surveyId must be present");
        assertNotNull(snapshot.assessmentRevision(), "assessmentRevision must be present");
        assertNotNull(snapshot.assessedLevel(), "assessedLevel must be present");
        assertNotNull(snapshot.targetLevel(), "targetLevel must be present");
        assertNotNull(snapshot.goalCode(), "goalCode must be present");
        assertNotNull(snapshot.minutesPerDay(), "minutesPerDay must be present");
        assertTrue(snapshot.minutesPerDay() > 0, "minutesPerDay must be positive");
    }
}
