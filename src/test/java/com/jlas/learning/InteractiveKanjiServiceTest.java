package com.jlas.learning;

import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Kanji;
import com.jlas.dictionary.entity.KanjiReading;
import com.jlas.dictionary.repository.KanjiReadingRepository;
import com.jlas.dictionary.repository.KanjiRepository;
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
import com.jlas.learning.service.impl.InteractiveKanjiServiceImpl;
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

/** [JLAS-W0193] Task 5 — same server-owned state machine as Vocabulary (Task 4), reused via the extracted
 * {@code InteractiveStudyEngine}; this suite focuses on what's new for Kanji: ownership isolation from other
 * activity types, the four question shapes, and real-data-only card rendering. */
@ExtendWith(MockitoExtension.class)
class InteractiveKanjiServiceTest {
    @Mock UserLearningPathItemRepository items;
    @Mock LearningActivityMemberRepository members;
    @Mock LearningStudySessionRepository studySessions;
    @Mock LearningStudyCardRepository studyCards;
    @Mock LearningMicroCheckStateRepository checkStates;
    @Mock LearningQuizSessionRepository quizSessions;
    @Mock KanjiRepository kanjiRepo;
    @Mock KanjiReadingRepository kanjiReadingRepo;
    @Mock LearningMapper mapper;

    InteractiveKanjiServiceImpl service;

    @BeforeEach void setUp() {
        service = new InteractiveKanjiServiceImpl(items, members, studySessions, studyCards,
                checkStates, quizSessions, kanjiRepo, kanjiReadingRepo, mapper);
    }

    @Test void studyStateIsOwnerScoped() {
        when(items.findOwnedByIdForUpdate(99L, 7L)).thenReturn(Optional.empty());
        ApiException error = assertThrows(ApiException.class, () -> service.studyState(7L, 99L));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test void vocabularyActivityIsRejectedAsNotFoundForKanjiService() {
        User user = User.builder().id(7L).build();
        UserLearningPath path = UserLearningPath.builder().id(100L).user(user).build();
        UserLearningPathItem vocabActivity = UserLearningPathItem.builder().id(1L).learningPath(path)
                .activityType(LearningActivityType.VOCABULARY).activityState(LearningActivityState.STUDYING).build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(vocabActivity));

        ApiException error = assertThrows(ApiException.class, () -> service.studyState(7L, 1L));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test void rememberRequiresFlipFirst() {
        var activity = kanjiActivity(1L, 7L);
        var member = member(1, 201L);
        var session = LearningStudySession.builder().id(10L).activity(activity).sourceRevision("rev").build();
        var card = LearningStudyCard.builder().id(20L).session(session).canonicalId(201L)
                .orderIndex(1).flipped(false).remembered(false).build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(studySessions.findByActivityId(1L)).thenReturn(Optional.of(session));
        when(studySessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(studyCards.findBySessionIdOrderByOrderIndex(10L)).thenReturn(new ArrayList<>(List.of(card)));
        when(studyCards.findBySessionIdAndCanonicalId(10L, 201L)).thenReturn(Optional.of(card));

        ApiException error = assertThrows(ApiException.class, () -> service.remember(7L, 1L, 201L, true));
        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
    }

    @Test void allRememberedMarksCheckReadyAndRendersRealDataOnly() {
        var activity = kanjiActivity(1L, 7L);
        var member = member(1, 201L);
        var session = LearningStudySession.builder().id(10L).activity(activity).sourceRevision("rev").build();
        var card = LearningStudyCard.builder().id(20L).session(session).canonicalId(201L)
                .orderIndex(1).flipped(true).remembered(true).build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(studySessions.findByActivityId(1L)).thenReturn(Optional.of(session));
        when(studySessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(studyCards.findBySessionIdOrderByOrderIndex(10L)).thenReturn(new ArrayList<>(List.of(card)));
        when(kanjiRepo.findAllById(any())).thenReturn(List.of(kanji(201L, "間", "GIAN", "khoang, giua")));
        // No kanji_reading rows at all for this kanji — must render empty lists, never fabricate a reading.
        when(kanjiReadingRepo.findByKanjiIdInOrderByKanjiIdAscReadingTypeAscIdAsc(any())).thenReturn(List.of());

        var result = service.studyState(7L, 1L);

        assertTrue(result.checkEligible());
        assertEquals(LearningActivityState.CHECK_READY, activity.getActivityState());
        var view = result.cards().get(0);
        assertEquals("間", view.character());
        assertEquals("GIAN", view.hanViet());
        assertTrue(view.onReadings().isEmpty());
        assertTrue(view.kunReadings().isEmpty());
    }

    /** [JLAS-W0125] A part created by a pace change knows its position so the page can show "Phần x / n". */
    @Test void studyStateReportsThePartPositionInsideItsPackage() {
        var activity = kanjiActivity(1L, 7L);
        var pkg = com.jlas.learning.entity.LearningPathPackage.builder().id(55L).build();
        activity.setLearningPackage(pkg);
        activity.setPartIndex(5);
        activity.setOrderIndex(40);
        var member = member(1, 201L);
        var session = LearningStudySession.builder().id(10L).activity(activity).sourceRevision("rev").build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(items.countByLearningPackageIdAndActivityType(55L, LearningActivityType.KANJI)).thenReturn(3L);
        // part_index 5 is an identity left by a repeated split; the position comes from study order.
        when(items.countByLearningPackageIdAndActivityTypeAndOrderIndexLessThan(55L, LearningActivityType.KANJI, 40))
                .thenReturn(1L);
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(studySessions.findByActivityId(1L)).thenReturn(Optional.of(session));
        when(studySessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var card = LearningStudyCard.builder().id(20L).session(session).canonicalId(201L)
                .orderIndex(1).flipped(false).remembered(false).build();
        when(studyCards.findBySessionIdOrderByOrderIndex(10L)).thenReturn(new ArrayList<>(List.of(card)));
        when(kanjiRepo.findAllById(any())).thenReturn(List.of(kanji(201L, "間", "GIAN", "khoang, giua")));
        when(kanjiReadingRepo.findByKanjiIdInOrderByKanjiIdAscReadingTypeAscIdAsc(any())).thenReturn(List.of());

        var result = service.studyState(7L, 1L);

        assertEquals(2, result.partIndex());
        assertEquals(3, result.partCount());
    }

    @Test void issueRequiresAllCardsRemembered() {
        var activity = kanjiActivity(1L, 7L);
        var member = member(1, 201L);
        var session = LearningStudySession.builder().id(10L).activity(activity).sourceRevision("rev").build();
        var card = LearningStudyCard.builder().id(20L).session(session).canonicalId(201L)
                .orderIndex(1).flipped(true).remembered(false).build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(studySessions.findByActivityId(1L)).thenReturn(Optional.of(session));
        when(studyCards.findBySessionIdOrderByOrderIndex(10L)).thenReturn(new ArrayList<>(List.of(card)));

        ApiException error = assertThrows(ApiException.class, () -> service.issueMicroCheck(7L, 1L));
        assertEquals(ErrorCode.PREREQUISITE_NOT_MET, error.getErrorCode());
    }

    @Test void issueCoversEveryTargetWithFourQuestionShapes() {
        var activity = kanjiActivity(1L, 7L);
        List<LearningActivityMember> membership = new ArrayList<>();
        for (int i = 0; i < 5; i++) membership.add(member(i + 1, 200L + i));
        var session = LearningStudySession.builder().id(10L).activity(activity).sourceRevision("rev").build();
        List<LearningStudyCard> cards = new ArrayList<>();
        List<Kanji> kanjiList = new ArrayList<>();
        List<KanjiReading> readings = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long id = 200L + i;
            cards.add(LearningStudyCard.builder().id(30L + i).session(session).canonicalId(id)
                    .orderIndex(i + 1).flipped(true).remembered(true).build());
            Kanji k = kanji(id, "字" + i, "TU" + i, "nghia " + i);
            kanjiList.add(k);
            readings.add(KanjiReading.builder().id(id * 10).kanji(k).readingType("ON").readingText("on" + i)
                    .isCommon(true).build());
        }
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(membership);
        when(studySessions.findByActivityId(1L)).thenReturn(Optional.of(session));
        when(studyCards.findBySessionIdOrderByOrderIndex(10L)).thenReturn(cards);
        when(checkStates.findByActivityIdOrderByBatchIndex(1L)).thenReturn(List.of());
        when(kanjiRepo.findAllById(any())).thenReturn(kanjiList);
        when(kanjiReadingRepo.findByKanjiIdInOrderByKanjiIdAscReadingTypeAscIdAsc(any())).thenReturn(readings);
        var questionRef = new java.util.concurrent.atomic.AtomicReference<List<com.jlas.learning.dto.LessonQuizResponse.LessonQuizQuestion>>();
        when(mapper.writeQuizQuestions(any())).thenAnswer(inv -> {
            questionRef.set(inv.getArgument(0));
            return "[]";
        });
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

        assertEquals(1, result.batchCount());
        // 5 select questions (round-robin over 3 types) + 5 matching = 10
        assertEquals(10, result.totalQuestions());
        assertEquals(10, keyRef.get().size());
        var matching = questionRef.get().stream().filter(q -> "MATCHING".equals(q.questionType())).toList();
        assertEquals(5, matching.size());
        assertEquals(5, matching.getFirst().options().size());
        assertTrue(matching.stream().allMatch(q -> q.options().equals(matching.getFirst().options())));
        assertEquals(5, matching.stream().map(q -> keyRef.get().get(q.questionId()).correctOption())
                .distinct().count());
        long distinctTypes = result.questions().stream().map(q -> q.questionType()).distinct().count();
        assertTrue(distinctTypes >= 2, "expected multiple question shapes, got " + distinctTypes);
    }

    /** R2 — eleven members used to be cut 10+1; the one-item batch could never produce a choice question
     * (options are drawn from the batch itself), so the learner was blocked forever after passing batch one. */
    @Test void elevenKanjiNeverLeaveAOneItemFinalBatch() {
        var activity = kanjiActivity(1L, 7L);
        List<LearningActivityMember> membership = new ArrayList<>();
        var session = LearningStudySession.builder().id(10L).activity(activity).sourceRevision("rev").build();
        List<LearningStudyCard> cards = new ArrayList<>();
        List<Kanji> kanjiList = new ArrayList<>();
        List<KanjiReading> readings = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            long id = 200L + i;
            membership.add(member(i + 1, id));
            cards.add(LearningStudyCard.builder().id(30L + i).session(session).canonicalId(id)
                    .orderIndex(i + 1).flipped(true).remembered(true).build());
            Kanji k = kanji(id, String.valueOf((char) ('一' + i)), "TU" + i, "nghia " + i);
            kanjiList.add(k);
            readings.add(KanjiReading.builder().id(id * 10).kanji(k).readingType("ON").readingText("on" + i)
                    .isCommon(true).build());
        }
        var firstPassed = LearningMicroCheckState.builder().activity(activity).batchIndex(0).passed(true).build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(membership);
        when(studySessions.findByActivityId(1L)).thenReturn(Optional.of(session));
        when(studyCards.findBySessionIdOrderByOrderIndex(10L)).thenReturn(cards);
        when(checkStates.findByActivityIdOrderByBatchIndex(1L)).thenReturn(List.of(firstPassed));
        when(kanjiRepo.findAllById(any())).thenReturn(kanjiList);
        when(kanjiReadingRepo.findByKanjiIdInOrderByKanjiIdAscReadingTypeAscIdAsc(any())).thenReturn(readings);
        when(mapper.writeQuizQuestions(any())).thenReturn("[]");
        when(mapper.writeQuizAnswerKey(any())).thenReturn("{}");
        when(quizSessions.save(any())).thenAnswer(inv -> {
            LearningQuizSession s = inv.getArgument(0);
            s.setId(500L);
            return s;
        });

        var result = service.issueMicroCheck(7L, 1L);

        // Batch count is unchanged (still two), only the trailing 10+1 is rebalanced to 6+5.
        assertEquals(1, result.batchIndex());
        assertEquals(2, result.batchCount());
        assertEquals(10, result.totalQuestions(), "five targets x (one select + one matching)");
        assertTrue(result.questions().stream().allMatch(q -> q.options() != null && q.options().size() >= 2));
    }

    /** [JLAS-W0193] Task 10 audit finding — same fix/rationale as
     * {@code InteractiveVocabularyServiceTest#reIssuingTheSameUnpassedBatchAfterAFailedAttemptVariesTheOptionArrangement}:
     * Kanji shares the same engine/adapter shape, so it shared the same pre-fix vulnerability. */
    @Test void reIssuingTheSameUnpassedBatchAfterAFailedAttemptVariesTheOptionArrangement() {
        var activity = kanjiActivity(1L, 7L);
        List<LearningActivityMember> membership = new ArrayList<>();
        for (int i = 0; i < 4; i++) membership.add(member(i + 1, 200L + i));
        var session = LearningStudySession.builder().id(10L).activity(activity).sourceRevision("rev").build();
        List<LearningStudyCard> cards = new ArrayList<>();
        List<Kanji> kanjiList = new ArrayList<>();
        List<KanjiReading> readings = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long id = 200L + i;
            cards.add(LearningStudyCard.builder().id(30L + i).session(session).canonicalId(id)
                    .orderIndex(i + 1).flipped(true).remembered(true).build());
            Kanji k = kanji(id, "字" + i, "TU" + i, "nghia " + i);
            kanjiList.add(k);
            readings.add(KanjiReading.builder().id(id * 10).kanji(k).readingType("ON").readingText("on" + i)
                    .isCommon(true).build());
        }
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(membership);
        when(studySessions.findByActivityId(1L)).thenReturn(Optional.of(session));
        when(studyCards.findBySessionIdOrderByOrderIndex(10L)).thenReturn(cards);
        when(kanjiRepo.findAllById(any())).thenReturn(kanjiList);
        when(kanjiReadingRepo.findByKanjiIdInOrderByKanjiIdAscReadingTypeAscIdAsc(any())).thenReturn(readings);
        when(mapper.writeQuizQuestions(any())).thenReturn("[]");
        when(mapper.writeQuizAnswerKey(any())).thenReturn("{}");
        when(quizSessions.save(any())).thenAnswer(inv -> {
            LearningQuizSession s = inv.getArgument(0);
            s.setId(500L);
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
        var activity = kanjiActivity(1L, 7L);
        LearningQuizSession session = issuedSession(100L, activity.getId());
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(quizSessions.findOwnedByActivity(100L, 7L, "KANJI_MICRO_CHECK", 1L)).thenReturn(Optional.of(session));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member(1, 201L)));
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

    @Test void submitFailsBelowSeventyPercent() {
        var activity = kanjiActivity(1L, 7L);
        LearningQuizSession session = issuedSession(100L, activity.getId());
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(quizSessions.findOwnedByActivity(100L, 7L, "KANJI_MICRO_CHECK", 1L)).thenReturn(Optional.of(session));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member(1, 201L)));
        when(checkStates.findForUpdate(1L, 0)).thenReturn(Optional.empty());
        when(mapper.readQuizAnswerKey(any())).thenReturn(answerKey(10));
        when(mapper.writeQuizResult(any())).thenReturn("{}");
        when(checkStates.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(quizSessions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.submitMicroCheck(7L, 1L, new MicroCheckSubmitRequest(100L, answers(6, 4)));

        assertFalse(result.passed());
        assertEquals(60, result.scorePercent());
        assertEquals(2, result.attemptsRemaining());
    }

    @Test void thirdFailureTriggersCooldownAndReview() {
        var activity = kanjiActivity(1L, 7L);
        LearningQuizSession session = issuedSession(100L, activity.getId());
        LearningMicroCheckState state = LearningMicroCheckState.builder().activity(activity).batchIndex(0)
                .cycleNumber(1).attemptsUsed(2).passed(false).reviewRequired(false).build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(quizSessions.findOwnedByActivity(100L, 7L, "KANJI_MICRO_CHECK", 1L)).thenReturn(Optional.of(session));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member(1, 201L)));
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
        var activity = kanjiActivity(1L, 7L);
        LearningQuizSession session = issuedSession(100L, activity.getId());
        session.setStatus("SUBMITTED");
        session.setScorePercent(70);
        session.setCorrectCount(7);
        session.setPassed(true);
        session.setQuestionCount(10);
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(quizSessions.findOwnedByActivity(100L, 7L, "KANJI_MICRO_CHECK", 1L)).thenReturn(Optional.of(session));
        var stored = new com.jlas.learning.dto.LessonQuizResult(10, 7, 70, true, 70, false, List.of(), List.of());
        when(mapper.readQuizResult(any())).thenReturn(stored);
        when(checkStates.findByActivityIdAndBatchIndex(1L, 0)).thenReturn(Optional.empty());

        var result = service.submitMicroCheck(7L, 1L, new MicroCheckSubmitRequest(100L, List.of()));

        assertEquals(70, result.scorePercent());
        verify(checkStates, never()).save(any());
    }

    // ---- helpers ----

    private UserLearningPathItem kanjiActivity(Long id, Long userId) {
        User user = User.builder().id(userId).build();
        UserLearningPath path = UserLearningPath.builder().id(100L).user(user).build();
        return UserLearningPathItem.builder().id(id).learningPath(path)
                .activityType(LearningActivityType.KANJI).activityState(LearningActivityState.STUDYING).build();
    }

    private LearningActivityMember member(int order, long canonicalId) {
        return LearningActivityMember.builder().activity(kanjiActivity(1L, 7L)).memberType(LearningMemberType.KANJI)
                .canonicalId(canonicalId).orderIndex(order).sourceRevision("rev").build();
    }

    private Kanji kanji(Long id, String character, String hanViet, String meaningVi) {
        return Kanji.builder().id(id).character(character).unicodeCode(Integer.toHexString(character.codePointAt(0)))
                .hanViet(hanViet).meaningVi(meaningVi).jlptLevel("N5").strokeCount((byte) 5).build();
    }

    private LearningQuizSession issuedSession(Long id, Long activityId) {
        return LearningQuizSession.builder().id(id).user(User.builder().id(7L).build())
                .quizType("KANJI_MICRO_CHECK").activityId(activityId).batchIndex(0)
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
