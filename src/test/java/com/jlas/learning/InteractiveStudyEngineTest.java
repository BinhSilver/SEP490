package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.MicroCheckDraftAnswer;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckSaveDraftRequest;
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
        when(quizSessions.findByActivityIdAndQuizTypeAndStatusOrderBySubmittedAtDesc(
                ACTIVITY, "VOCAB_MICRO_CHECK", "SUBMITTED"))
                .thenReturn(List.of(originalBatch(0, 1, 10), originalBatch(1, 11, 12)));
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
    void reviewCanOpenAnInProgressActivityReadOnlyForQuizResultNavigation() {
        activity.setActivityState(LearningActivityState.CHECK_READY);

        EngineStudyState<String> state = engine.reviewState(USER, ACTIVITY);

        assertEquals(LearningActivityState.CHECK_READY, state.activityState());
        assertEquals(12, state.totalCards());
        assertTrue(state.cards().stream().allMatch(EngineStudyState.EngineCard::flipped));
        verifyNoInteractions(checkStates, quizSessions, studySessions, studyCards);
        verify(items, never()).save(any());
    }

    @Test
    void lockedActivityCannotBeStartedThroughStudyEndpoint() {
        activity.setActivityState(LearningActivityState.LOCKED);

        ApiException error = assertThrows(ApiException.class, () -> engine.studyState(USER, ACTIVITY));

        assertEquals(ErrorCode.PREREQUISITE_NOT_MET, error.getErrorCode());
    }

    @Test
    void communicationActivitiesCannotSubmitLegacyMicroCheckAttempts() {
        activity.getLearningPath().setGoal("TRAVEL");

        ApiException error = assertThrows(ApiException.class, () -> engine.submitMicroCheck(USER, ACTIVITY,
                new MicroCheckSubmitRequest(900L, List.of())));

        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        verifyNoInteractions(quizSessions, checkStates);
    }

    @Test
    void communicationActivitiesCannotSaveQuizDrafts() {
        activity.getLearningPath().setGoal("BA");

        ApiException error = assertThrows(ApiException.class, () -> engine.saveDraft(USER, ACTIVITY,
                new MicroCheckSaveDraftRequest(900L, List.of())));

        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        verify(quizSessions, never()).findOwnedByActivity(any(), any(), any(), any());
        verify(quizSessions, never()).save(any());
    }

    @Test
    void communicationActivitiesCannotIssuePracticeReplays() {
        activity.getLearningPath().setGoal("DAILY_LIFE");

        ApiException error = assertThrows(ApiException.class, () -> engine.issuePractice(USER, ACTIVITY));

        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        verifyNoInteractions(quizSessions);
    }

    @Test
    void communicationActivitiesCannotSubmitPracticeReplays() {
        activity.getLearningPath().setGoal("TRAVEL");

        ApiException error = assertThrows(ApiException.class, () -> engine.submitPractice(USER, ACTIVITY,
                new MicroCheckSubmitRequest(900L, List.of())));

        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        verifyNoInteractions(quizSessions);
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

    @Test
    void reviewFeedbackReturnsTheLatestSubmittedAttemptPerBatchWithoutWriting() {
        LearningQuizSession older = submitted(1L, 0, 40, "2026-09-01T10:00:00");
        LearningQuizSession newer = submitted(2L, 0, 90, "2026-09-02T10:00:00");
        LearningQuizSession other = submitted(3L, 1, 70, "2026-09-01T11:00:00");
        when(quizSessions.findByActivityIdAndQuizTypeAndStatusOrderBySubmittedAtDesc(ACTIVITY, "VOCAB_MICRO_CHECK", "SUBMITTED"))
                .thenReturn(List.of(newer, other, older));

        var feedback = engine.reviewFeedback(USER, ACTIVITY);

        assertEquals(2, feedback.size());
        assertEquals(0, feedback.get(0).batchIndex());
        assertEquals(90, feedback.get(0).scorePercent());
        assertEquals(1, feedback.get(1).batchIndex());
        assertFalse(feedback.get(0).review().isEmpty());
        assertEquals("what is w1", feedback.get(0).review().get(0).questionText());
        assertEquals("a", feedback.get(0).review().get(0).correctAnswerText());
        assertEquals("a", feedback.get(0).review().get(0).selectedAnswerText());
        verify(quizSessions, never()).save(any());
        verifyNoInteractions(checkStates, studySessions, studyCards);
    }

    @Test
    void reviewFeedbackRequiresAFinishedActivity() {
        activity.setActivityState(LearningActivityState.COOLDOWN);
        ApiException error = assertThrows(ApiException.class, () -> engine.reviewFeedback(USER, ACTIVITY));
        assertEquals(ErrorCode.PREREQUISITE_NOT_MET, error.getErrorCode());
    }

    @Test
    void practiceStoresTheSourceSnapshotOfExactlyTheReplayedBatch() {
        var first = engine.issuePracticeWithSource(USER, ACTIVITY);
        assertEquals(0, first.response().batchIndex());
        assertTrue(first.sourceSnapshotJson().contains("source-of-batch-0"));
        when(quizSessions.countByActivityIdAndQuizType(ACTIVITY, "PRACTICE_REPLAY")).thenReturn(1L);

        var second = engine.issuePracticeWithSource(USER, ACTIVITY);

        assertEquals(1, second.response().batchIndex());
        assertTrue(second.sourceSnapshotJson().contains("source-of-batch-1"));
        assertFalse(second.sourceSnapshotJson().contains("source-of-batch-0"));
        var captor = org.mockito.ArgumentCaptor.forClass(LearningQuizSession.class);
        verify(quizSessions, times(2)).save(captor.capture());
        assertTrue(captor.getAllValues().get(1).getSourceSnapshotJson().contains("source-of-batch-1"));
        assertFalse(captor.getAllValues().get(1).getAnswerKeyJson().contains("source-of-batch"));
    }

    @Test
    void practiceCopiesTheOriginalSubmittedBatchSnapshotInsteadOfRebuildingCurrentContent() {
        var mapper = new LearningMapper();
        LearningQuizSession original = submitted(41L, 0, 90, "2026-09-02T10:00:00");
        original.setQuestionsJson(mapper.writeQuizQuestions(List.of(
                new com.jlas.learning.dto.LessonQuizResponse.LessonQuizQuestion(777L, "READING", "N4",
                        null, null, null, null, "OLD QUESTION", List.of("old-a", "old-b"), "SINGLE_CHOICE"))));
        original.setAnswerKeyJson(mapper.writeQuizAnswerKey(Map.of(777L,
                new com.jlas.learning.dto.QuizAnswerKey(2, "old explanation", null, null))));
        original.setSourceSnapshotJson("{\"passage\":\"OLD PASSAGE\"}");
        when(quizSessions.findByActivityIdAndQuizTypeAndStatusOrderBySubmittedAtDesc(
                ACTIVITY, "VOCAB_MICRO_CHECK", "SUBMITTED")).thenReturn(List.of(original));

        var issued = engine.issuePracticeWithSource(USER, ACTIVITY);

        var captor = org.mockito.ArgumentCaptor.forClass(LearningQuizSession.class);
        verify(quizSessions).save(captor.capture());
        assertEquals(original.getQuestionsJson(), captor.getValue().getQuestionsJson());
        assertEquals(original.getAnswerKeyJson(), captor.getValue().getAnswerKeyJson());
        assertEquals(original.getSourceSnapshotJson(), captor.getValue().getSourceSnapshotJson());
        assertEquals(777L, issued.response().questions().get(0).questionId());
        assertEquals("OLD QUESTION", issued.response().questions().get(0).questionText());
    }

    @Test
    void readingPracticeRejectsALegacyBatchWithoutAFrozenSourceSnapshot() {
        activity.setActivityType(LearningActivityType.READING);
        engine = new InteractiveStudyEngine<>(items, members, studySessions, studyCards, checkStates, quizSessions,
                new LearningMapper(), new StringAdapter() {
                    @Override public LearningActivityType activityType() { return LearningActivityType.READING; }
                });
        LearningQuizSession legacy = submitted(41L, 0, 90, "2026-09-02T10:00:00");
        when(quizSessions.findByActivityIdAndQuizTypeAndStatusOrderBySubmittedAtDesc(
                ACTIVITY, "VOCAB_MICRO_CHECK", "SUBMITTED")).thenReturn(List.of(legacy));

        ApiException error = assertThrows(ApiException.class,
                () -> engine.issuePracticeWithSource(USER, ACTIVITY));

        assertEquals("SOURCE_SNAPSHOT_UNAVAILABLE", error.getErrorCode().name());
        verify(quizSessions, never()).save(any());
    }

    @Test
    void vocabularyReplayNeedsStoredQuestionsAndKeyButNotAPassageSnapshot() {
        var original = originalBatch(0, 1, 2);
        original.setSourceSnapshotJson(null);
        when(quizSessions.findByActivityIdAndQuizTypeAndStatusOrderBySubmittedAtDesc(
                ACTIVITY, "VOCAB_MICRO_CHECK", "SUBMITTED")).thenReturn(List.of(original));

        var issued = engine.issuePractice(USER, ACTIVITY);

        assertEquals(2, issued.questions().size());
        assertEquals("what is w1", issued.questions().getFirst().questionText());
        assertEquals(LearningActivityState.PASSED, activity.getActivityState());
        verifyNoInteractions(checkStates, studyCards, studySessions);
    }

    @Test
    void learnMicroCheckStoresTheSourceSnapshotToo() {
        var open = new InteractiveStudyEngine<>(items, members, studySessions, studyCards, checkStates, quizSessions,
                new LearningMapper(), new StringAdapter());
        open.issueMicroCheck(USER, ACTIVITY);
        var captor = org.mockito.ArgumentCaptor.forClass(LearningQuizSession.class);
        verify(quizSessions).save(captor.capture());
        assertEquals("VOCAB_MICRO_CHECK", captor.getValue().getQuizType());
        assertTrue(captor.getValue().getSourceSnapshotJson().contains("source-of-batch-0"));
    }

    @Test
    void issuingAMicroCheckReopensAnInProgressAttemptInsteadOfCreatingANewSnapshot() {
        var mapper = new LearningMapper();
        LearningQuizSession inProgress = LearningQuizSession.builder()
                .id(555L).quizType("VOCAB_MICRO_CHECK").activityId(ACTIVITY).batchIndex(0)
                .status("ISSUED").questionCount(2).build();
        inProgress.setQuestionsJson(mapper.writeQuizQuestions(List.of(
                new com.jlas.learning.dto.LessonQuizResponse.LessonQuizQuestion(1L, "VOCABULARY", null, null, null,
                        null, null, "what is w1", List.of("a", "b"), "MEANING"),
                new com.jlas.learning.dto.LessonQuizResponse.LessonQuizQuestion(2L, "VOCABULARY", null, null, null,
                        null, null, "what is w2", List.of("c", "d"), "MEANING"))));
        inProgress.setAnswerKeyJson("{}");
        inProgress.setSourceSnapshotJson("{\"passage\":\"source-of-batch-0\"}");
        when(quizSessions.findFirstByActivityIdAndQuizTypeAndBatchIndexAndUserIdAndStatusOrderByIdDesc(
                ACTIVITY, "VOCAB_MICRO_CHECK", 0, USER, "ISSUED")).thenReturn(Optional.of(inProgress));

        MicroCheckResponse issued = engine.issueMicroCheck(USER, ACTIVITY);

        assertEquals(555L, issued.attemptId());
        assertEquals(0, issued.batchIndex());
        assertEquals(2, issued.totalQuestions());
        assertEquals(List.of(1L, 2L),
                issued.questions().stream().map(MicroCheckResponse.MicroCheckQuestion::questionId).toList());
        assertEquals("what is w1", issued.questions().get(0).questionText());
        verify(quizSessions, never()).save(any());
        verify(checkStates, never()).save(any());
    }

    @Test
    void reviewEntriesReturnTheStoredSnapshotNotTheCurrentCanonicalSource() {
        LearningQuizSession stored = submitted(1L, 0, 90, "2026-09-02T10:00:00");
        stored.setSourceSnapshotJson("{\"passage\":\"OLD PASSAGE\"}");
        when(quizSessions.findByActivityIdAndQuizTypeAndStatusOrderBySubmittedAtDesc(ACTIVITY, "VOCAB_MICRO_CHECK", "SUBMITTED"))
                .thenReturn(List.of(stored));

        var entries = engine.reviewEntries(USER, ACTIVITY);

        assertEquals(1, entries.size());
        assertEquals("{\"passage\":\"OLD PASSAGE\"}", entries.get(0).sourceSnapshotJson());
        assertEquals(90, entries.get(0).feedback().scorePercent());
    }

    @Test
    void attemptHistoryRetainsEverySubmittedLearnAndPracticeQuizInOrder() {
        LearningQuizSession older = submitted(11L, 0, 40, "2026-09-02T10:00:00");
        LearningQuizSession newer = submitted(12L, 0, 80, "2026-09-02T10:05:00");
        LearningQuizSession replay = submitted(13L, 0, 100, "2026-09-02T10:10:00");
        replay.setQuizType(InteractiveStudyEngine.PRACTICE_REPLAY);
        when(quizSessions.findByActivityIdAndQuizTypeAndStatusOrderBySubmittedAtDesc(ACTIVITY, "VOCAB_MICRO_CHECK", "SUBMITTED"))
                .thenReturn(List.of(newer, older));
        when(quizSessions.findByActivityIdAndQuizTypeAndStatusOrderBySubmittedAtDesc(ACTIVITY,
                InteractiveStudyEngine.PRACTICE_REPLAY, "SUBMITTED")).thenReturn(List.of(replay));

        var history = engine.quizAttemptHistory(USER, ACTIVITY);

        assertEquals(3, history.size());
        assertEquals(List.of(11L, 12L, 13L), history.stream().map(e -> e.attemptId()).toList());
        assertEquals(List.of(40, 80, 100), history.stream().map(e -> e.scorePercent()).toList());
        assertEquals(List.of("LEARN", "LEARN", "PRACTICE"), history.stream().map(e -> e.mode()).toList());
        assertEquals(List.of(1, 2, 1), history.stream().map(e -> e.attemptNumber()).toList());
    }

    @Test
    void legacyAttemptWithoutSavedQuestionTextKeepsItsActualFeedbackWithoutInventingAPrompt() {
        LearningQuizSession legacy = submitted(14L, 0, 40, "2026-09-02T10:00:00");
        legacy.setQuestionsJson("[]");
        when(quizSessions.findByActivityIdAndQuizTypeAndStatusOrderBySubmittedAtDesc(
                ACTIVITY, "VOCAB_MICRO_CHECK", "SUBMITTED")).thenReturn(List.of(legacy));

        var history = engine.quizAttemptHistory(USER, ACTIVITY);

        assertEquals(1, history.size());
        assertEquals(1, history.getFirst().review().size());
        assertNull(history.getFirst().review().getFirst().questionText());
        assertEquals("w1", history.getFirst().review().getFirst().explanation());
    }

    @Test
    void comprehensionHistoryUsesEachAttemptsOwnFrozenSourceAndNeverSubstitutesCurrentContent() {
        activity.setActivityType(LearningActivityType.LISTENING);
        var listening = new InteractiveStudyEngine<>(items, members, studySessions, studyCards, checkStates,
                quizSessions, new LearningMapper(), new StringAdapter() {
            @Override public LearningActivityType activityType() { return LearningActivityType.LISTENING; }
        });
        LearningQuizSession older = submitted(31L, 0, 40, "2026-09-02T10:00:00");
        older.setSourceSnapshotJson("{\"audioUrl\":\"/audio/old.mp3\"}");
        LearningQuizSession newer = submitted(32L, 0, 80, "2026-09-02T10:05:00");
        newer.setSourceSnapshotJson("{\"audioUrl\":\"/audio/new.mp3\"}");
        LearningQuizSession legacy = submitted(33L, 0, 100, "2026-09-02T10:10:00");
        when(quizSessions.findByActivityIdAndQuizTypeAndStatusOrderBySubmittedAtDesc(ACTIVITY,
                "VOCAB_MICRO_CHECK", "SUBMITTED")).thenReturn(List.of(legacy, newer, older));

        var history = listening.quizAttemptHistory(USER, ACTIVITY);

        assertEquals("/audio/old.mp3", history.get(0).source().audioUrl());
        assertEquals("/audio/new.mp3", history.get(1).source().audioUrl());
        assertEquals("UNAVAILABLE", history.get(2).sourceStatus());
        assertNull(history.get(2).source());
    }

    @Test
    void attemptHistoryIsAvailableBeforeTheActivityHasPassed() {
        activity.setActivityState(LearningActivityState.CHECK_READY);
        LearningQuizSession priorAttempt = submitted(21L, 0, 60, "2026-09-02T10:00:00");
        when(quizSessions.findByActivityIdAndQuizTypeAndStatusOrderBySubmittedAtDesc(
                ACTIVITY, "VOCAB_MICRO_CHECK", "SUBMITTED")).thenReturn(List.of(priorAttempt));

        var history = engine.quizAttemptHistory(USER, ACTIVITY);

        assertEquals(1, history.size());
        assertEquals(21L, history.get(0).attemptId());
        assertEquals(60, history.get(0).scorePercent());
        verify(items).findByIdAndUserId(ACTIVITY, USER);
    }

    @Test
    void attemptHistoryRejectsAnotherLearnerBeforeReadingSessions() {
        ApiException error = assertThrows(ApiException.class, () -> engine.quizAttemptHistory(8L, ACTIVITY));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
        verify(items).findByIdAndUserId(ACTIVITY, 8L);
        verify(quizSessions, never()).findByActivityIdAndQuizTypeAndStatusOrderBySubmittedAtDesc(
                any(), any(), any());
    }

    @Test
    void aLegacySessionWithoutASnapshotIsReportedAsSuchNeverBackfilled() {
        LearningQuizSession legacy = submitted(1L, 0, 90, "2026-09-02T10:00:00");
        when(quizSessions.findByActivityIdAndQuizTypeAndStatusOrderBySubmittedAtDesc(ACTIVITY, "VOCAB_MICRO_CHECK", "SUBMITTED"))
                .thenReturn(List.of(legacy));
        assertNull(engine.reviewEntries(USER, ACTIVITY).get(0).sourceSnapshotJson());
    }

    // ---- [JLAS-W0113] Autosave (saveDraft) ----

    @Test
    void saveDraftRejectsAnAttemptOwnedBySomeoneElse() {
        when(quizSessions.findOwnedByActivity(777L, USER, "VOCAB_MICRO_CHECK", ACTIVITY)).thenReturn(Optional.empty());

        ApiException error = assertThrows(ApiException.class, () -> engine.saveDraft(USER, ACTIVITY,
                new MicroCheckSaveDraftRequest(777L, List.of(new MicroCheckDraftAnswer(1L, 1)))));

        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCode());
        verify(quizSessions, never()).save(any());
    }

    @Test
    void saveDraftRejectsASubmittedAttempt() {
        LearningQuizSession session = issuedDraftSession();
        session.setStatus("SUBMITTED");
        when(quizSessions.findOwnedByActivity(777L, USER, "VOCAB_MICRO_CHECK", ACTIVITY)).thenReturn(Optional.of(session));

        ApiException error = assertThrows(ApiException.class, () -> engine.saveDraft(USER, ACTIVITY,
                new MicroCheckSaveDraftRequest(777L, List.of(new MicroCheckDraftAnswer(1L, 1)))));

        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        verify(quizSessions, never()).save(any());
    }

    @Test
    void saveDraftRejectsAQuestionNotInTheSnapshot() {
        LearningQuizSession session = issuedDraftSession();
        when(quizSessions.findOwnedByActivity(777L, USER, "VOCAB_MICRO_CHECK", ACTIVITY)).thenReturn(Optional.of(session));

        ApiException error = assertThrows(ApiException.class, () -> engine.saveDraft(USER, ACTIVITY,
                new MicroCheckSaveDraftRequest(777L, List.of(new MicroCheckDraftAnswer(999L, 1)))));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        verify(quizSessions, never()).save(any());
    }

    @Test
    void saveDraftRejectsAnOptionOutsideTheSnapshotsOptionCount() {
        LearningQuizSession session = issuedDraftSession();
        when(quizSessions.findOwnedByActivity(777L, USER, "VOCAB_MICRO_CHECK", ACTIVITY)).thenReturn(Optional.of(session));

        ApiException error = assertThrows(ApiException.class, () -> engine.saveDraft(USER, ACTIVITY,
                new MicroCheckSaveDraftRequest(777L, List.of(new MicroCheckDraftAnswer(1L, 5))))); // question 1 has 2 options

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        verify(quizSessions, never()).save(any());
    }

    @Test
    void saveDraftPersistsSelectionsAndDropsUnselected() {
        LearningQuizSession session = issuedDraftSession();
        when(quizSessions.findOwnedByActivity(777L, USER, "VOCAB_MICRO_CHECK", ACTIVITY)).thenReturn(Optional.of(session));

        List<MicroCheckDraftAnswer> saved = engine.saveDraft(USER, ACTIVITY,
                new MicroCheckSaveDraftRequest(777L,
                        List.of(new MicroCheckDraftAnswer(1L, 2), new MicroCheckDraftAnswer(2L, null))));

        assertEquals(1, saved.size());
        assertEquals(1L, saved.get(0).questionId());
        assertEquals(2, saved.get(0).selectedOption());
        assertNotNull(session.getDraftAnswersJson());
        verify(quizSessions).save(session);
    }

    @Test
    void saveDraftNeverGradesSpendsAnAttemptOrTouchesCheckState() {
        LearningQuizSession session = issuedDraftSession();
        when(quizSessions.findOwnedByActivity(777L, USER, "VOCAB_MICRO_CHECK", ACTIVITY)).thenReturn(Optional.of(session));

        engine.saveDraft(USER, ACTIVITY,
                new MicroCheckSaveDraftRequest(777L, List.of(new MicroCheckDraftAnswer(1L, 2))));

        assertNull(session.getResultJson());
        assertNull(session.getScorePercent());
        assertNull(session.getSubmittedAt());
        verifyNoInteractions(checkStates, studySessions, studyCards);
        verify(items, never()).save(any());
    }

    @Test
    void reopeningAnInProgressAttemptReturnsItsSavedDraftSelections() {
        var mapper = new LearningMapper();
        LearningQuizSession inProgress = issuedDraftSession();
        inProgress.setDraftAnswersJson(mapper.writeDraftAnswers(List.of(new MicroCheckDraftAnswer(1L, 2))));
        when(quizSessions.findFirstByActivityIdAndQuizTypeAndBatchIndexAndUserIdAndStatusOrderByIdDesc(
                ACTIVITY, "VOCAB_MICRO_CHECK", 0, USER, "ISSUED")).thenReturn(Optional.of(inProgress));

        MicroCheckResponse issued = engine.issueMicroCheck(USER, ACTIVITY);

        assertEquals(777L, issued.attemptId());
        assertEquals(1, issued.draftAnswers().size());
        assertEquals(1L, issued.draftAnswers().get(0).questionId());
        assertEquals(2, issued.draftAnswers().get(0).selectedOption());
        verify(quizSessions, never()).save(any());
    }

    private LearningQuizSession submitted(long id, int batch, int score, String at) {
        var mapper = new LearningMapper();
        var question = new com.jlas.learning.dto.LessonQuizResponse.LessonQuizQuestion(1L, "VOCABULARY", null, null, null,
                null, null, "what is w1", List.of("a", "b"), "MEANING");
        var review = new com.jlas.learning.dto.LessonQuizResult.AnswerReview(1L, 1, 1, true, "w1");
        var result = new com.jlas.learning.dto.LessonQuizResult(1, 1, score, score >= 70, 70, false, List.of(review), List.of());
        LearningQuizSession s = LearningQuizSession.builder().id(id).quizType("VOCAB_MICRO_CHECK").activityId(ACTIVITY)
                .batchIndex(batch).status("SUBMITTED").questionCount(1).scorePercent(score)
                .submittedAt(java.time.LocalDateTime.parse(at)).build();
        s.setQuestionsJson(mapper.writeQuizQuestions(List.of(question)));
        s.setAnswerKeyJson("{}");
        s.setResultJson(mapper.writeQuizResult(result));
        return s;
    }

    private LearningQuizSession originalBatch(int batch, int firstId, int lastId) {
        var mapper = new LearningMapper();
        var questions = new ArrayList<com.jlas.learning.dto.LessonQuizResponse.LessonQuizQuestion>();
        var answers = new java.util.LinkedHashMap<Long, com.jlas.learning.dto.QuizAnswerKey>();
        for (long id = firstId; id <= lastId; id++) {
            questions.add(new com.jlas.learning.dto.LessonQuizResponse.LessonQuizQuestion(id, "VOCABULARY",
                    null, null, null, null, null, "what is w" + id, List.of("a", "b", "c", "d"), "MEANING"));
            answers.put(id, new com.jlas.learning.dto.QuizAnswerKey(1, "w" + id, null, null));
        }
        LearningQuizSession session = LearningQuizSession.builder().id(100L + batch)
                .quizType("VOCAB_MICRO_CHECK").activityId(ACTIVITY).batchIndex(batch).status("SUBMITTED")
                .questionCount(questions.size()).submittedAt(java.time.LocalDateTime.parse("2026-09-02T10:00:00"))
                .build();
        session.setQuestionsJson(mapper.writeQuizQuestions(questions));
        session.setAnswerKeyJson(mapper.writeQuizAnswerKey(answers));
        session.setSourceSnapshotJson("{\"passage\":\"source-of-batch-" + batch + "\"}");
        return session;
    }

    private LearningQuizSession issuedPracticeSession() {
        engine.issuePractice(USER, ACTIVITY);
        var captor = org.mockito.ArgumentCaptor.forClass(LearningQuizSession.class);
        verify(quizSessions).save(captor.capture());
        clearInvocations(quizSessions);
        doAnswer(call -> call.getArgument(0)).when(quizSessions).save(any());
        return captor.getValue();
    }

    /** An ISSUED micro-check attempt (id 777, batch 0) with two 2-option questions and a real answer key. */
    private LearningQuizSession issuedDraftSession() {
        var mapper = new LearningMapper();
        var questions = List.of(
                new com.jlas.learning.dto.LessonQuizResponse.LessonQuizQuestion(1L, "VOCABULARY", null, null, null,
                        null, null, "what is w1", List.of("a", "b"), "MEANING"),
                new com.jlas.learning.dto.LessonQuizResponse.LessonQuizQuestion(2L, "VOCABULARY", null, null, null,
                        null, null, "what is w2", List.of("c", "d"), "MEANING"));
        var key = Map.of(
                1L, new com.jlas.learning.dto.QuizAnswerKey(1, "w1", null, null),
                2L, new com.jlas.learning.dto.QuizAnswerKey(1, "w2", null, null));
        LearningQuizSession session = LearningQuizSession.builder().id(777L).quizType("VOCAB_MICRO_CHECK")
                .activityId(ACTIVITY).batchIndex(0).status("ISSUED").questionCount(2).build();
        session.setQuestionsJson(mapper.writeQuizQuestions(questions));
        session.setAnswerKeyJson(mapper.writeQuizAnswerKey(key));
        return session;
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
        @Override public String sourceSnapshotJson(List<String> batch, int batchIndex, String sourceRevision) {
            return "{\"passage\":\"source-of-batch-" + batchIndex + "\",\"rev\":\"" + sourceRevision + "\"}";
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
