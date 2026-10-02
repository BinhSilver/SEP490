package com.jlas.learning;

import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.repository.GrammarPointRepository;
import com.jlas.learning.dto.GrammarFillSubmitRequest;
import com.jlas.learning.dto.GrammarOrderingSubmitRequest;
import com.jlas.learning.dto.GrammarStudyState;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.dto.QuizAnswerKey;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningGrammarPracticeProgress;
import com.jlas.learning.entity.LearningMicroCheckState;
import com.jlas.learning.entity.LearningQuizSession;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningGrammarPracticeProgressRepository;
import com.jlas.learning.repository.LearningMicroCheckStateRepository;
import com.jlas.learning.repository.LearningQuizSessionRepository;
import com.jlas.learning.repository.LearningStudyCardRepository;
import com.jlas.learning.repository.LearningStudySessionRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.service.impl.InteractiveGrammarServiceImpl;
import com.jlas.learning.service.impl.grammar.GrammarTextAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** [JLAS-W0193] Task 6 — Grammar: ordering + fill-blank practice gate, then the exact Vocabulary/Kanji
 * micro-check contract reused via {@code InteractiveStudyEngine}. Focuses on what's new for Grammar: the
 * practice-gate mechanics, ownership across points, and the two-questions-per-point/non-verbatim micro-check
 * — the attempt/cooldown/idempotent-submit machinery itself is already proven by Vocabulary's/Kanji's suites
 * and only re-confirmed here at the wiring level (pass/fail/cooldown/duplicate), same as Kanji's own suite did. */
@ExtendWith(MockitoExtension.class)
class InteractiveGrammarServiceTest {
    @Mock UserLearningPathItemRepository items;
    @Mock LearningActivityMemberRepository members;
    @Mock LearningStudySessionRepository studySessions;
    @Mock LearningStudyCardRepository studyCards;
    @Mock LearningMicroCheckStateRepository checkStates;
    @Mock LearningQuizSessionRepository quizSessions;
    @Mock LearningGrammarPracticeProgressRepository progress;
    @Mock GrammarPointRepository grammarPoints;
    @Mock LearningMapper mapper;

    InteractiveGrammarServiceImpl service;

    @BeforeEach void setUp() {
        service = new InteractiveGrammarServiceImpl(items, members, studySessions, studyCards, checkStates,
                quizSessions, progress, grammarPoints, mapper);
    }

    @Test void studyStateIsOwnerScoped() {
        when(items.findOwnedByIdForUpdate(99L, 7L)).thenReturn(Optional.empty());
        ApiException error = assertThrows(ApiException.class, () -> service.studyState(7L, 99L));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test void lockedGrammarCannotBeOpenedThroughDirectStudyEndpoint(){
        var activity=grammarActivity(1L,7L);
        activity.setActivityState(LearningActivityState.LOCKED);
        when(items.findOwnedByIdForUpdate(1L,7L)).thenReturn(Optional.of(activity));

        ApiException error=assertThrows(ApiException.class,()->service.studyState(7L,1L));

        assertEquals(ErrorCode.PREREQUISITE_NOT_MET,error.getErrorCode());
        verifyNoInteractions(members,grammarPoints,progress);
    }

    @Test void vocabularyActivityIsRejectedAsNotFoundForGrammarService() {
        User user = User.builder().id(7L).build();
        UserLearningPath path = UserLearningPath.builder().id(100L).user(user).build();
        UserLearningPathItem vocabActivity = UserLearningPathItem.builder().id(1L).learningPath(path)
                .activityType(LearningActivityType.VOCABULARY).activityState(LearningActivityState.STUDYING).build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(vocabActivity));

        ApiException error = assertThrows(ApiException.class, () -> service.studyState(7L, 1L));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test void studyStateBuildsComponentBreakdownShuffledOrderingAndBlankedFillFromRealData() {
        var activity = grammarActivity(1L, 7L);
        var member = member(activity, 1, 501L, "rev-1");
        var point = point(501L, "N1 は N2 です", "N1 là N2\nCâu khẳng định cơ bản.",
                "わたしは グエンです。\nTôi là Nguyễn.", "は đọc là wa ở vị trí này.");
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(grammarPoints.findAllById(any())).thenReturn(List.of(point));
        when(progress.findByActivityIdAndGrammarPointId(1L, 501L)).thenReturn(Optional.empty());

        GrammarStudyState state = service.studyState(7L, 1L);

        assertEquals(1, state.totalPoints());
        assertEquals(0, state.gateSatisfiedCount());
        assertFalse(state.checkEligible());
        var view = state.points().get(0);
        assertEquals("N1 là N2", view.objective());
        assertEquals("Câu khẳng định cơ bản.", view.usageNote());
        assertEquals(4, view.components().size());
        assertEquals("PARTICLE", view.components().get(1).role());
        assertEquals("AUXILIARY", view.components().get(3).role());
        assertEquals("わたしは", view.example().japanese().split(" ")[0]);
        assertEquals("Tôi là Nguyễn.", view.example().meaningVi());
        assertEquals(2, view.ordering().words().size());
        assertFalse(view.ordering().completed());
        assertFalse(view.fill().completed());
        assertTrue(view.fill().promptWithBlank().contains("_____"));
        // The blank must never reveal the missing word itself.
        assertFalse(view.fill().promptWithBlank().contains("グエンです"));
        assertFalse(view.gateSatisfied());
    }

    @Test void grammarPointOutsideThisActivitysMembershipIsRejectedAsNotFound() {
        var activity = grammarActivity(1L, 7L);
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member(activity, 1, 501L, "rev-1")));

        var request = new GrammarOrderingSubmitRequest(List.of(0, 1));
        ApiException error = assertThrows(ApiException.class,
                () -> service.submitOrdering(7L, 1L, 999L, request));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test void correctOrderingMarksProgressDoneAndReturnsAssembledSentenceAsFeedback() {
        var activity = grammarActivity(1L, 7L);
        var member = member(activity, 1, 501L, "rev-1");
        var point = point(501L, "N1 は N2 です", "N1 là N2", "わたしは グエンです。\nTôi là Nguyễn.", null);
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(grammarPoints.findById(501L)).thenReturn(Optional.of(point));
        when(progress.findForUpdate(1L, 501L)).thenReturn(Optional.empty());
        when(progress.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.submitOrdering(7L, 1L, 501L, new GrammarOrderingSubmitRequest(List.of(0, 1)));

        assertTrue(result.correct());
        assertFalse(result.gateSatisfied(), "fill practice not done yet");
        assertEquals("わたしは グエンです。", result.correctText());
        var captor = org.mockito.ArgumentCaptor.forClass(LearningGrammarPracticeProgress.class);
        verify(progress).save(captor.capture());
        assertTrue(captor.getValue().isOrderingDone());
        assertFalse(captor.getValue().isFillDone());
    }

    @Test void wrongOrderingDoesNotMarkProgressDoneButStillExplainsTheCorrectOrder() {
        var activity = grammarActivity(1L, 7L);
        var member = member(activity, 1, 501L, "rev-1");
        var point = point(501L, "N1 は N2 です", "N1 là N2", "わたしは グエンです。\nTôi là Nguyễn.", null);
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(grammarPoints.findById(501L)).thenReturn(Optional.of(point));
        when(progress.findForUpdate(1L, 501L)).thenReturn(Optional.empty());
        when(progress.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.submitOrdering(7L, 1L, 501L, new GrammarOrderingSubmitRequest(List.of(1, 0)));

        assertFalse(result.correct());
        assertTrue(result.explanation().contains("わたしは グエンです。"));
        var captor = org.mockito.ArgumentCaptor.forClass(LearningGrammarPracticeProgress.class);
        verify(progress).save(captor.capture());
        assertFalse(captor.getValue().isOrderingDone());
    }

    @Test void fillAnswerIsGradedAfterNormalizingWhitespaceCaseAndTrailingPunctuation() {
        var activity = grammarActivity(1L, 7L);
        var member = member(activity, 1, 501L, "rev-1");
        var point = point(501L, "N1 は N2 です", "N1 là N2", "わたしは グエンです。\nTôi là Nguyễn.", null);
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(grammarPoints.findById(501L)).thenReturn(Optional.of(point));
        when(progress.findForUpdate(1L, 501L)).thenReturn(Optional.empty());
        when(progress.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // The blank target for this sentence is "グエンです。" (index 1, see GrammarTextAnalyzerTest).
        var request = new GrammarFillSubmitRequest("  グエンです  ");

        var result = service.submitFill(7L, 1L, 501L, request);

        assertTrue(result.correct(), "trimmed/whitespace-normalized answer without the trailing 。 must still pass");
    }

    @Test void wrongFillAnswerDoesNotMarkProgressDone() {
        var activity = grammarActivity(1L, 7L);
        var member = member(activity, 1, 501L, "rev-1");
        var point = point(501L, "N1 は N2 です", "N1 là N2", "わたしは グエンです。\nTôi là Nguyễn.", null);
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(grammarPoints.findById(501L)).thenReturn(Optional.of(point));
        when(progress.findForUpdate(1L, 501L)).thenReturn(Optional.empty());
        when(progress.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.submitFill(7L, 1L, 501L, new GrammarFillSubmitRequest("ちがいます"));

        assertFalse(result.correct());
        var captor = org.mockito.ArgumentCaptor.forClass(LearningGrammarPracticeProgress.class);
        verify(progress).save(captor.capture());
        assertFalse(captor.getValue().isFillDone());
    }

    @Test void aRevisionBumpResetsStaleProgressInsteadOfCarryingItOver() {
        var activity = grammarActivity(1L, 7L);
        var member = member(activity, 1, 501L, "rev-2");
        var point = point(501L, "N1 は N2 です", "N1 là N2", "わたしは グエンです。\nTôi là Nguyễn.", null);
        var stale = LearningGrammarPracticeProgress.builder().activity(activity).grammarPointId(501L)
                .sourceRevision("rev-1").orderingDone(true).fillDone(true).build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(grammarPoints.findById(501L)).thenReturn(Optional.of(point));
        when(progress.findForUpdate(1L, 501L)).thenReturn(Optional.of(stale));
        when(progress.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.submitFill(7L, 1L, 501L, new GrammarFillSubmitRequest("nonsense"));

        assertFalse(result.correct());
        assertFalse(result.gateSatisfied(), "a new revision must not silently inherit the old revision's completed gate");
    }

    @Test void issueRequiresBothPracticesDoneForEveryPoint() {
        var activity = grammarActivity(1L, 7L);
        var member = member(activity, 1, 501L, "rev-1");
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(progress.findByActivityIdAndGrammarPointId(1L, 501L)).thenReturn(Optional.empty());

        ApiException error = assertThrows(ApiException.class, () -> service.issueMicroCheck(7L, 1L));
        assertEquals(ErrorCode.PREREQUISITE_NOT_MET, error.getErrorCode());
    }

    @Test void issueProducesAtLeastTwoNonVerbatimQuestionsPerPointOnceGateSatisfied() {
        var activity = grammarActivity(1L, 7L);
        var pointA = point(501L, "N1 は N2 です", "N1 là N2\nGiải thích A", "わたしは グエンです。\nTôi là Nguyễn.", null);
        var pointB = point(502L, "N1 の N2", "N2 của N1\nGiải thích B", "これは ほんです。\nCái này là sách.", null);
        List<LearningActivityMember> membership = List.of(
                member(activity, 1, 501L, "rev-1"), member(activity, 2, 502L, "rev-1"));
        var progressA = LearningGrammarPracticeProgress.builder().activity(activity).grammarPointId(501L)
                .sourceRevision("rev-1").orderingDone(true).fillDone(true).build();
        var progressB = LearningGrammarPracticeProgress.builder().activity(activity).grammarPointId(502L)
                .sourceRevision("rev-1").orderingDone(true).fillDone(true).build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(membership);
        when(progress.findByActivityIdAndGrammarPointId(1L, 501L)).thenReturn(Optional.of(progressA));
        when(progress.findByActivityIdAndGrammarPointId(1L, 502L)).thenReturn(Optional.of(progressB));
        when(checkStates.findByActivityIdOrderByBatchIndex(1L)).thenReturn(List.of());
        when(grammarPoints.findAllById(any())).thenReturn(List.of(pointA, pointB));
        when(mapper.writeQuizQuestions(any())).thenReturn("[]");
        var keyRef = new java.util.concurrent.atomic.AtomicReference<Map<Long, QuizAnswerKey>>();
        when(mapper.writeQuizAnswerKey(any())).thenAnswer(inv -> {
            keyRef.set(inv.getArgument(0));
            return "{}";
        });
        when(quizSessions.save(any())).thenAnswer(inv -> {
            LearningQuizSession s = inv.getArgument(0);
            s.setId(900L);
            return s;
        });

        var result = service.issueMicroCheck(7L, 1L);

        assertEquals(1, result.batchCount());
        // 2 points x 2 question types (MEANING_MATCH, USAGE_SELECT) = 4.
        assertEquals(4, result.totalQuestions());
        assertEquals(4, keyRef.get().size());
        long distinctTypes = result.questions().stream().map(q -> q.questionType()).distinct().count();
        assertEquals(2, distinctTypes);
        // Never verbatim-repeats the fill-blank prompt (which always contains the blank marker).
        assertTrue(result.questions().stream().noneMatch(q -> q.questionText().contains("_____")));
        // MEANING_MATCH content comes from `meaning`, never from the example sentence used in practice.
        assertTrue(result.questions().stream()
                .anyMatch(q -> "MEANING_MATCH".equals(q.questionType()) && q.questionText().contains("nghĩa là gì")));
        assertTrue(result.questions().stream()
                .anyMatch(q -> "USAGE_SELECT".equals(q.questionType())));
    }

    /** [JLAS-W0193] Task 10 audit finding — same fix/rationale as
     * {@code InteractiveVocabularyServiceTest#reIssuingTheSameUnpassedBatchAfterAFailedAttemptVariesTheOptionArrangement}:
     * Grammar shares the same engine/adapter shape for its micro-check, so it shared the same pre-fix
     * vulnerability despite its practice-gate step being different from Vocabulary/Kanji. */
    @Test void reIssuingTheSameUnpassedBatchAfterAFailedAttemptVariesTheOptionArrangement() {
        var activity = grammarActivity(1L, 7L);
        var pointA = point(501L, "N1 は N2 です", "N1 là N2\nGiải thích A", "わたしは グエンです。\nTôi là Nguyễn.", null);
        var pointB = point(502L, "N1 の N2", "N2 của N1\nGiải thích B", "これは ほんです。\nCái này là sách.", null);
        List<LearningActivityMember> membership = List.of(
                member(activity, 1, 501L, "rev-1"), member(activity, 2, 502L, "rev-1"));
        var progressA = LearningGrammarPracticeProgress.builder().activity(activity).grammarPointId(501L)
                .sourceRevision("rev-1").orderingDone(true).fillDone(true).build();
        var progressB = LearningGrammarPracticeProgress.builder().activity(activity).grammarPointId(502L)
                .sourceRevision("rev-1").orderingDone(true).fillDone(true).build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(membership);
        when(progress.findByActivityIdAndGrammarPointId(1L, 501L)).thenReturn(Optional.of(progressA));
        when(progress.findByActivityIdAndGrammarPointId(1L, 502L)).thenReturn(Optional.of(progressB));
        when(grammarPoints.findAllById(any())).thenReturn(List.of(pointA, pointB));
        when(mapper.writeQuizQuestions(any())).thenReturn("[]");
        when(mapper.writeQuizAnswerKey(any())).thenReturn("{}");
        when(quizSessions.save(any())).thenAnswer(inv -> {
            LearningQuizSession s = inv.getArgument(0);
            s.setId(900L);
            return s;
        });

        when(checkStates.findByActivityIdOrderByBatchIndex(1L)).thenReturn(List.of());
        var firstIssue = service.issueMicroCheck(7L, 1L);

        var failedOnceState = LearningMicroCheckState.builder().activity(activity).batchIndex(0)
                .cycleNumber(1).attemptsUsed(1).passed(false).reviewRequired(false).build();
        when(checkStates.findByActivityIdOrderByBatchIndex(1L)).thenReturn(List.of(failedOnceState));
        var secondIssue = service.issueMicroCheck(7L, 1L);

        assertEquals(firstIssue.questions().size(), secondIssue.questions().size());
        boolean anyOptionsDiffer = false;
        for (int i = 0; i < firstIssue.questions().size(); i++) {
            if (!firstIssue.questions().get(i).options().equals(secondIssue.questions().get(i).options())) {
                anyOptionsDiffer = true;
                break;
            }
        }
        assertTrue(anyOptionsDiffer,
                "re-issuing the same unpassed batch after a failed attempt should vary the option arrangement");
    }

    @Test void submitPassesAtExactlySeventyPercent() {
        var activity = grammarActivity(1L, 7L);
        LearningQuizSession session = issuedSession(100L, activity.getId());
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(quizSessions.findOwnedByActivity(100L, 7L, "GRAMMAR_MICRO_CHECK", 1L)).thenReturn(Optional.of(session));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member(activity, 1, 501L, "rev-1")));
        when(checkStates.findForUpdate(1L, 0)).thenReturn(Optional.empty());
        when(mapper.readQuizAnswerKey(any())).thenReturn(answerKey(10));
        when(mapper.writeQuizResult(any())).thenReturn("{}");
        var savedState = new java.util.concurrent.atomic.AtomicReference<LearningMicroCheckState>();
        when(checkStates.save(any())).thenAnswer(inv -> {
            LearningMicroCheckState s = inv.getArgument(0);
            savedState.set(s);
            return s;
        });
        when(quizSessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(checkStates.findByActivityIdOrderByBatchIndex(1L))
                .thenAnswer(inv -> savedState.get() == null ? List.of() : List.of(savedState.get()));

        var result = service.submitMicroCheck(7L, 1L, new MicroCheckSubmitRequest(100L, answers(7, 3)));

        assertTrue(result.passed());
        assertEquals(70, result.scorePercent());
        assertEquals(LearningActivityState.PASSED, result.activityState());
    }

    @Test void thirdFailureTriggersCooldownAndReview() {
        var activity = grammarActivity(1L, 7L);
        LearningQuizSession session = issuedSession(100L, activity.getId());
        LearningMicroCheckState state = LearningMicroCheckState.builder().activity(activity).batchIndex(0)
                .cycleNumber(1).attemptsUsed(2).passed(false).reviewRequired(false).build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(quizSessions.findOwnedByActivity(100L, 7L, "GRAMMAR_MICRO_CHECK", 1L)).thenReturn(Optional.of(session));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member(activity, 1, 501L, "rev-1")));
        when(checkStates.findForUpdate(1L, 0)).thenReturn(Optional.of(state));
        when(mapper.readQuizAnswerKey(any())).thenReturn(answerKey(10));
        when(mapper.writeQuizResult(any())).thenReturn("{}");
        when(checkStates.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(quizSessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(checkStates.findByActivityIdOrderByBatchIndex(1L)).thenReturn(List.of(state));

        var result = service.submitMicroCheck(7L, 1L, new MicroCheckSubmitRequest(100L, answers(0, 10)));

        assertEquals(3, result.attemptsUsed());
        assertTrue(result.reviewRequired());
        assertNotNull(result.cooldownUntil());
        assertEquals(LearningActivityState.COOLDOWN, result.activityState());
    }

    @Test void duplicateSubmitReturnsSavedResultWithoutConsumingAttempt() {
        var activity = grammarActivity(1L, 7L);
        LearningQuizSession session = issuedSession(100L, activity.getId());
        session.setStatus("SUBMITTED");
        session.setScorePercent(75);
        session.setCorrectCount(3);
        session.setPassed(true);
        session.setQuestionCount(4);
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(quizSessions.findOwnedByActivity(100L, 7L, "GRAMMAR_MICRO_CHECK", 1L)).thenReturn(Optional.of(session));
        var stored = new com.jlas.learning.dto.LessonQuizResult(4, 3, 75, true, 70, false, List.of(), List.of());
        when(mapper.readQuizResult(any())).thenReturn(stored);
        when(checkStates.findByActivityIdAndBatchIndex(1L, 0)).thenReturn(Optional.empty());

        var result = service.submitMicroCheck(7L, 1L, new MicroCheckSubmitRequest(100L, List.of()));

        assertEquals(75, result.scorePercent());
        verify(checkStates, never()).save(any());
    }

    // ---- helpers ----

    private UserLearningPathItem grammarActivity(Long id, Long userId) {
        User user = User.builder().id(userId).build();
        UserLearningPath path = UserLearningPath.builder().id(100L).user(user).build();
        return UserLearningPathItem.builder().id(id).learningPath(path)
                .activityType(LearningActivityType.GRAMMAR).activityState(LearningActivityState.STUDYING).build();
    }

    private LearningActivityMember member(UserLearningPathItem activity, int order, long canonicalId, String revision) {
        return LearningActivityMember.builder().activity(activity).memberType(LearningMemberType.GRAMMAR_POINT)
                .canonicalId(canonicalId).orderIndex(order).sourceRevision(revision).build();
    }

    private GrammarPoint point(Long id, String pattern, String meaning, String example, String note) {
        return GrammarPoint.builder().id(id).pattern(pattern).meaning(meaning).example(example).note(note)
                .orderIndex(1).build();
    }

    private LearningQuizSession issuedSession(Long id, Long activityId) {
        return LearningQuizSession.builder().id(id).user(User.builder().id(7L).build())
                .quizType("GRAMMAR_MICRO_CHECK").activityId(activityId).batchIndex(0)
                .questionCount(10).status("ISSUED").questionsJson("[]").answerKeyJson("{}").build();
    }

    private Map<Long, QuizAnswerKey> answerKey(int n) {
        Map<Long, QuizAnswerKey> key = new LinkedHashMap<>();
        for (long i = 1; i <= n; i++) key.put(i, new QuizAnswerKey(0, "correct-" + i, null, null));
        return key;
    }

    private List<MicroCheckSubmitRequest.MicroCheckAnswer> answers(int correct, int wrong) {
        List<MicroCheckSubmitRequest.MicroCheckAnswer> list = new ArrayList<>();
        long qid = 1;
        for (int i = 0; i < correct; i++) list.add(new MicroCheckSubmitRequest.MicroCheckAnswer(qid++, 0));
        for (int i = 0; i < wrong; i++) list.add(new MicroCheckSubmitRequest.MicroCheckAnswer(qid++, 1));
        return list;
    }
}
