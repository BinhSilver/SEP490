package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.ContentAudio;
import com.jlas.dictionary.entity.DialogueLineDetail;
import com.jlas.dictionary.repository.ContentAudioRepository;
import com.jlas.dictionary.repository.DialogueLineDetailRepository;
import com.jlas.learning.dto.DialogueHeartbeatRequest;
import com.jlas.learning.dto.DialogueProgressResult;
import com.jlas.learning.dto.DialogueStudyState;
import com.jlas.learning.dto.DialogueStudyState.DialogueLineView;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningDialogueProgress;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningDialogueProgressRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.service.InteractiveDialogueService;
import com.jlas.learning.service.impl.engine.InteractiveStudyEngine;
import com.jlas.learning.service.impl.InteractiveActivityStatePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** [JLAS-W0193] Task 7 — Dialogue: read-only canonical script + server-timed, evidence-based completion.
 * Deliberately NOT built on {@link InteractiveStudyEngine} / {@code InteractiveContentAdapter}: Dialogue has
 * no micro-check, no score and no attempt cycle, so the engine's flip/remember-or-practice-gate ->
 * issue/submit/grade/cooldown shape does not fit. This is a standalone, much smaller state machine: a single
 * evidence row per activity, mutated only by two server-computed rules —
 * <ol>
 *   <li>a heartbeat's time contribution is {@code min(serverMeasuredGapSeconds, 20)}, or {@code 0} if the gap
 *   since the last heartbeat exceeds 30s (streak reset) or there was no previous heartbeat (baseline only);
 *   the client never supplies a duration, elapsed time or timestamp — only an idempotency token;</li>
 *   <li>bottom-reached evidence is a dedicated, separately persisted event that requires at least one prior
 *   heartbeat to exist (some engagement evidence) and, once set, is never cleared.</li>
 * </ol>
 * Completion requires both {@code accumulatedSeconds >= REQUIRED_SECONDS} and reached-bottom evidence, and is
 * recorded exactly once. */
@Service
@RequiredArgsConstructor
public class InteractiveDialogueServiceImpl implements InteractiveDialogueService {
    static final int REQUIRED_SECONDS = 180;
    static final int MAX_HEARTBEAT_SECONDS = 20;
    static final int GAP_RESET_SECONDS = 30;

    private final UserLearningPathItemRepository items;
    private final LearningActivityMemberRepository members;
    private final LearningDialogueProgressRepository progressRepo;
    private final DialogueLineDetailRepository dialogueLines;
    private final ContentAudioRepository audio;

    @Override
    @Transactional
    public DialogueStudyState studyState(Long userId, Long activityId) {
        UserLearningPathItem activity = loadActivity(userId, activityId);
        List<LearningActivityMember> membership = loadMembership(activity);
        LearningDialogueProgress progress = progressRepo.findByActivityId(activityId).orElse(null);
        boolean sameRevision = progress != null && progress.getSourceRevision().equals(revisionOf(membership));
        int accumulated = sameRevision ? progress.getAccumulatedSeconds() : 0;
        boolean reachedBottom = sameRevision && progress.getReachedBottomAt() != null;
        boolean completed = sameRevision && progress.getCompletedAt() != null;

        return new DialogueStudyState(activity.getId(), activity.getActivityType(), activity.getActivityState(),
                revisionOf(membership), REQUIRED_SECONDS, accumulated, remaining(accumulated), reachedBottom,
                completed, buildLineViews(membership));
    }

    @Override
    @Transactional(readOnly = true)
    public DialogueStudyState reviewState(Long userId, Long activityId) {
        return replayState(userId, activityId);
    }

    @Override
    @Transactional(readOnly = true)
    public DialogueStudyState practiceState(Long userId, Long activityId) {
        return replayState(userId, activityId);
    }

    private DialogueStudyState replayState(Long userId, Long activityId) {
        UserLearningPathItem activity = items.findByIdAndUserId(activityId, userId)
                .filter(item -> item.getActivityType() == LearningActivityType.DIALOGUE
                        && item.getActivityState() == LearningActivityState.COMPLETED)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        List<LearningActivityMember> membership = loadMembership(activity);
        LearningDialogueProgress state = progressRepo.findByActivityId(activityId).orElse(null);
        boolean matchingRevision = state != null && state.getSourceRevision().equals(revisionOf(membership));
        int accumulated = matchingRevision ? state.getAccumulatedSeconds() : 0;
        boolean reachedBottom = matchingRevision && state.getReachedBottomAt() != null;
        boolean completed = activity.getActivityState() == LearningActivityState.COMPLETED
                || (matchingRevision && state.getCompletedAt() != null);
        return new DialogueStudyState(activityId, LearningActivityType.DIALOGUE, activity.getActivityState(),
                revisionOf(membership), REQUIRED_SECONDS, accumulated, remaining(accumulated), reachedBottom,
                completed, buildLineViews(membership));
    }

    @Override
    @Transactional
    public DialogueProgressResult heartbeat(Long userId, Long activityId, DialogueHeartbeatRequest request) {
        UserLearningPathItem activity = loadActivity(userId, activityId);
        List<LearningActivityMember> membership = loadMembership(activity);
        LearningDialogueProgress state = lockOrCreateProgress(activity, membership);

        if (request.heartbeatToken().equals(state.getLastHeartbeatToken())) {
            // Exact retry/replay of the same heartbeat: return current state, credit nothing twice.
            return toResult(activity, state);
        }

        LocalDateTime now = LocalDateTime.now();
        long contribution;
        if (state.getLastHeartbeatAt() == null) {
            contribution = 0; // no prior anchor to measure a gap from — establish the baseline only.
        } else {
            long gapSeconds = Duration.between(state.getLastHeartbeatAt(), now).getSeconds();
            if (gapSeconds < 0) gapSeconds = 0; // clock-skew guard; never credit negative time.
            contribution = gapSeconds > GAP_RESET_SECONDS ? 0 : Math.min(gapSeconds, MAX_HEARTBEAT_SECONDS);
        }
        state.setAccumulatedSeconds(state.getAccumulatedSeconds() + (int) contribution);
        state.setLastHeartbeatAt(now);
        state.setLastHeartbeatToken(request.heartbeatToken());
        applyCompletionIfEligible(activity, state, now);
        progressRepo.save(state);
        return toResult(activity, state);
    }

    @Override
    @Transactional
    public DialogueProgressResult reachedBottom(Long userId, Long activityId) {
        UserLearningPathItem activity = loadActivity(userId, activityId);
        loadMembership(activity);
        LearningDialogueProgress state = progressRepo.findForUpdate(activityId).orElse(null);
        if (state == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Chưa có bằng chứng hoạt động để ghi nhận đã cuộn đến cuối.");
        }
        LocalDateTime now = LocalDateTime.now();
        if (state.getReachedBottomAt() == null) {
            state.setReachedBottomAt(now);
        }
        applyCompletionIfEligible(activity, state, now);
        progressRepo.save(state);
        return toResult(activity, state);
    }

    // ---- Internal helpers ----

    private UserLearningPathItem loadActivity(Long userId, Long activityId) {
        UserLearningPathItem activity = items.findOwnedByIdForUpdate(activityId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (activity.getActivityType() != LearningActivityType.DIALOGUE) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        InteractiveActivityStatePolicy.requireUnlocked(activity);
        return activity;
    }

    private List<LearningActivityMember> loadMembership(UserLearningPathItem activity) {
        List<LearningActivityMember> list = members.findByActivityIdOrderByOrderIndex(activity.getId()).stream()
                .filter(m -> m.getMemberType() == LearningMemberType.DIALOGUE_LINE)
                .toList();
        if (list.isEmpty()) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT, "Hoạt động chưa có kịch bản hội thoại.");
        }
        return list;
    }

    private String revisionOf(List<LearningActivityMember> membership) {
        return membership.getFirst().getSourceRevision();
    }

    /** Locks (or creates) the activity's single progress row. A membership revision bump (re-composition)
     * resets stale progress from a previous revision rather than silently carrying it over to different
     * dialogue content — same posture as Grammar's practice-progress gate. */
    private LearningDialogueProgress lockOrCreateProgress(UserLearningPathItem activity,
            List<LearningActivityMember> membership) {
        String revision = revisionOf(membership);
        LearningDialogueProgress state = progressRepo.findForUpdate(activity.getId()).orElse(null);
        if (state == null) {
            state = LearningDialogueProgress.builder().activity(activity).sourceRevision(revision).build();
        } else if (!state.getSourceRevision().equals(revision)) {
            state.setSourceRevision(revision);
            state.setAccumulatedSeconds(0);
            state.setLastHeartbeatAt(null);
            state.setLastHeartbeatToken(null);
            state.setReachedBottomAt(null);
            state.setCompletedAt(null);
        }
        return state;
    }

    private void applyCompletionIfEligible(UserLearningPathItem activity, LearningDialogueProgress state,
            LocalDateTime now) {
        boolean satisfiesTime = state.getAccumulatedSeconds() >= REQUIRED_SECONDS;
        boolean satisfiesBottom = state.getReachedBottomAt() != null;
        if (satisfiesTime && satisfiesBottom && state.getCompletedAt() == null) {
            state.setCompletedAt(now);
        }
        if (state.getCompletedAt() != null) {
            activity.setActivityState(LearningActivityState.COMPLETED);
        }
    }

    private DialogueProgressResult toResult(UserLearningPathItem activity, LearningDialogueProgress state) {
        int accumulated = state.getAccumulatedSeconds();
        return new DialogueProgressResult(activity.getId(), activity.getActivityState(), REQUIRED_SECONDS,
                accumulated, remaining(accumulated), state.getReachedBottomAt() != null,
                state.getCompletedAt() != null);
    }

    private int remaining(int accumulatedSeconds) {
        return Math.max(0, REQUIRED_SECONDS - accumulatedSeconds);
    }

    private List<DialogueLineView> buildLineViews(List<LearningActivityMember> membership) {
        List<Long> ids = membership.stream().map(LearningActivityMember::getCanonicalId).distinct().toList();
        Map<Long, DialogueLineDetail> byId = dialogueLines.findAllById(ids).stream()
                .collect(Collectors.toMap(DialogueLineDetail::getContentItemId, d -> d));
        Map<Long, ContentAudio> audioById = audio.findByOwnerTypeAndOwnerIdIn("CONTENT_ITEM", ids).stream()
                .collect(Collectors.toMap(ContentAudio::getOwnerId, a -> a, (a, b) -> a));

        List<DialogueLineView> views = new ArrayList<>();
        for (LearningActivityMember member : membership) {
            DialogueLineDetail detail = byId.get(member.getCanonicalId());
            if (detail == null || detail.getContentItem() == null) {
                throw new ApiException(ErrorCode.BLOCKED_CONTENT, "Nội dung hội thoại không còn đầy đủ. Vui lòng thử lại sau.");
            }
            var item = detail.getContentItem();
            var role = detail.getRole();
            views.add(new DialogueLineView(member.getCanonicalId(), member.getOrderIndex(),
                    role == null ? null : role.getCode(), role == null ? null : role.getNameVi(),
                    detail.getSceneCode(), detail.getBubbleSide(), item.getContent(), item.getTranslatedText(),
                    publicAudioUrl(audioById.get(member.getCanonicalId()))));
        }
        return views;
    }

    private String publicAudioUrl(ContentAudio audio) {
        if (audio == null || audio.getAudioUrl() == null || audio.getAudioUrl().isBlank()) return null;
        String value = audio.getAudioUrl().strip();
        if (value.startsWith("//") || value.contains("\\")) return null;
        try {
            java.net.URI uri = java.net.URI.create(value);
            if (uri.isAbsolute()) {
                return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        && uri.getHost() != null && uri.getUserInfo() == null ? uri.toASCIIString() : null;
            }
            if (uri.getPath() == null || java.util.Arrays.stream(uri.getPath().split("/"))
                    .anyMatch(segment -> segment.equals("..") || segment.contains("%") || segment.contains("\\"))) return null;
            return value.startsWith("/") ? uri.toASCIIString() : "/uploads/content-audio/" + uri.toASCIIString();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
