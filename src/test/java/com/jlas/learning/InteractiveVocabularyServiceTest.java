package com.jlas.learning;

import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.dto.QuizAnswerKey;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningMicroCheckState;
import com.jlas.learning.entity.LearningQuizSession;
import com.jlas.learning.entity.LearningStudyCard;
import com.jlas.learning.entity.LearningStudySession;
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
import com.jlas.learning.service.impl.InteractiveVocabularyServiceImpl;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InteractiveVocabularyServiceTest {
    @Mock UserLearningPathItemRepository items;
    @Mock LearningActivityMemberRepository members;
    @Mock LearningStudySessionRepository studySessions;
    @Mock LearningStudyCardRepository studyCards;
    @Mock LearningMicroCheckStateRepository checkStates;
    @Mock LearningQuizSessionRepository quizSessions;
    @Mock VocabularyEntryRepository vocabularyRepo;
    @Mock LearningMapper mapper;

    InteractiveVocabularyServiceImpl service;

    @BeforeEach void setUp() {
        service = new InteractiveVocabularyServiceImpl(items, members, studySessions, studyCards,
                checkStates, quizSessions, vocabularyRepo, mapper);
    }

    // ---- Study ----

    @Test void studyStateIsOwnerScoped() {
        when(items.findOwnedByIdForUpdate(99L, 7L)).thenReturn(Optional.empty());
        ApiException error = assertThrows(ApiException.class, () -> service.studyState(7L, 99L));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test void rememberRequiresFlipFirst() {
        var activity = vocabActivity(1L, 7L);
        var member = member(1L, 1, 101L);
        var session = LearningStudySession.builder().id(10L).activity(activity).sourceRevision("rev").build();
        var card = LearningStudyCard.builder().id(20L).session(session).canonicalId(101L)
                .orderIndex(1).flipped(false).remembered(false).build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(studySessions.findByActivityId(1L)).thenReturn(Optional.of(session));
        when(studySessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(studyCards.findBySessionIdOrderByOrderIndex(10L)).thenReturn(new ArrayList<>(List.of(card)));
        when(studyCards.findBySessionIdAndCanonicalId(10L, 101L)).thenReturn(Optional.of(card));

        ApiException error = assertThrows(ApiException.class,
                () -> service.remember(7L, 1L, 101L, true));
        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        assertFalse(card.isRemembered());
    }

    @Test void unrememberedCardResetsFlipForReviewQueue() {
        var activity = vocabActivity(1L, 7L);
        var member = member(1L, 1, 101L);
        var session = LearningStudySession.builder().id(10L).activity(activity).sourceRevision("rev").build();
        var card = LearningStudyCard.builder().id(20L).session(session).canonicalId(101L)
                .orderIndex(1).flipped(true).remembered(false).build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(studySessions.findByActivityId(1L)).thenReturn(Optional.of(session));
        when(studyCards.findBySessionIdOrderByOrderIndex(10L)).thenReturn(new ArrayList<>(List.of(card)));
        when(studyCards.findBySessionIdAndCanonicalId(10L, 101L)).thenReturn(Optional.of(card));
        when(studySessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(vocabularyRepo.findAllById(any())).thenReturn(List.of(vocab(101L, "時間", "じかん", "thời gian")));

        var result = service.remember(7L, 1L, 101L, false);

        assertFalse(card.isFlipped());
        assertFalse(card.isRemembered());
        assertFalse(result.checkEligible());
    }

    @Test void allRememberedMarksCheckReadyAndEligible() {
        var activity = vocabActivity(1L, 7L);
        var member = member(1L, 1, 101L);
        var session = LearningStudySession.builder().id(10L).activity(activity).sourceRevision("rev").build();
        var card = LearningStudyCard.builder().id(20L).session(session).canonicalId(101L)
                .orderIndex(1).flipped(true).remembered(true).build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(studySessions.findByActivityId(1L)).thenReturn(Optional.of(session));
        when(studySessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(studyCards.findBySessionIdOrderByOrderIndex(10L)).thenReturn(new ArrayList<>(List.of(card)));
        when(vocabularyRepo.findAllById(any())).thenReturn(List.of(vocab(101L, "時間", "じかん", "thời gian")));

        var result = service.studyState(7L, 1L);

        assertTrue(result.checkEligible());
        assertEquals(1, result.rememberedCount());
        assertEquals(0, result.reviewRemaining());
        assertEquals(LearningActivityState.CHECK_READY, activity.getActivityState());
    }

    // ---- Micro-check issue ----

    @Test void issueRequiresAllCardsRemembered() {
        var activity = vocabActivity(1L, 7L);
        var member = member(1L, 1, 101L);
        var session = LearningStudySession.builder().id(10L).activity(activity).sourceRevision("rev").build();
        var card = LearningStudyCard.builder().id(20L).session(session).canonicalId(101L)
                .orderIndex(1).flipped(true).remembered(false).build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(studySessions.findByActivityId(1L)).thenReturn(Optional.of(session));
        when(studyCards.findBySessionIdOrderByOrderIndex(10L)).thenReturn(new ArrayList<>(List.of(card)));

        ApiException error = assertThrows(ApiException.class, () -> service.issueMicroCheck(7L, 1L));
        assertEquals(ErrorCode.PREREQUISITE_NOT_MET, error.getErrorCode());
    }

    @Test void issueBatchesAndCoversEveryTarget() {
        var activity = vocabActivity(1L, 7L);
        List<LearningActivityMember> membership = new ArrayList<>();
        for (int i = 0; i < 15; i++) membership.add(member(1L, i + 1, 100L + i));
        var session = LearningStudySession.builder().id(10L).activity(activity).sourceRevision("rev").build();
        List<LearningStudyCard> cards = new ArrayList<>();
        for (int i = 0; i < 15; i++) cards.add(LearningStudyCard.builder().id(30L + i).session(session)
                .canonicalId(100L + i).orderIndex(i + 1).flipped(true).remembered(true).build());
        List<VocabularyEntry> vocab = new ArrayList<>();
        for (int i = 0; i < 15; i++) vocab.add(vocab(100L + i, "word" + i, "reading" + i, "meaning" + i));
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(membership);
        when(studySessions.findByActivityId(1L)).thenReturn(Optional.of(session));
        when(studyCards.findBySessionIdOrderByOrderIndex(10L)).thenReturn(cards);
        when(checkStates.findByActivityIdOrderByBatchIndex(1L)).thenReturn(List.of());
        when(vocabularyRepo.findAllById(any())).thenReturn(vocab);
        when(mapper.writeQuizQuestions(any())).thenReturn("[]");
        var keyRef = new java.util.concurrent.atomic.AtomicReference<Map<Long, QuizAnswerKey>>();
        when(mapper.writeQuizAnswerKey(any())).thenAnswer(inv -> {
            keyRef.set(inv.getArgument(0));
            return "{}";
        });
        when(quizSessions.save(any())).thenAnswer(inv -> {
            LearningQuizSession s = inv.getArgument(0);
            s.setId(500L);
            return s;
        });

        var result = service.issueMicroCheck(7L, 1L);

        assertEquals(2, result.batchCount());
        assertEquals(0, result.batchIndex());
        assertEquals(20, result.totalQuestions());
        // every one of the 10 targets in batch 0 appears in a select question
        assertEquals(20, result.questions().size());
        // answer key stays server-side and uses the 1-based option index shared with LessonQuiz grading
        assertEquals(20, keyRef.get().size());
        assertTrue(keyRef.get().values().stream().allMatch(k -> k.correctOption() >= 1 && k.correctOption() <= 4));
    }

    /** [JLAS-W0193] Task 10 audit finding — re-issuing the same still-unpassed batch after a failed attempt
     * must not reproduce byte-identical question option arrangements, because a failed attempt's required
     * feedback ({@code MicroCheckResult.review}) reveals the correct answer per question. Deterministic
     * generation keyed only on (batch, batchIndex) — the pre-fix behavior — would let a learner replay the
     * revealed answer key verbatim on the very next issue without any real re-engagement with the content. */
    @Test void reIssuingTheSameUnpassedBatchAfterAFailedAttemptVariesTheOptionArrangement() {
        var activity = vocabActivity(1L, 7L);
        List<LearningActivityMember> membership = new ArrayList<>();
        for (int i = 0; i < 4; i++) membership.add(member(1L, i + 1, 100L + i));
        var session = LearningStudySession.builder().id(10L).activity(activity).sourceRevision("rev").build();
        List<LearningStudyCard> cards = new ArrayList<>();
        for (int i = 0; i < 4; i++) cards.add(LearningStudyCard.builder().id(30L + i).session(session)
                .canonicalId(100L + i).orderIndex(i + 1).flipped(true).remembered(true).build());
        List<VocabularyEntry> vocab = new ArrayList<>();
        for (int i = 0; i < 4; i++) vocab.add(vocab(100L + i, "word" + i, "reading" + i, "meaning" + i));
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(membership);
        when(studySessions.findByActivityId(1L)).thenReturn(Optional.of(session));
        when(studyCards.findBySessionIdOrderByOrderIndex(10L)).thenReturn(cards);
        when(vocabularyRepo.findAllById(any())).thenReturn(vocab);
        when(mapper.writeQuizQuestions(any())).thenReturn("[]");
        when(mapper.writeQuizAnswerKey(any())).thenReturn("{}");
        var nextAttempt = new java.util.concurrent.atomic.AtomicLong(500L);
        when(quizSessions.save(any())).thenAnswer(inv -> {
            LearningQuizSession s = inv.getArgument(0);
            s.setId(nextAttempt.getAndIncrement());
            return s;
        });

        // First-ever issue for this batch: no prior attempt.
        when(checkStates.findByActivityIdOrderByBatchIndex(1L)).thenReturn(List.of());
        var firstIssue = service.issueMicroCheck(7L, 1L);

        // Re-issue after one failed, valid attempt on the same batch/cycle.
        var failedOnceState = LearningMicroCheckState.builder().activity(activity).batchIndex(0)
                .cycleNumber(1).attemptsUsed(1).passed(false).reviewRequired(false).build();
        when(checkStates.findByActivityIdOrderByBatchIndex(1L)).thenReturn(List.of(failedOnceState));
        var secondIssue = service.issueMicroCheck(7L, 1L);

        assertNotEquals(firstIssue.attemptId(), secondIssue.attemptId());
        assertEquals(firstIssue.batchIndex(), secondIssue.batchIndex());
        assertEquals(firstIssue.totalQuestions(), secondIssue.totalQuestions());
        assertEquals(firstIssue.questions().size(), secondIssue.questions().size());
        assertEquals(firstIssue.questions().stream().map(q -> q.questionId()).collect(java.util.stream.Collectors.toSet()),
                secondIssue.questions().stream().map(q -> q.questionId()).collect(java.util.stream.Collectors.toSet()),
                "retry must retain the whole batch, not just missed targets");
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

    // ---- Micro-check submit ----

    @Test void submitPassesAtExactlySeventyPercent() {
        var activity = vocabActivity(1L, 7L);
        LearningQuizSession session = issuedSession(100L, activity.getId());
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(quizSessions.findOwnedByActivity(100L, 7L, "VOCAB_MICRO_CHECK", 1L)).thenReturn(Optional.of(session));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member(1L, 1, 101L)));
        when(checkStates.findForUpdate(1L, 0)).thenReturn(Optional.empty());
        Map<Long, QuizAnswerKey> key = answerKey(10);
        when(mapper.readQuizAnswerKey(any())).thenReturn(key);
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

        var request = new MicroCheckSubmitRequest(100L, answers(7, 3));
        var result = service.submitMicroCheck(7L, 1L, request);

        assertTrue(result.passed());
        assertEquals(70, result.scorePercent());
        assertEquals(7, result.correctAnswers());
        assertEquals(LearningActivityState.PASSED, result.activityState());
    }

    @Test void submitFailsBelowSeventyPercent() {
        var activity = vocabActivity(1L, 7L);
        LearningQuizSession session = issuedSession(100L, activity.getId());
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(quizSessions.findOwnedByActivity(100L, 7L, "VOCAB_MICRO_CHECK", 1L)).thenReturn(Optional.of(session));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member(1L, 1, 101L)));
        when(checkStates.findForUpdate(1L, 0)).thenReturn(Optional.empty());
        when(mapper.readQuizAnswerKey(any())).thenReturn(answerKey(10));
        when(mapper.writeQuizResult(any())).thenReturn("{}");
        when(checkStates.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(quizSessions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.submitMicroCheck(7L, 1L, new MicroCheckSubmitRequest(100L, answers(6, 4)));

        assertFalse(result.passed());
        assertEquals(60, result.scorePercent());
        assertEquals(1, result.attemptsUsed());
        assertEquals(2, result.attemptsRemaining());
    }

    @Test void thirdFailureTriggersCooldownAndReview() {
        var activity = vocabActivity(1L, 7L);
        LearningQuizSession session = issuedSession(100L, activity.getId());
        LearningMicroCheckState state = LearningMicroCheckState.builder().activity(activity).batchIndex(0)
                .cycleNumber(1).attemptsUsed(2).passed(false).reviewRequired(false).build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(quizSessions.findOwnedByActivity(100L, 7L, "VOCAB_MICRO_CHECK", 1L)).thenReturn(Optional.of(session));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member(1L, 1, 101L)));
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
        var activity = vocabActivity(1L, 7L);
        LearningQuizSession session = issuedSession(100L, activity.getId());
        session.setStatus("SUBMITTED");
        session.setScorePercent(70);
        session.setCorrectCount(7);
        session.setPassed(true);
        session.setQuestionCount(10);
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(quizSessions.findOwnedByActivity(100L, 7L, "VOCAB_MICRO_CHECK", 1L)).thenReturn(Optional.of(session));
        var stored = new com.jlas.learning.dto.LessonQuizResult(10, 7, 70, true, 70, false, List.of(), List.of());
        when(mapper.readQuizResult(any())).thenReturn(stored);
        when(checkStates.findByActivityIdAndBatchIndex(1L, 0)).thenReturn(Optional.empty());

        var result = service.submitMicroCheck(7L, 1L, new MicroCheckSubmitRequest(100L, List.of()));

        assertEquals(70, result.scorePercent());
        verify(checkStates, never()).save(any());
    }

    @Test void reviewRequiredBeforeNewCycleAfterCooldown() {
        var activity = vocabActivity(1L, 7L);
        LearningMicroCheckState state = LearningMicroCheckState.builder().activity(activity).batchIndex(0)
                .cycleNumber(1).attemptsUsed(3).passed(false).reviewRequired(true)
                .cooldownUntil(java.time.LocalDateTime.now().plusMinutes(60)).build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member(1L, 1, 101L)));
        when(checkStates.findByActivityIdOrderByBatchIndex(1L)).thenReturn(List.of(state));

        var result = service.microCheckState(7L, 1L);

        assertFalse(result.checkEligible());
        assertTrue(result.reviewRequired());
        assertEquals(LearningActivityState.COOLDOWN, result.activityState());
    }

    // ---- helpers ----

    private UserLearningPathItem vocabActivity(Long id, Long userId) {
        User user = User.builder().id(userId).build();
        UserLearningPath path = UserLearningPath.builder().id(100L).user(user).build();
        return UserLearningPathItem.builder().id(id).learningPath(path)
                .activityType(LearningActivityType.VOCABULARY).activityState(LearningActivityState.STUDYING).build();
    }

    private LearningActivityMember member(Long activityId, int order, long canonicalId) {
        return LearningActivityMember.builder().activity(vocabActivity(activityId, 7L)).memberType(LearningMemberType.VOCABULARY_ENTRY)
                .canonicalId(canonicalId).orderIndex(order).sourceRevision("rev").build();
    }

    private VocabularyEntry vocab(Long id, String expression, String reading, String meaning) {
        return VocabularyEntry.builder().id(id).expression(expression).normalizedExpression(expression)
                .reading(reading).normalizedReading(reading).meaning(meaning).build();
    }

    private LearningQuizSession issuedSession(Long id, Long activityId) {
        return LearningQuizSession.builder().id(id).user(User.builder().id(7L).build())
                .quizType("VOCAB_MICRO_CHECK").activityId(activityId).batchIndex(0)
                .questionCount(10).status("ISSUED")
                .questionsJson("[]").answerKeyJson("{}").build();
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
