package com.jlas.learning;

import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.repository.AssessmentQuestionRepository;
import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.*;
import com.jlas.learning.entity.*;
import com.jlas.learning.enums.*;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.repository.*;
import com.jlas.learning.service.ComprehensionExerciseCatalog;
import com.jlas.learning.service.impl.InteractiveComprehensionServiceImpl;
import com.jlas.learning.service.impl.engine.ComprehensionRevision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InteractiveComprehensionServiceTest {
    @Mock UserLearningPathItemRepository items;
    @Mock LearningActivityMemberRepository members;
    @Mock LearningStudySessionRepository studySessions;
    @Mock LearningStudyCardRepository studyCards;
    @Mock LearningMicroCheckStateRepository checkStates;
    @Mock LearningQuizSessionRepository quizSessions;
    @Mock AssessmentQuestionRepository questions;
    @Mock ComprehensionExerciseCatalog catalog;
    @Mock LearningMapper mapper;
    InteractiveComprehensionServiceImpl service;

    @BeforeEach void setUp() {
        service = new InteractiveComprehensionServiceImpl(items, members, studySessions, studyCards,
                checkStates, quizSessions, questions, catalog, mapper);
    }

    @Test void crossUserAndWrongActivityTypeAreSafeNotFound() {
        when(items.findOwnedByIdForUpdate(99L, 7L)).thenReturn(Optional.empty());
        assertEquals(ErrorCode.NOT_FOUND,
                assertThrows(ApiException.class, () -> service.studyState(7L, 99L)).getErrorCode());

        var vocab = activity(LearningActivityType.VOCABULARY);
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(vocab));
        assertEquals(ErrorCode.NOT_FOUND,
                assertThrows(ApiException.class, () -> service.studyState(7L, 1L)).getErrorCode());
    }

    @Test void exactExerciseGroupsBecomeSeparateBatchesWithoutAnswerLeakage() {
        arrangeTwoReadingExercises();
        when(checkStates.findByActivityIdOrderByBatchIndex(1L)).thenReturn(List.of());
        when(mapper.writeQuizQuestions(any())).thenReturn("questions");
        AtomicReference<Map<Long, QuizAnswerKey>> answerKey = new AtomicReference<>();
        when(mapper.writeQuizAnswerKey(any())).thenAnswer(inv -> {
            answerKey.set(inv.getArgument(0)); return "secret";
        });
        when(quizSessions.save(any())).thenAnswer(inv -> {
            var session = inv.getArgument(0, LearningQuizSession.class); session.setId(500L); return session;
        });

        var issued = service.issueMicroCheck(7L, 1L);

        assertEquals(2, issued.batchCount());
        assertEquals(2, issued.totalQuestions());
        assertEquals(List.of(101L, 102L), issued.questions().stream()
                .map(MicroCheckResponse.MicroCheckQuestion::questionId).toList());
        assertEquals(2, answerKey.get().size());
        assertEquals(4, MicroCheckResponse.MicroCheckQuestion.class.getRecordComponents().length);
    }

    @Test void studyStateReturnsSanitizedSourceAndNeverAnswerData() {
        arrangeTwoReadingExercises();
        when(checkStates.findByActivityIdOrderByBatchIndex(1L)).thenReturn(List.of());
        var state = service.studyState(7L, 1L);
        assertEquals("passage one", state.source().passage());
        assertNull(state.source().audioUrl());
        assertEquals(2, state.questions().size());
        assertEquals(3, ComprehensionStudyQuestion.class.getRecordComponents().length);
    }

    @Test void canonicalRevisionDriftBlocksInsteadOfMixingNewContentIntoOldActivity() {
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity(LearningActivityType.READING)));
        var activity = activity(LearningActivityType.READING);
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(
                member(activity, 1, 101, "0".repeat(64)), member(activity, 2, 102, "0".repeat(64))));
        when(questions.findAllById(any())).thenReturn(questionRows().subList(0, 2));
        when(catalog.resolve("READING", "N4")).thenReturn(
                new ComprehensionExerciseCatalog.CatalogResult(List.of(exercises().getFirst()), List.of()));
        assertEquals(ErrorCode.BLOCKED_CONTENT,
                assertThrows(ApiException.class, () -> service.studyState(7L, 1L)).getErrorCode());
    }

    @Test void submitPassesAtExactlySeventyAndKeepsServerScore() {
        var activity = activity(LearningActivityType.READING);
        var session = LearningQuizSession.builder().id(600L).user(User.builder().id(7L).build())
                .quizType("READING_MICRO_CHECK").activityId(1L).batchIndex(0).status("ISSUED")
                .questionsJson("q").answerKeyJson("key").build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(quizSessions.findOwnedByActivity(600L, 7L, "READING_MICRO_CHECK", 1L)).thenReturn(Optional.of(session));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(membership());
        when(checkStates.findForUpdate(1L, 0)).thenReturn(Optional.empty());
        when(mapper.readQuizAnswerKey("key")).thenReturn(answerKey(10));
        when(mapper.writeQuizResult(any())).thenReturn("result");
        when(quizSessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AtomicReference<LearningMicroCheckState> saved = new AtomicReference<>();
        when(checkStates.save(any())).thenAnswer(inv -> { saved.set(inv.getArgument(0)); return inv.getArgument(0); });
        arrangeContent();
        when(checkStates.findByActivityIdOrderByBatchIndex(1L)).thenAnswer(inv -> List.of(saved.get()));

        var result = service.submitMicroCheck(7L, 1L,
                new MicroCheckSubmitRequest(600L, answers(7, 3)));
        assertTrue(result.passed());
        assertEquals(70, result.scorePercent());
        assertEquals(LearningActivityState.CHECK_READY, result.activityState(),
                "passing one exercise must advance to the next instead of completing the whole activity");
    }

    @Test void reviewEvidenceBeforeThirdFailureCannotUnlockButNewServerEventCan() {
        var activity = activity(LearningActivityType.READING);
        var failedAt = LocalDateTime.now().minusMinutes(2);
        var state = LearningMicroCheckState.builder().activity(activity).batchIndex(0).cycleNumber(1)
                .attemptsUsed(3).passed(false).reviewRequired(true).cooldownUntil(failedAt.plusMinutes(60)).build();
        var study = LearningStudySession.builder().id(90L).activity(activity).sourceRevision("rev").build();
        var old1 = LearningStudyCard.builder().id(1L).session(study).canonicalId(101L).orderIndex(1)
                .flipped(true).flippedAt(failedAt.minusSeconds(1)).build();
        var old2 = LearningStudyCard.builder().id(2L).session(study).canonicalId(102L).orderIndex(2)
                .flipped(true).flippedAt(failedAt.minusSeconds(1)).build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(membership());
        when(checkStates.findByActivityIdOrderByBatchIndex(1L)).thenReturn(List.of(state));
        when(studySessions.findByActivityId(1L)).thenReturn(Optional.of(study));
        when(studyCards.findBySessionIdOrderByOrderIndex(90L)).thenReturn(List.of(old1, old2));
        when(studySessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(studyCards.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(checkStates.save(any())).thenAnswer(inv -> inv.getArgument(0));
        arrangeContent();

        assertEquals(ErrorCode.PREREQUISITE_NOT_MET,
                assertThrows(ApiException.class, () -> service.completeReview(7L, 1L)).getErrorCode());
        service.recordReviewEvidence(7L, 1L);
        assertDoesNotThrow(() -> service.completeReview(7L, 1L));
        assertFalse(state.isReviewRequired());
    }

    private void arrangeTwoReadingExercises() {
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity(LearningActivityType.READING)));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(membership());
        arrangeContent();
    }

    private void arrangeContent() {
        when(questions.findAllById(any())).thenReturn(questionRows());
        when(catalog.resolve("READING", "N4")).thenReturn(
                new ComprehensionExerciseCatalog.CatalogResult(exercises(), List.of()));
    }

    private UserLearningPathItem activity(LearningActivityType type) {
        return UserLearningPathItem.builder().id(1L)
                .learningPath(UserLearningPath.builder().id(10L).user(User.builder().id(7L).build()).build())
                .activityType(type).activityState(LearningActivityState.STUDYING).build();
    }

    private List<LearningActivityMember> membership() {
        var activity = activity(LearningActivityType.READING);
        String revision = ComprehensionRevision.combined(LearningActivityType.READING, exercises());
        return List.of(member(activity, 1, 101, revision), member(activity, 2, 102, revision),
                member(activity, 3, 201, revision), member(activity, 4, 202, revision));
    }

    private LearningActivityMember member(UserLearningPathItem activity, int order, long id, String revision) {
        return LearningActivityMember.builder().activity(activity).memberType(LearningMemberType.COMPREHENSION_QUESTION)
                .canonicalId(id).orderIndex(order).sourceRevision(revision).build();
    }

    private List<AssessmentQuestion> questionRows() {
        return List.of(question(101), question(102), question(201), question(202));
    }

    private AssessmentQuestion question(long id) {
        return AssessmentQuestion.builder().id(id).skill("READING").jlptLevel("N4").isActive(true).build();
    }

    private ComprehensionExercise exercise(String fp, String source, long first, long second) {
        return new ComprehensionExercise(fp, "READING", "N4", source, "OUT", null,
                List.of(new ComprehensionQuestionSpec(first, "Q" + first, List.of("A", "B"), 1, "E"),
                        new ComprehensionQuestionSpec(second, "Q" + second, List.of("A", "B"), 2, "E")),
                (fp + "0".repeat(64)).substring(0, 64));
    }

    private List<ComprehensionExercise> exercises() {
        return List.of(exercise("fp1", "passage one", 101L, 102L),
                exercise("fp2", "passage two", 201L, 202L));
    }

    private Map<Long, QuizAnswerKey> answerKey(int count) {
        Map<Long, QuizAnswerKey> result = new LinkedHashMap<>();
        for (long i = 1; i <= count; i++) result.put(i, new QuizAnswerKey(1, "E", null, null));
        return result;
    }

    private List<MicroCheckSubmitRequest.MicroCheckAnswer> answers(int correct, int wrong) {
        List<MicroCheckSubmitRequest.MicroCheckAnswer> result = new ArrayList<>(); long id = 1;
        for (int i = 0; i < correct; i++) result.add(new MicroCheckSubmitRequest.MicroCheckAnswer(id++, 1));
        for (int i = 0; i < wrong; i++) result.add(new MicroCheckSubmitRequest.MicroCheckAnswer(id++, 2));
        return result;
    }
}
