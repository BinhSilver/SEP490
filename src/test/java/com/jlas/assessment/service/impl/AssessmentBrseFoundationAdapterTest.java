package com.jlas.assessment.service.impl;

import com.jlas.assessment.entity.AssessmentAttempt;
import com.jlas.assessment.entity.SmartTestSession;
import com.jlas.assessment.repository.AssessmentAttemptRepository;
import com.jlas.assessment.repository.SmartTestSessionRepository;
import com.jlas.auth.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssessmentBrseFoundationAdapterTest {
    @Mock AssessmentAttemptRepository attempts;
    @Mock SmartTestSessionRepository sessions;

    @Test void returnsImmutableFoundationSnapshotForOwnedSubmittedBaAttempt() {
        var adapter = new AssessmentBrseFoundationAdapter(attempts, sessions);
        var attempt = AssessmentAttempt.builder().id(9L).user(User.builder().id(7L).build())
                .attemptType("SMART_TEST").status("SUBMITTED").build();
        var session = SmartTestSession.builder().attemptId(9L).attempt(attempt).goal("BA")
                .estimatedOverallLevel("N5").dailyStudyMinutes(25).build();
        when(attempts.findByIdAndUserId(9L, 7L)).thenReturn(Optional.of(attempt));
        when(sessions.findById(9L)).thenReturn(Optional.of(session));

        var result = adapter.verify(7L, 9L);

        assertTrue(result.qualified());
        assertEquals("N5", result.assessedLevel());
        assertEquals(25, result.dailyStudyMinutes());
    }

    @Test void failsClosedForWrongGoalOrUnsubmittedAttempt() {
        var adapter = new AssessmentBrseFoundationAdapter(attempts, sessions);
        var attempt = AssessmentAttempt.builder().id(9L).user(User.builder().id(7L).build())
                .attemptType("SMART_TEST").status("IN_PROGRESS").build();
        when(attempts.findByIdAndUserId(9L, 7L)).thenReturn(Optional.of(attempt));
        assertEquals("ATTEMPT_NOT_SUBMITTED", adapter.verify(7L, 9L).reasonCode());

        attempt.setStatus("SUBMITTED");
        when(sessions.findById(9L)).thenReturn(Optional.of(SmartTestSession.builder()
                .attemptId(9L).attempt(attempt).goal("JLPT").build()));
        assertEquals("GOAL_MISMATCH", adapter.verify(7L, 9L).reasonCode());
    }
}
