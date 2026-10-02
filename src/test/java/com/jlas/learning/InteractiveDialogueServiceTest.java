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

    InteractiveDialogueServiceImpl service;

    @BeforeEach void setUp() {
        service = new InteractiveDialogueServiceImpl(items, members, progressRepo, dialogueLines, audio);
    }

    @Test void studyStateIsOwnerScoped() {
        when(items.findOwnedByIdForUpdate(99L, 7L)).thenReturn(Optional.empty());
        ApiException error = assertThrows(ApiException.class, () -> service.studyState(7L, 99L));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
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
