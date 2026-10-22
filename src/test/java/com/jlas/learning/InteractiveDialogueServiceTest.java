package com.jlas.learning;

import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.ContentAudio;
import com.jlas.dictionary.entity.ContentItem;
import com.jlas.dictionary.entity.ContentVoice;
import com.jlas.dictionary.entity.DialogueLineDetail;
import com.jlas.dictionary.entity.DialogueRole;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.LessonSection;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.repository.ContentAudioRepository;
import com.jlas.dictionary.repository.DialogueLineDetailRepository;
import com.jlas.learning.dto.DialogueHeartbeatRequest;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningDialogueProgress;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningDialogueProgressRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.service.impl.InteractiveDialogueServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** [JLAS-W0193] Task 7 — Dialogue: read-only canonical script plus server-timed, tamper-resistant completion.
 * Unlike Vocabulary/Kanji/Grammar this is not built on {@code InteractiveStudyEngine} (no score, no attempt
 * cycle) — these tests focus on what is genuinely new: heartbeat cap/reset arithmetic, reached-bottom
 * evidence, the AND-gated completion rule, replay/idempotency and ownership. */
@ExtendWith(MockitoExtension.class)
class InteractiveDialogueServiceTest {
    @Mock UserLearningPathItemRepository items;
    @Mock LearningActivityMemberRepository members;
    @Mock LearningDialogueProgressRepository progressRepo;
    @Mock DialogueLineDetailRepository dialogueLines;
    @Mock ContentAudioRepository audio;
    @Mock com.jlas.learning.repository.LearningStudySessionRepository studySessions;
    @Mock com.jlas.learning.repository.LearningStudyCardRepository studyCards;
    @Mock com.jlas.learning.repository.LearningDialogueAidRepository dialogueAids;
    @Mock com.jlas.dictionary.repository.GrammarPointRepository grammarPoints;
    @Mock com.jlas.learning.repository.LearningDialogueChallengeClearRepository challengeClears;
    @Mock com.jlas.moderation.credit.service.UserCreditService creditService;

    InteractiveDialogueServiceImpl service;

    @BeforeEach void setUp() {
        service = new InteractiveDialogueServiceImpl(items, members, progressRepo, dialogueLines, audio,
                studySessions, studyCards, dialogueAids, grammarPoints, challengeClears);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "creditService", creditService);
    }

    // ---- §2.24 G1–G3: optional "vượt ải" — pick the right reply at each learner turn; first clear = 2 CP once ----

    private static final String CHALLENGE_AID = """
            {"furigana":[],"phrases":[],"challenge":{"learnerSpeaker":"リン","turns":[{"line":2,
             "answer":{"ja":"はい、これです。","vi":"Vâng, đây ạ.","furigana":"はい、これです。"},
             "distractors":[{"ja":"いいえ、ありません。","vi":"Không, không có.","furigana":"いいえ、ありません。"},
                            {"ja":"またね。","vi":"Hẹn gặp lại.","furigana":"またね。"}],
             "why_vi":"Nhân viên xin hộ chiếu nên đưa ra là đúng."}]}}""";

    private UserLearningPathItem challengeActivity() {
        var activity = communicationActivity(1L, 7L);
        activity.setLesson(Lesson.builder().id(40L).title("J-LAS · Nhận phòng khách sạn").build());
        when(items.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(activity));
        org.mockito.Mockito.lenient().when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member(activity, 1, 201L, "rev-1"),
                member(activity, 2, 202L, "rev-1")));
        when(dialogueLines.findAllById(any())).thenReturn(List.of(
                dialogueLine(201L, "ROLE-1", "係員", "s", "trai", "パスポートをお願いします。", "Cho tôi xin hộ chiếu."),
                dialogueLine(202L, "ROLE-2", "リン", "s", "phai", "はい、これです。", "Vâng, đây ạ.")));
        when(audio.findByOwnerTypeAndOwnerIdIn(eq("CONTENT_ITEM"), any())).thenReturn(List.of());
        when(dialogueAids.findById(40L)).thenReturn(Optional.of(aid(40L, CHALLENGE_AID)));
        return activity;
    }

    @Test void challengeViewOffersThreeRepliesPerLearnerTurnWithoutRevealingTheAnswerAndInAStableOrder() {
        challengeActivity();
        when(challengeClears.existsByUserIdAndLessonId(7L, 40L)).thenReturn(false);

        var view = service.challenge(7L, 1L);
        var again = service.challenge(7L, 1L);

        assertTrue(view.available());
        assertFalse(view.cleared());
        assertEquals(2, view.rewardCredits());
        assertEquals("Nhận phòng khách sạn", view.situationTitle(), "the challenge names its situation, without the J-LAS prefix");
        assertEquals(1, view.turns().size());
        var turn = view.turns().get(0);
        assertEquals(202L, turn.lineId(), "the turn sits at the learner's line in the script");
        assertEquals(List.of("a", "b", "c"), turn.options().stream().map(o -> o.id()).toList());
        assertEquals(java.util.Set.of("はい、これです。", "いいえ、ありません。", "またね。"),
                turn.options().stream().map(o -> o.japanese()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(turn.options(), again.turns().get(0).options(), "same order every time");
        assertEquals(java.util.List.of("id", "japanese", "meaningVi", "furigana"),
                java.util.Arrays.stream(turn.options().get(0).getClass().getRecordComponents()).map(c -> c.getName()).toList(),
                "an option never carries a correctness flag");
    }

    @Test void answeringAChallengeTurnSaysWhetherItWasRightAndWhyWithoutWritingAnything() {
        challengeActivity();
        var view = service.challenge(7L, 1L);
        String right = view.turns().get(0).options().stream().filter(o -> o.japanese().equals("はい、これです。")).findFirst().orElseThrow().id();
        String wrong = view.turns().get(0).options().stream().filter(o -> !o.id().equals(right)).findFirst().orElseThrow().id();

        var ok = service.answerChallenge(7L, 1L, 0, right);
        var no = service.answerChallenge(7L, 1L, 0, wrong);

        assertTrue(ok.correct());
        assertFalse(no.correct());
        assertEquals(right, no.correctOptionId());
        assertTrue(no.explanationVi().contains("hộ chiếu"));
        verify(challengeClears, never()).insertIfAbsent(any(), any(), anyInt(), anyInt());
        verifyNoInteractions(creditService);
    }

    @Test void clearingTheChallengeTheFirstTimeAwardsTwoCreditsOnceAndLaterClearsAwardNothing() {
        challengeActivity();
        String right = service.challenge(7L, 1L).turns().get(0).options().stream()
                .filter(o -> o.japanese().equals("はい、これです。")).findFirst().orElseThrow().id();
        when(challengeClears.insertIfAbsent(7L, 40L, 1, 1)).thenReturn(1, 0);
        when(creditService.earn(7L, 2, com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason.STUDY_REWARD,
                "COMM_CHALLENGE", 40L, "Vượt ải thử thách hội thoại"))
                .thenReturn(new com.jlas.moderation.credit.service.UserCreditService.CreditResult(true, 12));

        var first = service.completeChallenge(7L, 1L, List.of(right));
        var second = service.completeChallenge(7L, 1L, List.of(right));

        assertTrue(first.cleared());
        assertTrue(first.firstClear());
        assertEquals(2, first.creditsAwarded());
        assertTrue(second.cleared());
        assertFalse(second.firstClear());
        assertEquals(0, second.creditsAwarded());
        verify(creditService, times(1)).earn(any(), anyInt(), any(), any(), any(), any());
    }

    @Test void aWrongReplyNeverClearsOrAwardsAndAWrongAnswerCountIsRejected() {
        challengeActivity();
        String wrong = service.challenge(7L, 1L).turns().get(0).options().stream()
                .filter(o -> !o.japanese().equals("はい、これです。")).findFirst().orElseThrow().id();

        var result = service.completeChallenge(7L, 1L, List.of(wrong));

        assertFalse(result.cleared());
        assertEquals(0, result.creditsAwarded());
        assertEquals(0, result.correctCount());
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(ApiException.class,
                () -> service.completeChallenge(7L, 1L, List.of("a", "b"))).getErrorCode());
        verify(challengeClears, never()).insertIfAbsent(any(), any(), anyInt(), anyInt());
        verifyNoInteractions(creditService);
    }

    @Test void jlptDialoguesAndStaleChallengesOfferNoChallenge() {
        var jlpt = dialogueActivity(1L, 7L);
        jlpt.setLesson(Lesson.builder().id(40L).build());
        when(items.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(jlpt));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member(jlpt, 1, 201L, "rev-1")));
        when(dialogueLines.findAllById(any())).thenReturn(List.of(dialogueLine(201L, "R", "Nhân viên", "s", "trai", "いらっしゃいませ。", "Xin chào.")));
        when(audio.findByOwnerTypeAndOwnerIdIn(eq("CONTENT_ITEM"), any())).thenReturn(List.of());
        assertFalse(service.challenge(7L, 1L).available());

        var activity = challengeActivity();
        when(dialogueLines.findAllById(any())).thenReturn(List.of(
                dialogueLine(201L, "ROLE-1", "係員", "s", "trai", "パスポートをお願いします。", "Cho tôi xin hộ chiếu."),
                dialogueLine(202L, "ROLE-2", "リン", "s", "phai", "はい、どうぞ。", "Vâng, xin mời.")));
        assertFalse(service.challenge(7L, 1L).available(), "a challenge whose answer is no longer in the script is withheld");
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(ApiException.class,
                () -> service.completeChallenge(7L, 1L, List.of("a"))).getErrorCode());
        assertNotNull(activity);
    }

    // ---- Owner 2026-10-19 (C2/C4): furigana, "Từ & cụm từ" and the end summary come from the lesson's aid row ----

    @Test void communicationStudyStateCarriesLineFuriganaPhrasesAndPatternsLabelledAsSupplement() {
        var activity = communicationActivity(1L, 7L);
        activity.setLesson(Lesson.builder().id(40L).build());
        stubScript(activity, dialogueLine(201L, "ROLE_A", "Nhân viên", "s", "trai", "パスポートと予約番号をお願いします。", "Cho tôi xin hộ chiếu."),
                dialogueLine(202L, "ROLE_B", "Linh", "s", "phai", "はい、これです。", "Vâng, đây ạ."));
        when(dialogueAids.findById(40L)).thenReturn(Optional.of(aid(40L, """
                {"furigana":["パスポートと{予約|よやく}{番号|ばんごう}をお{願|ねが}いします。","はい、これです。"],
                 "phrases":[{"ja":"予約番号","reading":"よやくばんごう","vi":"mã đặt chỗ","line":1}]}""")));
        when(grammarPoints.findByLessonIdOrderByOrderIndexAsc(40L)).thenReturn(List.of(
                com.jlas.dictionary.entity.GrammarPoint.builder().pattern("〜をお願いします").meaning("Cho tôi xin ~").note("Câu đề nghị").build()));

        var state = service.studyState(7L, 1L);

        assertEquals("パスポートと{予約|よやく}{番号|ばんごう}をお{願|ねが}いします。", state.lines().get(0).furigana());
        assertEquals("はい、これです。", state.lines().get(1).furigana());
        var aids = state.aids();
        assertNotNull(aids);
        assertEquals("Nội dung bổ sung J-LAS · cần rà soát", aids.label());
        assertEquals("NEEDS_REVIEW", aids.reviewStatus());
        assertEquals(1, aids.phrases().size());
        assertEquals("予約番号", aids.phrases().get(0).japanese());
        assertEquals("よやくばんごう", aids.phrases().get(0).reading());
        assertEquals("mã đặt chỗ", aids.phrases().get(0).meaningVi());
        assertEquals(201L, aids.phrases().get(0).lineId(), "the phrase points at the line that contains it");
        assertEquals(0, aids.phrases().get(0).index());
        assertFalse(aids.phrases().get(0).flipped());
        assertEquals("〜をお願いします", aids.patterns().get(0).pattern());
        assertEquals("Cho tôi xin ~", aids.patterns().get(0).meaningVi());
    }

    @Test void furiganaThatNoLongerMatchesItsLineIsDroppedAndBrokenAidJsonNeverBreaksTheDialogue() {
        var activity = communicationActivity(1L, 7L);
        activity.setLesson(Lesson.builder().id(40L).build());
        stubScript(activity, dialogueLine(201L, "ROLE_A", "Nhân viên", "s", "trai", "予約しました。", "Tôi đã đặt."));
        when(dialogueAids.findById(40L)).thenReturn(Optional.of(aid(40L,
                "{\"furigana\":[\"{予約|よやく}したグエンです。\"],\"phrases\":[{\"ja\":\"見つからない\",\"reading\":\"x\",\"vi\":\"y\",\"line\":1}]}")));
        when(grammarPoints.findByLessonIdOrderByOrderIndexAsc(40L)).thenReturn(List.of());

        var stale = service.studyState(7L, 1L);
        assertNull(stale.lines().get(0).furigana(), "a reading for another text must never be shown on this line");
        assertNull(stale.aids().phrases().get(0).lineId(), "a phrase absent from the script points at no line");

        when(dialogueAids.findById(40L)).thenReturn(Optional.of(aid(40L, "{not json")));
        var broken = service.studyState(7L, 1L);
        assertNull(broken.aids());
        assertEquals("予約しました。", broken.lines().get(0).japanese());
    }

    // ---- §2.24: "Từ & cụm từ" are flip cards and count toward completion with every line practised + the end ----

    @Test void communicationDialogueCompletesOnlyAfterEveryPhraseCardIsFlippedToo() {
        var activity = communicationActivity(1L, 7L);
        activity.setLesson(Lesson.builder().id(40L).build());
        var progress = new java.util.concurrent.atomic.AtomicReference<LearningDialogueProgress>();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member(activity, 1, 201L, "rev-1")));
        when(progressRepo.findForUpdate(1L)).thenAnswer(inv -> Optional.ofNullable(progress.get()));
        when(progressRepo.save(any())).thenAnswer(inv -> { progress.set(inv.getArgument(0)); return inv.getArgument(0); });
        when(dialogueAids.findById(40L)).thenReturn(Optional.of(aid(40L,
                "{\"furigana\":[],\"phrases\":[{\"ja\":\"予約\",\"reading\":\"よやく\",\"vi\":\"đặt\",\"line\":1},"
                        + "{\"ja\":\"番号\",\"reading\":\"ばんごう\",\"vi\":\"số\",\"line\":1}]}")));
        var cards = inMemoryPracticeStore(activity);

        service.practiceLine(7L, 1L, 201L);
        var lineAndEnd = service.reachedBottom(7L, 1L);
        var oneCard = service.flipPhrase(7L, 1L, 0);
        var sameCardAgain = service.flipPhrase(7L, 1L, 0);
        var allCards = service.flipPhrase(7L, 1L, 1);

        assertFalse(lineAndEnd.completed(), "practised lines and the end are not enough while phrase cards remain");
        assertEquals(2, lineAndEnd.totalPhrases());
        assertEquals(0, lineAndEnd.flippedPhrases());
        assertEquals(1, oneCard.flippedPhrases());
        assertFalse(oneCard.completed());
        assertEquals(1, sameCardAgain.flippedPhrases(), "flipping the same card twice counts once");
        assertTrue(allCards.completed());
        assertEquals(LearningActivityState.COMPLETED, activity.getActivityState());
        assertEquals(1L, cards.stream().filter(card -> card.getCanonicalId() == 201L).count(),
                "phrase cards never collide with line cards");
        assertEquals(3, cards.size());
    }

    @Test void flippingAPhraseOutsideTheAidIsNotFoundAndJlptDialoguesHaveNoPhraseCards() {
        var activity = communicationActivity(1L, 7L);
        activity.setLesson(Lesson.builder().id(40L).build());
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member(activity, 1, 201L, "rev-1")));
        when(dialogueAids.findById(40L)).thenReturn(Optional.of(aid(40L,
                "{\"furigana\":[],\"phrases\":[{\"ja\":\"予約\",\"reading\":\"よやく\",\"vi\":\"đặt\",\"line\":1}]}")));

        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ApiException.class, () -> service.flipPhrase(7L, 1L, 1)).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ApiException.class, () -> service.flipPhrase(7L, 1L, -1)).getErrorCode());

        var jlpt = dialogueActivity(2L, 7L);
        when(items.findOwnedByIdForUpdate(2L, 7L)).thenReturn(Optional.of(jlpt));
        when(members.findByActivityIdOrderByOrderIndex(2L)).thenReturn(List.of(member(jlpt, 1, 301L, "rev-1")));
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(ApiException.class, () -> service.flipPhrase(7L, 2L, 0)).getErrorCode());
    }

    @Test void jlptDialogueCarriesNoCommunicationAids() {
        var activity = dialogueActivity(1L, 7L);
        activity.setLesson(Lesson.builder().id(40L).build());
        stubScript(activity, dialogueLine(201L, "ROLE_A", "Nhân viên", "s", "trai", "いらっしゃいませ。", "Xin chào."));

        var state = service.studyState(7L, 1L);

        assertNull(state.aids());
        assertNull(state.lines().get(0).furigana());
        verifyNoInteractions(dialogueAids, grammarPoints);
    }

    private void stubScript(UserLearningPathItem activity, DialogueLineDetail... lines) {
        List<LearningActivityMember> membership = new java.util.ArrayList<>();
        for (int i = 0; i < lines.length; i++) membership.add(member(activity, i + 1, lines[i].getContentItemId(), "rev-1"));
        when(items.findOwnedByIdForUpdate(activity.getId(), 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(activity.getId())).thenReturn(membership);
        when(dialogueLines.findAllById(any())).thenReturn(List.of(lines));
        when(audio.findByOwnerTypeAndOwnerIdIn(eq("CONTENT_ITEM"), any())).thenReturn(List.of());
        when(progressRepo.findByActivityId(activity.getId())).thenReturn(Optional.empty());
        inMemoryPracticeStore(activity);
    }

    private static com.jlas.learning.entity.LearningDialogueAid aid(Long lessonId, String json) {
        return com.jlas.learning.entity.LearningDialogueAid.builder().lessonId(lessonId).aidJson(json)
                .reviewStatus("NEEDS_REVIEW").build();
    }

    // ---- Owner 2026-10-19: communication dialogues complete by line-by-line practice, never by time ----

    @Test void communicationDialogueCompletesAfterEveryLineIsPracticedAndTheEndIsReachedWithoutAnyTime() {
        var activity = communicationActivity(1L, 7L);
        var first = member(activity, 1, 201L, "rev-1");
        var second = member(activity, 2, 202L, "rev-1");
        var progress = new java.util.concurrent.atomic.AtomicReference<LearningDialogueProgress>();
        var cards = inMemoryPracticeStore(activity);
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(first, second));
        when(progressRepo.findForUpdate(1L)).thenAnswer(inv -> Optional.ofNullable(progress.get()));
        when(progressRepo.save(any())).thenAnswer(inv -> { progress.set(inv.getArgument(0)); return inv.getArgument(0); });

        var afterOne = service.practiceLine(7L, 1L, 201L);
        var afterBottom = service.reachedBottom(7L, 1L);
        var replay = service.practiceLine(7L, 1L, 201L);
        var afterAll = service.practiceLine(7L, 1L, 202L);

        assertFalse(afterOne.completed());
        assertEquals(1, afterOne.practicedLines());
        assertFalse(afterBottom.completed(), "the end alone is not practice");
        assertEquals(1, replay.practicedLines(), "practising the same line twice counts once");
        assertTrue(afterAll.completed());
        assertEquals(0, afterAll.requiredSeconds());
        assertEquals(0, afterAll.accumulatedSeconds(), "no time was needed");
        assertEquals(LearningActivityState.COMPLETED, activity.getActivityState());
        assertEquals(2, cards.size());
    }

    @Test void jlptDialogueKeepsItsTimedRuleAndRejectsLinePractice() {
        var activity = dialogueActivity(1L, 7L);
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member(activity, 1, 201L, "rev-1")));

        var error = assertThrows(ApiException.class, () -> service.practiceLine(7L, 1L, 201L));
        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
    }

    @Test void practisingALineOutsideTheScriptIsNotFound() {
        var activity = communicationActivity(1L, 7L);
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member(activity, 1, 201L, "rev-1")));

        var error = assertThrows(ApiException.class, () -> service.practiceLine(7L, 1L, 999L));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test void communicationReachedBottomNeedsNoPriorHeartbeat() {
        var activity = communicationActivity(1L, 7L);
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member(activity, 1, 201L, "rev-1")));
        when(progressRepo.findForUpdate(1L)).thenReturn(Optional.empty());
        when(progressRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        inMemoryPracticeStore(activity);

        var result = service.reachedBottom(7L, 1L);

        assertTrue(result.reachedBottom());
        assertTrue(result.lineByLinePractice());
        assertEquals(0, result.practicedLines());
        assertEquals(1, result.totalLines());
    }

    @Test void studyStateIsOwnerScoped() {
        when(items.findOwnedByIdForUpdate(99L, 7L)).thenReturn(Optional.empty());
        ApiException error = assertThrows(ApiException.class, () -> service.studyState(7L, 99L));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test void reviewAndPracticeReadCompletedScriptWithoutWritingCompletionEvidence() {
        var activity = dialogueActivity(1L, 7L);
        activity.setActivityState(LearningActivityState.COMPLETED);
        var member = member(activity, 1, 201L, "rev-1");
        var line = dialogueLine(201L, "ROLE_A", "Nhân viên", "scene-1", "trai", "いらっしゃいませ。", "Xin chào.");
        when(items.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(progressRepo.findByActivityId(1L)).thenReturn(Optional.empty());
        when(dialogueLines.findAllById(any())).thenReturn(List.of(line));
        when(audio.findByOwnerTypeAndOwnerIdIn(eq("CONTENT_ITEM"), any())).thenReturn(List.of());

        var review = service.reviewState(7L, 1L);
        var practice = service.practiceState(7L, 1L);

        assertEquals(1, review.lines().size());
        assertTrue(review.completed());
        assertEquals(review.lines(), practice.lines());
        verify(items, never()).findOwnedByIdForUpdate(anyLong(), anyLong());
        verify(progressRepo, never()).save(any());
    }

    @Test void replayRejectsUnfinishedDialogue() {
        var activity = dialogueActivity(1L, 7L);
        when(items.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(activity));
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ApiException.class,
                () -> service.reviewState(7L, 1L)).getErrorCode());
        verifyNoInteractions(members, progressRepo, dialogueLines, audio);
    }

    @Test void replayRejectsPassedStateBecauseDialogueOnlyCompletesWithoutAScore() {
        var activity = dialogueActivity(1L, 7L);
        activity.setActivityState(LearningActivityState.PASSED);
        when(items.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(activity));
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ApiException.class,
                () -> service.practiceState(7L, 1L)).getErrorCode());
        verifyNoInteractions(members, progressRepo, dialogueLines, audio);
    }

    @Test void replayFailsClosedWhenCanonicalDialogueWasRemoved() {
        var activity = dialogueActivity(1L, 7L);
        activity.setActivityState(LearningActivityState.COMPLETED);
        when(items.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member(activity, 1, 201L, "rev-1")));
        assertEquals(ErrorCode.BLOCKED_CONTENT, assertThrows(ApiException.class,
                () -> service.reviewState(7L, 1L)).getErrorCode());
        verify(progressRepo, never()).save(any());
    }

    @Test void canonicalRelativeAudioUsesTheConfiguredPublicResourceRoute() {
        var activity = dialogueActivity(1L, 7L);
        activity.setActivityState(LearningActivityState.COMPLETED);
        when(items.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member(activity, 1, 201L, "rev-1")));
        when(dialogueLines.findAllById(any())).thenReturn(List.of(
                dialogueLine(201L, "A", "A", "scene", "trai", "こんにちは。", "Xin chào.")));
        var row = ContentAudio.builder().ownerId(201L).audioUrl("sekai-n5/audio/chuan-nu/clip.mp3").build();
        when(audio.findByOwnerTypeAndOwnerIdIn(eq("CONTENT_ITEM"), any())).thenReturn(List.of(row));
        assertEquals("/uploads/content-audio/sekai-n5/audio/chuan-nu/clip.mp3",
                service.reviewState(7L, 1L).lines().getFirst().audioUrl());
        for (String unsafe : List.of("//outside.example/a.mp3", "../private.mp3", "javascript:alert(1)",
                "https://user:pass@outside.example/a.mp3", "%2e%2e/private.mp3", "a\\private.mp3")) {
            row.setAudioUrl(unsafe);
            assertNull(service.reviewState(7L, 1L).lines().getFirst().audioUrl(), unsafe);
        }
    }

    @Test void explicitSyntheticJapaneseVoiceMarkerPassesThroughWithoutBecomingAFileUrl() {
        var activity = dialogueActivity(1L, 7L);
        activity.setActivityState(LearningActivityState.COMPLETED);
        when(items.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member(activity, 1, 201L, "rev-1")));
        when(dialogueLines.findAllById(any())).thenReturn(List.of(
                dialogueLine(201L, "A", "A", "scene", "trai", "こんにちは。", "Xin chào.")));
        var row = ContentAudio.builder().ownerId(201L).audioUrl("tts:ja-JP").build();
        when(audio.findByOwnerTypeAndOwnerIdIn(eq("CONTENT_ITEM"), any())).thenReturn(List.of(row));

        assertEquals("tts:ja-JP", service.reviewState(7L, 1L).lines().getFirst().audioUrl());
    }

    @Test void openDraftN3LineWithoutRecordedAudioUsesTheLabelledSynthesizedVoiceButARecordedClipStillWins() {
        // Owner 2026-10-19 (§2.28) + OD-44: the DRAFT J-LAS N3 textbook has no recorded dialogue audio yet.
        var activity = dialogueActivity(1L, 7L);
        activity.setActivityState(LearningActivityState.COMPLETED);
        var book = Textbook.builder().id(4L).title("Tiếng Nhật N3").jlptLevel("N3").status("DRAFT")
                .isLearningPathSource(true).build();
        activity.setLesson(Lesson.builder().id(61L).status("DRAFT")
                .unit(com.jlas.dictionary.entity.Unit.builder().id(2L).textbook(book).build()).build());
        when(items.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(
                member(activity, 1, 201L, "rev-1"), member(activity, 2, 202L, "rev-1")));
        when(dialogueLines.findAllById(any())).thenReturn(List.of(
                dialogueLine(201L, "A", "A", "scene", "trai", "こんにちは。", "Xin chào."),
                dialogueLine(202L, "B", "B", "scene", "phai", "どうも。", "Cảm ơn.")));
        when(audio.findByOwnerTypeAndOwnerIdIn(eq("CONTENT_ITEM"), any())).thenReturn(List.of(
                ContentAudio.builder().ownerId(202L).audioUrl("/audio/line-202.mp3").build()));

        var lines = service.reviewState(7L, 1L).lines();

        assertEquals("tts:ja-JP", lines.get(0).audioUrl());
        assertEquals("/audio/line-202.mp3", lines.get(1).audioUrl());
        assertEquals("DRAFT", book.getStatus());

        book.setJlptLevel("N4");
        assertNull(service.reviewState(7L, 1L).lines().get(0).audioUrl(),
                "a DRAFT N4 book is not owner-opened, so no voice is fabricated");
    }

    @Test void lockedDialogueCannotBeOpenedThroughDirectStudyEndpoint(){
        var activity=dialogueActivity(1L,7L);
        activity.setActivityState(LearningActivityState.LOCKED);
        when(items.findOwnedByIdForUpdate(1L,7L)).thenReturn(Optional.of(activity));

        ApiException error=assertThrows(ApiException.class,()->service.studyState(7L,1L));

        assertEquals(ErrorCode.PREREQUISITE_NOT_MET,error.getErrorCode());
        verifyNoInteractions(members,dialogueLines,audio,progressRepo);
    }

    @Test void vocabularyActivityIsRejectedAsNotFoundForDialogueService() {
        var activity = activityOf(1L, 7L, LearningActivityType.VOCABULARY);
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));

        ApiException error = assertThrows(ApiException.class, () -> service.studyState(7L, 1L));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test void emptyMembershipIsBlockedContent() {
        var activity = dialogueActivity(1L, 7L);
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of());

        ApiException error = assertThrows(ApiException.class, () -> service.studyState(7L, 1L));
        assertEquals(ErrorCode.BLOCKED_CONTENT, error.getErrorCode());
    }

    @Test void studyStateReturnsRealFieldsOnlyAndDefaultsZeroProgressWhenNoRowExists() {
        var activity = dialogueActivity(1L, 7L);
        var member = member(activity, 1, 201L, "rev-1");
        var line = dialogueLine(201L, "ROLE_A", "Nhân viên", "scene-1", "trai", "いらっしゃいませ。", "Xin chào quý khách.");
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(dialogueLines.findAllById(any())).thenReturn(List.of(line));
        when(audio.findByOwnerTypeAndOwnerIdIn(eq("CONTENT_ITEM"), any())).thenReturn(List.of());
        when(progressRepo.findByActivityId(1L)).thenReturn(Optional.empty());

        var state = service.studyState(7L, 1L);

        assertEquals(180, state.requiredSeconds());
        assertEquals(0, state.accumulatedSeconds());
        assertEquals(180, state.remainingSeconds());
        assertFalse(state.reachedBottom());
        assertFalse(state.completed());
        assertEquals(1, state.lines().size());
        var view = state.lines().get(0);
        assertEquals(201L, view.lineId());
        assertEquals("ROLE_A", view.roleCode());
        assertEquals("Nhân viên", view.roleName());
        assertEquals("いらっしゃいませ。", view.japanese());
        assertEquals("Xin chào quý khách.", view.meaningVi());
        assertNull(view.audioUrl(), "no approved audio row exists for this line, so it must never be fabricated");
    }

    @Test void studyStateAttachesApprovedAudioUrlWhenPresent() {
        var activity = dialogueActivity(1L, 7L);
        var member = member(activity, 1, 201L, "rev-1");
        var line = dialogueLine(201L, "ROLE_A", "Nhân viên", "scene-1", "trai", "いらっしゃいませ。", "Xin chào quý khách.");
        var voice = ContentVoice.builder().code("chuan-nu").name("Chuẩn nữ").build();
        var audioRow = ContentAudio.builder().ownerType("CONTENT_ITEM").ownerId(201L).voice(voice)
                .audioUrl("/audio/line-201.mp3").build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(dialogueLines.findAllById(any())).thenReturn(List.of(line));
        when(audio.findByOwnerTypeAndOwnerIdIn(eq("CONTENT_ITEM"), any())).thenReturn(List.of(audioRow));
        when(progressRepo.findByActivityId(1L)).thenReturn(Optional.empty());

        var state = service.studyState(7L, 1L);

        assertEquals("/audio/line-201.mp3", state.lines().get(0).audioUrl());
    }

    @Test void studyStateOnReloadResumesTheStoredAccumulatedProgressForTheSameRevision() {
        var activity = dialogueActivity(1L, 7L);
        var member = member(activity, 1, 201L, "rev-1");
        var line = dialogueLine(201L, "ROLE_A", "Nhân viên", "scene-1", "trai", "いらっしゃいませ。", "Xin chào quý khách.");
        var stored = LearningDialogueProgress.builder().activity(activity).sourceRevision("rev-1")
                .accumulatedSeconds(96).lastHeartbeatAt(LocalDateTime.now()).lastHeartbeatToken("token-9")
                .reachedBottomAt(LocalDateTime.now()).build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(dialogueLines.findAllById(any())).thenReturn(List.of(line));
        when(audio.findByOwnerTypeAndOwnerIdIn(eq("CONTENT_ITEM"), any())).thenReturn(List.of());
        when(progressRepo.findByActivityId(1L)).thenReturn(Optional.of(stored));

        var state = service.studyState(7L, 1L);

        assertEquals(96, state.accumulatedSeconds(), "reload must resume exactly the server-persisted amount");
        assertEquals(84, state.remainingSeconds());
        assertTrue(state.reachedBottom());
        assertFalse(state.completed());
    }

    @Test void firstHeartbeatEverEstablishesBaselineWithoutCreditingAnyTime() {
        var activity = dialogueActivity(1L, 7L);
        var member = member(activity, 1, 201L, "rev-1");
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(progressRepo.findForUpdate(1L)).thenReturn(Optional.empty());
        when(progressRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.heartbeat(7L, 1L, new DialogueHeartbeatRequest("token-1"));

        assertEquals(0, result.accumulatedSeconds());
        assertFalse(result.completed());
        var captor = ArgumentCaptor.forClass(LearningDialogueProgress.class);
        verify(progressRepo).save(captor.capture());
        assertNotNull(captor.getValue().getLastHeartbeatAt());
        assertEquals("token-1", captor.getValue().getLastHeartbeatToken());
    }

    @Test void heartbeatWithinCapWindowCreditsTheServerMeasuredGap() {
        var activity = dialogueActivity(1L, 7L);
        var member = member(activity, 1, 201L, "rev-1");
        var existing = LearningDialogueProgress.builder().activity(activity).sourceRevision("rev-1")
                .accumulatedSeconds(10).lastHeartbeatAt(LocalDateTime.now().minusSeconds(15))
                .lastHeartbeatToken("token-0").build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(progressRepo.findForUpdate(1L)).thenReturn(Optional.of(existing));
        when(progressRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.heartbeat(7L, 1L, new DialogueHeartbeatRequest("token-1"));

        assertTrue(result.accumulatedSeconds() >= 24 && result.accumulatedSeconds() <= 26,
                "expected ~10+15=25s credited, got " + result.accumulatedSeconds());
    }

    @Test void heartbeatContributionIsCappedAtTwentySecondsEvenWhenServerMeasuredGapIsLarger() {
        var activity = dialogueActivity(1L, 7L);
        var member = member(activity, 1, 201L, "rev-1");
        // 25s gap: within the 30s reset threshold, but must still cap the credited amount at 20s.
        var existing = LearningDialogueProgress.builder().activity(activity).sourceRevision("rev-1")
                .accumulatedSeconds(0).lastHeartbeatAt(LocalDateTime.now().minusSeconds(25))
                .lastHeartbeatToken("token-0").build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(progressRepo.findForUpdate(1L)).thenReturn(Optional.of(existing));
        when(progressRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.heartbeat(7L, 1L, new DialogueHeartbeatRequest("token-1"));

        assertEquals(20, result.accumulatedSeconds());
    }

    @Test void gapOverThirtySecondsResetsTheStreakInsteadOfCreditingTheWholeGap() {
        var activity = dialogueActivity(1L, 7L);
        var member = member(activity, 1, 201L, "rev-1");
        // A huge server-measured gap (e.g. tab was backgrounded/asleep for 10 minutes) must credit nothing.
        var existing = LearningDialogueProgress.builder().activity(activity).sourceRevision("rev-1")
                .accumulatedSeconds(50).lastHeartbeatAt(LocalDateTime.now().minusSeconds(600))
                .lastHeartbeatToken("token-0").build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(progressRepo.findForUpdate(1L)).thenReturn(Optional.of(existing));
        when(progressRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.heartbeat(7L, 1L, new DialogueHeartbeatRequest("token-1"));

        assertEquals(50, result.accumulatedSeconds(), "the 600s gap must not be added; only the pre-existing 50s remains");
    }

    @Test void duplicateHeartbeatTokenIsRejectedWithoutDoubleCrediting() {
        var activity = dialogueActivity(1L, 7L);
        var member = member(activity, 1, 201L, "rev-1");
        var existing = LearningDialogueProgress.builder().activity(activity).sourceRevision("rev-1")
                .accumulatedSeconds(30).lastHeartbeatAt(LocalDateTime.now().minusSeconds(5))
                .lastHeartbeatToken("token-1").build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(progressRepo.findForUpdate(1L)).thenReturn(Optional.of(existing));

        var result = service.heartbeat(7L, 1L, new DialogueHeartbeatRequest("token-1"));

        assertEquals(30, result.accumulatedSeconds());
        verify(progressRepo, never()).save(any());
    }

    @Test void reachedBottomWithoutAnyPriorHeartbeatIsRejectedAsTampering() {
        var activity = dialogueActivity(1L, 7L);
        var member = member(activity, 1, 201L, "rev-1");
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(progressRepo.findForUpdate(1L)).thenReturn(Optional.empty());

        ApiException error = assertThrows(ApiException.class, () -> service.reachedBottom(7L, 1L));
        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        verify(progressRepo, never()).save(any());
    }

    @Test void reachedBottomAfterHeartbeatEvidenceIsAcceptedAndIdempotent() {
        var activity = dialogueActivity(1L, 7L);
        var member = member(activity, 1, 201L, "rev-1");
        var existing = LearningDialogueProgress.builder().activity(activity).sourceRevision("rev-1")
                .accumulatedSeconds(30).lastHeartbeatAt(LocalDateTime.now()).lastHeartbeatToken("token-1").build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(progressRepo.findForUpdate(1L)).thenReturn(Optional.of(existing));
        when(progressRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var first = service.reachedBottom(7L, 1L);
        var firstTimestamp = existing.getReachedBottomAt();
        var second = service.reachedBottom(7L, 1L);

        assertTrue(first.reachedBottom());
        assertTrue(second.reachedBottom());
        assertEquals(firstTimestamp, existing.getReachedBottomAt(), "a repeat call must not move the evidence timestamp");
    }

    @Test void completesOnlyWhenBothTimeAndBottomEvidenceAreSatisfied() {
        var activity = dialogueActivity(1L, 7L);
        var member = member(activity, 1, 201L, "rev-1");
        var existing = LearningDialogueProgress.builder().activity(activity).sourceRevision("rev-1")
                .accumulatedSeconds(179).lastHeartbeatAt(LocalDateTime.now().minusSeconds(1))
                .reachedBottomAt(LocalDateTime.now()).lastHeartbeatToken("token-0").build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(progressRepo.findForUpdate(1L)).thenReturn(Optional.of(existing));
        when(progressRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.heartbeat(7L, 1L, new DialogueHeartbeatRequest("token-1"));

        assertTrue(result.accumulatedSeconds() >= 180);
        assertTrue(result.completed());
        assertEquals(LearningActivityState.COMPLETED, result.activityState());
        assertEquals(LearningActivityState.COMPLETED, activity.getActivityState());
    }

    @Test void enoughTimeWithoutBottomIsNotComplete() {
        var activity = dialogueActivity(1L, 7L);
        var member = member(activity, 1, 201L, "rev-1");
        var existing = LearningDialogueProgress.builder().activity(activity).sourceRevision("rev-1")
                .accumulatedSeconds(200).lastHeartbeatAt(LocalDateTime.now().minusSeconds(1)).build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(progressRepo.findForUpdate(1L)).thenReturn(Optional.of(existing));
        when(progressRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.heartbeat(7L, 1L, new DialogueHeartbeatRequest("token-1"));

        assertFalse(result.completed());
        assertEquals(0, result.remainingSeconds());
    }

    @Test void bottomReachedBeforeThreeMinutesIsNotComplete() {
        var activity = dialogueActivity(1L, 7L);
        var member = member(activity, 1, 201L, "rev-1");
        var existing = LearningDialogueProgress.builder().activity(activity).sourceRevision("rev-1")
                .accumulatedSeconds(20).lastHeartbeatAt(LocalDateTime.now()).lastHeartbeatToken("token-0").build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(progressRepo.findForUpdate(1L)).thenReturn(Optional.of(existing));
        when(progressRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.reachedBottom(7L, 1L);

        assertFalse(result.completed());
        assertEquals(160, result.remainingSeconds());
    }

    @Test void completionTimestampIsNeverOverwrittenByALaterHeartbeat() {
        var activity = dialogueActivity(1L, 7L);
        var member = member(activity, 1, 201L, "rev-1");
        var completedAt = LocalDateTime.now().minusMinutes(5);
        var existing = LearningDialogueProgress.builder().activity(activity).sourceRevision("rev-1")
                .accumulatedSeconds(200).lastHeartbeatAt(LocalDateTime.now().minusSeconds(5))
                .reachedBottomAt(completedAt).completedAt(completedAt).lastHeartbeatToken("token-0").build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(progressRepo.findForUpdate(1L)).thenReturn(Optional.of(existing));
        when(progressRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.heartbeat(7L, 1L, new DialogueHeartbeatRequest("token-1"));

        assertTrue(result.completed());
        assertEquals(completedAt, existing.getCompletedAt());
    }

    @Test void aRevisionBumpResetsStaleProgressInsteadOfCarryingItOver() {
        var activity = dialogueActivity(1L, 7L);
        var member = member(activity, 1, 201L, "rev-2");
        var stale = LearningDialogueProgress.builder().activity(activity).sourceRevision("rev-1")
                .accumulatedSeconds(200).reachedBottomAt(LocalDateTime.now()).completedAt(LocalDateTime.now())
                .lastHeartbeatAt(LocalDateTime.now()).lastHeartbeatToken("token-0").build();
        when(items.findOwnedByIdForUpdate(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(member));
        when(progressRepo.findForUpdate(1L)).thenReturn(Optional.of(stale));
        when(progressRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.heartbeat(7L, 1L, new DialogueHeartbeatRequest("token-1"));

        assertEquals(0, result.accumulatedSeconds(), "a new revision must not silently inherit the old revision's progress");
        assertFalse(result.completed());
    }

    // ---- helpers ----

    private UserLearningPathItem communicationActivity(Long id, Long userId) {
        var activity = dialogueActivity(id, userId);
        activity.getLearningPath().setGoal("TRAVEL");
        return activity;
    }

    /** Backs the practice session/card repositories with plain collections for one activity. */
    private List<com.jlas.learning.entity.LearningStudyCard> inMemoryPracticeStore(UserLearningPathItem activity) {
        var session = new java.util.concurrent.atomic.AtomicReference<com.jlas.learning.entity.LearningStudySession>();
        List<com.jlas.learning.entity.LearningStudyCard> cards = new java.util.ArrayList<>();
        org.mockito.Mockito.lenient().when(studySessions.findByActivityId(activity.getId()))
                .thenAnswer(inv -> Optional.ofNullable(session.get()));
        org.mockito.Mockito.lenient().when(studySessions.save(any())).thenAnswer(inv -> {
            com.jlas.learning.entity.LearningStudySession saved = inv.getArgument(0);
            if (saved.getId() == null) saved.setId(500L);
            session.set(saved);
            return saved;
        });
        org.mockito.Mockito.lenient().when(studyCards.findBySessionIdOrderByOrderIndex(any())).thenAnswer(inv -> List.copyOf(cards));
        org.mockito.Mockito.lenient().when(studyCards.findBySessionIdAndCanonicalId(any(), any())).thenAnswer(inv ->
                cards.stream().filter(card -> card.getCanonicalId().equals(inv.getArgument(1))).findFirst());
        org.mockito.Mockito.lenient().when(studyCards.save(any())).thenAnswer(inv -> {
            com.jlas.learning.entity.LearningStudyCard card = inv.getArgument(0);
            if (!cards.contains(card)) cards.add(card);
            return card;
        });
        return cards;
    }

    private UserLearningPathItem dialogueActivity(Long id, Long userId) {
        return activityOf(id, userId, LearningActivityType.DIALOGUE);
    }

    private UserLearningPathItem activityOf(Long id, Long userId, LearningActivityType type) {
        User user = User.builder().id(userId).build();
        UserLearningPath path = UserLearningPath.builder().id(100L).user(user).build();
        return UserLearningPathItem.builder().id(id).learningPath(path)
                .activityType(type).activityState(LearningActivityState.STUDYING).build();
    }

    private LearningActivityMember member(UserLearningPathItem activity, int order, long canonicalId, String revision) {
        return LearningActivityMember.builder().activity(activity).memberType(LearningMemberType.DIALOGUE_LINE)
                .canonicalId(canonicalId).orderIndex(order).sourceRevision(revision).build();
    }

    private DialogueLineDetail dialogueLine(long contentItemId, String roleCode, String roleNameVi,
            String sceneCode, String bubbleSide, String content, String translatedText) {
        Lesson lesson = Lesson.builder().id(1L).build();
        LessonSection section = LessonSection.builder().id(1L).lesson(lesson).orderIndex(1).build();
        ContentItem item = ContentItem.builder().id(contentItemId).section(section).contentType("LISTENING")
                .title("Line").content(content).translatedText(translatedText).orderIndex(1).build();
        DialogueRole role = DialogueRole.builder().id(1L).lesson(lesson).code(roleCode).nameVi(roleNameVi).build();
        return DialogueLineDetail.builder().contentItemId(contentItemId).contentItem(item).role(role)
                .sceneCode(sceneCode).bubbleSide(bubbleSide).build();
    }
}
