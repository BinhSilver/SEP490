package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningQuizSession;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningMicroCheckStateRepository;
import com.jlas.learning.repository.LearningQuizSessionRepository;
import com.jlas.learning.repository.LearningStudyCardRepository;
import com.jlas.learning.repository.LearningStudySessionRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.service.impl.engine.EngineStudyState;
import com.jlas.learning.service.impl.engine.InteractiveContentAdapter;
import com.jlas.learning.service.impl.engine.InteractiveStudyEngine;
import com.jlas.learning.service.impl.engine.QuestionSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** [JLAS-W0108] Task 10 - REVIEW is read-only and PRACTICE is a recorded replay that can never complete,
 * reward, unlock or demote. Content is a synthetic string adapter (ids 1..12), not curriculum data. */
class InteractiveStudyEngineTest {
    private static final Long USER = 7L;
    private static final Long ACTIVITY = 50L;

    UserLearningPathItemRepository items = mock(UserLearningPathItemRepository.class);
    LearningActivityMemberRepository members = mock(LearningActivityMemberRepository.class);
    LearningStudySessionRepository studySessions = mock(LearningStudySessionRepository.class);
    LearningStudyCardRepository studyCards = mock(LearningStudyCardRepository.class);
    LearningMicroCheckStateRepository checkStates = mock(LearningMicroCheckStateRepository.class);
    LearningQuizSessionRepository quizSessions = mock(LearningQuizSessionRepository.class);
    InteractiveStudyEngine<String> engine;
    UserLearningPathItem activity;

    @BeforeEach
    void setUp() {
        engine = new InteractiveStudyEngine<>(items, members, studySessions, studyCards, checkStates, quizSessions,
                new LearningMapper(), new StringAdapter());
        User user = new User();
        user.setId(USER);
        UserLearningPath path = UserLearningPath.builder().id(1L).user(user).build();
        activity = new UserLearningPathItem();
        activity.setId(ACTIVITY);
        activity.setLearningPath(path);
        activity.setActivityType(LearningActivityType.VOCABULARY);
        activity.setActivityState(LearningActivityState.PASSED);
        List<LearningActivityMember> membership = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            membership.add(LearningActivityMember.builder().activity(activity).memberType(LearningMemberType.VOCABULARY_ENTRY)
                    .canonicalId((long) i).orderIndex(i).sourceRevision("rev-1").build());
        }
        when(items.findByIdAndUserId(ACTIVITY, USER)).thenReturn(Optional.of(activity));
        when(items.findOwnedByIdForUpdate(ACTIVITY, USER)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(ACTIVITY)).thenReturn(membership);
        when(quizSessions.save(any())).thenAnswer(call -> {
            LearningQuizSession s = call.getArgument(0);
            if (s.getId() == null) s.setId(900L);
            return s;
        });
    }

    @Test
    void reviewIsReadOnlyAndNeverChangesState() {
        EngineStudyState<String> state = engine.reviewState(USER, ACTIVITY);

        assertEquals(12, state.totalCards());
        assertEquals(LearningActivityState.PASSED, state.activityState());
        assertEquals(12, state.rememberedCount());
        assertFalse(state.checkEligible());
        verifyNoInteractions(checkStates, quizSessions, studySessions, studyCards);
        verify(items, never()).findOwnedByIdForUpdate(any(), any());
        verify(items, never()).save(any());
    }

    @Test
    void reviewRequiresAFinishedActivity() {
        activity.setActivityState(LearningActivityState.STUDYING);
        ApiException error = assertThrows(ApiException.class, () -> engine.reviewState(USER, ACTIVITY));
        assertEquals(ErrorCode.PREREQUISITE_NOT_MET, error.getErrorCode());
    }

    @Test
    void reviewOfAnotherUsersActivityIsNotFound() {
        ApiException error = assertThrows(ApiException.class, () -> engine.reviewState(99L, ACTIVITY));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test
    void practiceIssuesAReplaySessionWithoutTouchingMicroCheckState() {
        MicroCheckResponse issued = engine.issuePractice(USER, ACTIVITY);

        var captor = org.mockito.ArgumentCaptor.forClass(LearningQuizSession.class);
        verify(quizSessions).save(captor.capture());
        assertEquals("PRACTICE_REPLAY", captor.getValue().getQuizType());
        assertEquals(ACTIVITY, captor.getValue().getActivityId());
        assertEquals(issued.attemptId(), 900L);
        assertFalse(issued.questions().isEmpty());
        verifyNoInteractions(checkStates);
        assertEquals(LearningActivityState.PASSED, activity.getActivityState());
    }

    @Test
    void lowPracticeScoreDoesNotDemoteOrRecordAnyMicroCheckAttempt() {
        LearningQuizSession session = issuedPracticeSession();
        when(quizSessions.findOwnedByActivity(900L, USER, "PRACTICE_REPLAY", ACTIVITY)).thenReturn(Optional.of(session));

        MicroCheckResult result = engine.submitPractice(USER, ACTIVITY,
                new MicroCheckSubmitRequest(900L, List.of(new MicroCheckSubmitRequest.MicroCheckAnswer(1L, 99))));

        assertFalse(result.passed());
        assertEquals(0, result.scorePercent());
        assertEquals(LearningActivityState.PASSED, activity.getActivityState());
        assertEquals("SUBMITTED", session.getStatus());
        verifyNoInteractions(checkStates);
        verify(items, never()).save(any());
    }

    @Test
    void submittingPracticeAgainReturnsTheStoredResult() {
        LearningQuizSession session = issuedPracticeSession();
        when(quizSessions.findOwnedByActivity(900L, USER, "PRACTICE_REPLAY", ACTIVITY)).thenReturn(Optional.of(session));
        var request = new MicroCheckSubmitRequest(900L,
                List.of(new MicroCheckSubmitRequest.MicroCheckAnswer(1L, 1)));

        MicroCheckResult first = engine.submitPractice(USER, ACTIVITY, request);
        MicroCheckResult retry = engine.submitPractice(USER, ACTIVITY, request);

        assertEquals(first.scorePercent(), retry.scorePercent());
        verify(quizSessions, times(1)).save(any()); // the retry must not write again
    }

    @Test
    void practiceRequiresAFinishedActivity() {
        activity.setActivityState(LearningActivityState.CHECK_READY);
        ApiException error = assertThrows(ApiException.class, () -> engine.issuePractice(USER, ACTIVITY));
        assertEquals(ErrorCode.PREREQUISITE_NOT_MET, error.getErrorCode());
        verify(quizSessions, never()).save(any());
    }

    private LearningQuizSession issuedPracticeSession() {
        engine.issuePractice(USER, ACTIVITY);
        var captor = org.mockito.ArgumentCaptor.forClass(LearningQuizSession.class);
        verify(quizSessions).save(captor.capture());
        clearInvocations(quizSessions);
        doAnswer(call -> call.getArgument(0)).when(quizSessions).save(any());
        return captor.getValue();
    }

    /** Synthetic adapter: entity i is the string "w" + i; one question per item, correct option is always 1. */
    static class StringAdapter implements InteractiveContentAdapter<String> {
        @Override public LearningActivityType activityType() { return LearningActivityType.VOCABULARY; }
        @Override public LearningMemberType memberType() { return LearningMemberType.VOCABULARY_ENTRY; }
        @Override public String microCheckType() { return "VOCAB_MICRO_CHECK"; }
        @Override public Map<Long, String> loadByIds(List<Long> ids) {
            Map<Long, String> map = new java.util.LinkedHashMap<>();
            ids.forEach(id -> map.put(id, "w" + id));
            return map;
        }
        @Override public List<QuestionSpec> buildQuestions(List<String> batch, int batchIndex, int seed) {
            List<QuestionSpec> specs = new ArrayList<>();
            for (String word : batch) {
                long id = Long.parseLong(word.substring(1));
                specs.add(new QuestionSpec(id, "MEANING", "what is " + word, List.of("a", "b", "c", "d"), 1, word));
            }
            return specs;
        }
    }
}
