package com.jlas.learning.service.impl;

import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.InteractiveLearningActivityResponse;
import com.jlas.learning.dto.InteractiveLearningPackageResponse;
import com.jlas.learning.dto.InteractiveLearningPathResponse;
import com.jlas.learning.dto.ReferenceVocabularyResponse;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.LearningQuizSession;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningPackageStatus;
import com.jlas.learning.repository.LearningPathPackageRepository;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningQuizSessionRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.repository.UserLearningStageRepository;
import com.jlas.learning.service.InteractiveLearningPathService;
import com.jlas.learning.service.GoalEntryPolicy;
import com.jlas.learning.service.InteractiveContentCatalog;
import com.jlas.mission.entity.UserStreaks;
import com.jlas.mission.repository.UserStreaksRepository;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import com.jlas.moderation.credit.service.UserCreditService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** [JLAS-W0193] Task 2 owns the V2 roadmap/package read+start surface; Task 8 extends it with package
 * completion: cascading intra-package activity unlock, the composed-activity completion gate, real (never
 * hard-coded) score aggregation from immutable micro-check snapshots, exactly-once reward/streak via the
 * existing credit/streak contract, and next-package unlock. Completion is folded into the existing read
 * paths ({@link #current} / {@link #packageDetail}) rather than a separate endpoint — the same
 * "GET performs an idempotent server-owned state transition" pattern {@code InteractiveStudyEngine} already
 * uses when a study view flips {@code STUDYING -> CHECK_READY} on load. */
@Service
@RequiredArgsConstructor
public class InteractiveLearningPathServiceImpl implements InteractiveLearningPathService {
    private static final EnumSet<LearningActivityState> DONE =
            EnumSet.of(LearningActivityState.PASSED, LearningActivityState.COMPLETED);
    private static final EnumSet<LearningActivityType> TARGET_TYPES =
            EnumSet.of(LearningActivityType.VOCABULARY, LearningActivityType.KANJI,
                    LearningActivityType.GRAMMAR, LearningActivityType.DIALOGUE);
    /** [JLAS-W0193] Task 8 — micro-check quiz types that contribute a real score to package completion.
     * DIALOGUE is intentionally absent: it has no score field anywhere, by design (rules doc §2.14), so it
     * can never enter the numerator or denominator of {@link #computePackageScore}. */
    private static final Map<LearningActivityType, String> SCORED_MICRO_CHECK_TYPES = Map.of(
            LearningActivityType.VOCABULARY, "VOCAB_MICRO_CHECK",
            LearningActivityType.KANJI, "KANJI_MICRO_CHECK",
            LearningActivityType.GRAMMAR, "GRAMMAR_MICRO_CHECK");
    private static final String SUBMITTED = "SUBMITTED";
    /** [JLAS-W0193] Package-completion credit reward. No numeric value is specified by the approved spec;
     * this reuses the existing per-lesson reward magnitude ({@code LearningServiceImpl.STUDY_REWARD_PER_LESSON})
     * as the closest analogous "one unit of study completed" reward. Flagged as a ruling for review. */
    private static final int PACKAGE_COMPLETION_CREDIT = 10;

    private final UserLearningPathRepository paths;
    private final LearningPathPackageRepository packages;
    private final UserLearningPathItemRepository items;
    private final LearningQuizSessionRepository quizSessions;
    private final InteractiveSessionProjector sessionProjector = new InteractiveSessionProjector();

    /** [JLAS-W0193] Optional so slice tests don't need to wire the reward/streak stack — same pattern
     * {@code LearningServiceImpl} already uses for these exact two dependencies. */
    @Autowired(required = false)
    private UserStreaksRepository streakRepository;
    @Autowired(required = false)
    private UserCreditService creditService;
    @Autowired(required = false)
    private UserLearningStageRepository stages;
    @Autowired(required = false)
    private LearningActivityMemberRepository activityMembers;
    @Autowired(required = false)
    private InteractiveContentCatalog contentCatalog;

    @Override
    @Transactional
    public InteractiveLearningPathResponse current(Long userId) {
        UserLearningPath path = paths.findByUserIdAndStatusAndExperienceVersion(
                        userId, "ACTIVE", LearningExperienceVersion.INTERACTIVE_V2)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Bạn chưa có lộ trình tương tác đang hoạt động."));
        List<LearningPathPackage> packageEntities = packages.findByLearningPathIdOrderByOrderIndex(path.getId());
        if (packageEntities.isEmpty()) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                    "EMPTY_LESSON: Lộ trình chưa có package học hợp lệ.");
        }
        Long justCompletedId = refreshInProgressPackage(userId, packageEntities);
        Map<Long, Map<LearningMemberType, Integer>> countsByPackage = memberCounts(packageEntities);
        List<InteractiveLearningPackageResponse> packageViews = packageEntities.stream()
                .map(p -> mapPackage(p, orderedActivities(p), packageEntities,
                        p.getId().equals(justCompletedId), countsByPackage))
                .toList();
        int completed = (int) packageViews.stream()
                .filter(view -> view.status() == LearningPackageStatus.COMPLETED).count();
        Long currentId = packageViews.stream()
                .filter(view -> view.status() == LearningPackageStatus.IN_PROGRESS)
                .map(InteractiveLearningPackageResponse::id).findFirst()
                .orElseGet(() -> packageViews.stream()
                        .filter(view -> view.status() == LearningPackageStatus.AVAILABLE)
                        .map(InteractiveLearningPackageResponse::id).findFirst().orElse(null));
        return new InteractiveLearningPathResponse(path.getId(), path.getTitle(), path.getGoal(),
                path.getCurrentLevel(), path.getTargetLevel(), path.getMinutesPerDay(), completed,
                packageViews.size(), percent(completed, packageViews.size()), currentId, packageViews);
    }

    @Override
    @Transactional
    public InteractiveLearningPackageResponse packageDetail(Long userId, Long packageId) {
        LearningPathPackage learningPackage = packages.findOwnedByIdForUpdate(packageId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        requireInteractive(learningPackage);
        List<UserLearningPathItem> activities = orderedActivities(learningPackage);
        List<LearningPathPackage> siblings = siblingsOf(learningPackage);
        boolean justCompleted = refresh(userId, learningPackage, activities, siblings);
        return mapPackage(learningPackage, activities, siblings, justCompleted, memberCounts(siblings));
    }

    @Override
    @Transactional
    public InteractiveLearningPackageResponse start(Long userId, Long packageId) {
        LearningPathPackage learningPackage = packages.findOwnedByIdForUpdate(packageId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        requireInteractive(learningPackage);
        if (learningPackage.getStatus() == LearningPackageStatus.LOCKED) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET,
                    "Hãy hoàn thành package trước để mở ngày học này.");
        }
        List<UserLearningPathItem> activities = orderedActivities(learningPackage);
        if (activities.isEmpty()) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT, "Package chưa có hoạt động học hợp lệ.");
        }
        validateTargetPackage(learningPackage, activities);
        if (learningPackage.getStatus() == LearningPackageStatus.AVAILABLE) {
            learningPackage.setStatus(LearningPackageStatus.IN_PROGRESS);
            learningPackage.setStartedAt(LocalDateTime.now());
            UserLearningPathItem first = activities.getFirst();
            if (first.getActivityState() == LearningActivityState.LOCKED) {
                first.setActivityState(LearningActivityState.STUDYING);
            }
        }
        List<LearningPathPackage> siblings = siblingsOf(learningPackage);
        return mapPackage(learningPackage, activities, siblings, false, memberCounts(siblings));
    }

    // ---- Task 8: package completion, unlock, score, reward ----

    /** At most one package per path is ever {@code IN_PROGRESS} at a time (the next one only reaches
     * {@code AVAILABLE} on unlock; the learner must call {@link #start} to advance it further), so refreshing
     * that single package on every roadmap read is enough to reflect fresh completion without re-locking the
     * whole list. Returns the id of the package that completed as part of THIS call (or {@code null}), so the
     * frontend can distinguish "just finished — show the popup" from "already completed a while ago" without
     * needing a separate acknowledgement table. */
    private Long refreshInProgressPackage(Long userId, List<LearningPathPackage> packageEntities) {
        LearningPathPackage inProgress = packageEntities.stream()
                .filter(p -> p.getStatus() == LearningPackageStatus.IN_PROGRESS)
                .findFirst().orElse(null);
        if (inProgress == null) return null;
        LearningPathPackage locked = packages.findOwnedByIdForUpdate(inProgress.getId(), userId).orElse(null);
        if (locked == null) return null;
        boolean justCompleted = refresh(userId, locked, orderedActivities(locked), packageEntities);
        return justCompleted ? locked.getId() : null;
    }

    /** Cascades intra-package activity unlock, then completes the package exactly once (transactionally,
     * guarded by the caller's pessimistic lock on {@code learningPackage} plus the {@code status != COMPLETED}
     * check below) once Vocabulary/Kanji/Grammar are {@code PASSED} and Dialogue is {@code COMPLETED}. Returns
     * {@code true} only when THIS call performed the completion transition (never for a package that was
     * already {@code COMPLETED} on entry), so callers can show the completion popup exactly once. */
    private boolean refresh(Long userId, LearningPathPackage learningPackage, List<UserLearningPathItem> activities,
            List<LearningPathPackage> siblings) {
        cascadeUnlock(activities);
        if (learningPackage.getStatus() == LearningPackageStatus.COMPLETED) return false;
        String phase = learningPackage.getPhase();
        boolean requiredDone;
        if ("GOAL_LEARNING".equals(phase)) {
            requiredDone = allRequiredActivitiesDone(activities);
        } else if ("WARM_UP".equals(phase)) {
            // [JLAS-W0193] Task 9 — a Warm-up B package's activity set is dynamic (only the skills that
            // actually had weak evidence: any of Vocabulary/Kanji/Grammar, never Dialogue), unlike a
            // GOAL_LEARNING package's fixed four. It completes once EVERY activity it actually has reaches
            // its terminal state; an empty activity list is defensively never considered complete.
            requiredDone = allComposedActivitiesDone(activities);
        } else {
            requiredDone = false;
        }
        if (!requiredDone) return false;
        Integer score = computePackageScore(activities);
        learningPackage.setStatus(LearningPackageStatus.COMPLETED);
        learningPackage.setCompletedAt(LocalDateTime.now());
        learningPackage.setScorePercent(score);
        packages.save(learningPackage);
        grantCompletionReward(userId, learningPackage);
        advanceStageIfComplete(learningPackage, siblings);
        unlockNextPackage(learningPackage, siblings);
        maybeCompletePath(learningPackage, siblings);
        return true;
    }

    private void advanceStageIfComplete(LearningPathPackage completed,
            List<LearningPathPackage> siblings) {
        if (stages == null || completed.getStageId() == null) return;
        boolean stageComplete = siblings.stream()
                .filter(pkg -> completed.getStageId().equals(pkg.getStageId()))
                .allMatch(pkg -> pkg.getStatus() == LearningPackageStatus.COMPLETED);
        if (!stageComplete) return;
        stages.findById(completed.getStageId()).ifPresent(stage -> {
            if (!"COMPLETED".equals(stage.getStatus())) {
                stage.setStatus("COMPLETED");
                stages.save(stage);
            }
            stages.findByLearningPathIdAndStageIndex(
                    completed.getLearningPath().getId(), stage.getStageIndex() + 1).ifPresent(next -> {
                if ("PENDING".equals(next.getStatus())) {
                    next.setStatus("ACTIVE");
                    stages.save(next);
                }
            });
        });
    }

    /** [JLAS-W0193] Task 10 — once every {@code GOAL_LEARNING} package in the path is {@code COMPLETED}, the
     * path itself transitions {@code ACTIVE -> COMPLETED} (mirroring the legacy V1 precedent,
     * {@code LearningServiceImpl.finishPath}), which V2 never did before this task — a genuine hardening fix,
     * not just a Task 10 feature: without it, {@link #current} keeps matching the path forever (it filters
     * {@code status = ACTIVE}) even after the learner has nothing left to do, and there is no unambiguous
     * path-level signal for certificate/summary eligibility to read. Warm-up packages are prerequisites, not
     * counted requirements (design spec §6.3 "chỉ cấp khi mọi package bắt buộc..."), so only
     * {@code GOAL_LEARNING} packages are checked; a path with zero target packages (should not happen once
     * the composer has run, but checked defensively) is never marked complete. Idempotent: only flips the
     * path when it is still {@code ACTIVE}. */
    private void maybeCompletePath(LearningPathPackage justCompleted, List<LearningPathPackage> siblings) {
        UserLearningPath path = justCompleted.getLearningPath();
        if (!"ACTIVE".equals(path.getStatus())) return;
        // JLPT completion is exclusively owned by LearningPathCompletionService after a qualifying Mock Test.
        // Package completion only makes the final available; it must never mint a JLPT completion/certificate.
        if (!GoalEntryPolicy.isCommunicationGoal(path.getGoal())) return;
        boolean hasGoalLearningPackage = false;
        for (LearningPathPackage sibling : siblings) {
            if (!"GOAL_LEARNING".equals(sibling.getPhase())) continue;
            hasGoalLearningPackage = true;
            if (sibling.getStatus() != LearningPackageStatus.COMPLETED) return;
        }
        if (!hasGoalLearningPackage) return;
        path.setStatus("COMPLETED");
        path.setCompletedAt(LocalDateTime.now());
        paths.save(path);
    }

    /** Walks the ordered activities and unlocks each one's successor once it reaches its terminal state —
     * {@code PASSED} for Vocabulary/Kanji/Grammar, {@code COMPLETED} for Dialogue — cascading through any
     * number of already-finished activities in one call so a learner who studies several activities before
     * reloading still sees every eligible one unlocked. */
    private void cascadeUnlock(List<UserLearningPathItem> activities) {
        for (int i = 0; i + 1 < activities.size(); i++) {
            UserLearningPathItem current = activities.get(i);
            UserLearningPathItem next = activities.get(i + 1);
            if (isActivityDone(current) && next.getActivityState() == LearningActivityState.LOCKED) {
                next.setActivityState(LearningActivityState.STUDYING);
                items.save(next);
            }
        }
    }

    private boolean isActivityDone(UserLearningPathItem activity) {
        LearningActivityState required = activity.getActivityType() == LearningActivityType.DIALOGUE
                ? LearningActivityState.COMPLETED : LearningActivityState.PASSED;
        return activity.getActivityState() == required;
    }

    /** A non-empty composed package completes only when every activity it actually contains reaches its own
     * terminal state. This is shared by optional target activities and dynamic Warm-up packages. */
    private boolean allComposedActivitiesDone(List<UserLearningPathItem> activities) {
        if (activities.isEmpty()) return false;
        for (UserLearningPathItem activity : activities) {
            if (activity.getActivityType() == null || !isActivityDone(activity)) return false;
        }
        return true;
    }

    private boolean allRequiredActivitiesDone(List<UserLearningPathItem> activities) {
        return allComposedActivitiesDone(activities);
    }

    /** {@code targetScore = round((vocabularyScore + kanjiScore + grammarScore) / N)} where N is the number
     * of those three skills that actually have a valid score in THIS package — never a hard-coded division by
     * 3 — and each skill's own score is {@code round(Σcorrect / Σtotal)} pooled across that activity's batches
     * (design spec §2.2: "không lấy trung bình các phần trăm batch"), not an average of per-batch percentages.
     * Dialogue never contributes to either the numerator or the denominator. An activity with no passing
     * micro-check session (should not happen once {@link #allRequiredActivitiesDone} is true, but checked
     * defensively) is skipped rather than counted as a zero. */
    private Integer computePackageScore(List<UserLearningPathItem> activities) {
        int sum = 0;
        int count = 0;
        for (UserLearningPathItem activity : activities) {
            String quizType = SCORED_MICRO_CHECK_TYPES.get(activity.getActivityType());
            if (quizType == null) continue;
            Integer score = skillScore(activity.getId(), quizType);
            if (score != null) {
                sum += score;
                count++;
            }
        }
        return count == 0 ? null : Math.round((float) sum / count);
    }

    private Integer skillScore(Long activityId, String quizType) {
        List<LearningQuizSession> passing =
                quizSessions.findByActivityIdAndQuizTypeAndStatusAndPassed(activityId, quizType, SUBMITTED, true);
        if (passing.isEmpty()) return null;
        long correct = 0;
        long total = 0;
        for (LearningQuizSession session : passing) {
            correct += session.getCorrectCount() == null ? 0 : session.getCorrectCount();
            total += session.getQuestionCount() == null ? 0 : session.getQuestionCount();
        }
        if (total == 0) return null;
        return Math.round(correct * 100f / total);
    }

    /** Reward/streak via the project's existing contract (moderation credit ledger + mission streak), never a
     * parallel mechanism. Exactly-once is primarily guaranteed by the caller holding a pessimistic lock on
     * {@code learningPackage} and only reaching this method once per row via the {@code status != COMPLETED}
     * guard in {@link #refresh}; {@link UserCreditService#earn} additionally de-dupes by
     * {@code (userId, reason, referenceType=PACKAGE, referenceId=packageId)} as a second line of defense. */
    private void grantCompletionReward(Long userId, LearningPathPackage learningPackage) {
        if (streakRepository != null) {
            updateStreak(learningPackage.getLearningPath().getUser());
        }
        if (creditService != null) {
            creditService.earn(userId, PACKAGE_COMPLETION_CREDIT, CreditReason.STUDY_REWARD,
                    "PACKAGE", learningPackage.getId(), "Hoàn thành package học tương tác");
        }
    }

    /** Reuses {@link LearningServiceImpl#advanceStreak} (package-private, same package) instead of
     * duplicating the streak-advance formula: same-day completion is naturally idempotent (no change), a
     * broken streak resets to 1, a consecutive day extends it. */
    private void updateStreak(User user) {
        LocalDate today = LocalDate.now();
        UserStreaks streak = streakRepository.findByUserId(user.getId())
                .orElseGet(() -> UserStreaks.builder().user(user).build());
        var next = LearningServiceImpl.advanceStreak(streak.getCurrentStreak(), streak.getLastCompletedDate(), today);
        if (!next.changed()) return;
        streak.setCurrentStreak(next.current());
        streak.setLongestStreak(next.longest());
        streak.setLastCompletedDate(today);
        streakRepository.save(streak);
    }

    /** Unlocks only the immediate next package by order index — same one-step-at-a-time posture as activity
     * unlock — so "học vượt" opens exactly the next package in path order, never further ahead, and never
     * changes any package's scheduled {@code dayNumber}. */
    private void unlockNextPackage(LearningPathPackage learningPackage, List<LearningPathPackage> siblings) {
        siblings.stream()
                .filter(p -> p.getOrderIndex() > learningPackage.getOrderIndex())
                .min(Comparator.comparing(LearningPathPackage::getOrderIndex))
                .ifPresent(next -> {
                    if (next.getStatus() == LearningPackageStatus.LOCKED) {
                        next.setStatus(LearningPackageStatus.AVAILABLE);
                        packages.save(next);
                    }
                });
    }

    /** Only a package the server actually considers startable now (already unlocked) is offered as "học
     * vượt" — a next package that still exists but is not yet {@code AVAILABLE}/{@code IN_PROGRESS} (e.g. its
     * own prerequisite chain has not resolved) yields {@code null} here rather than a dead link. */
    private Long nextAvailablePackageId(LearningPathPackage learningPackage, List<LearningPathPackage> siblings) {
        return siblings.stream()
                .filter(p -> p.getOrderIndex() > learningPackage.getOrderIndex())
                .filter(p -> p.getStatus() == LearningPackageStatus.AVAILABLE
                        || p.getStatus() == LearningPackageStatus.IN_PROGRESS)
                .min(Comparator.comparing(LearningPathPackage::getOrderIndex))
                .map(LearningPathPackage::getId)
                .orElse(null);
    }

    /** Based purely on path order (does another package exist after this one), independent of that package's
     * unlock status, so the popup routes to the summary exactly when there is truly nothing left in the path. */
    private boolean isLastPackage(LearningPathPackage learningPackage, List<LearningPathPackage> siblings) {
        int maxOrder = siblings.stream().mapToInt(LearningPathPackage::getOrderIndex)
                .max().orElse(learningPackage.getOrderIndex());
        return learningPackage.getOrderIndex() >= maxOrder;
    }

    private List<LearningPathPackage> siblingsOf(LearningPathPackage learningPackage) {
        return packages.findByLearningPathIdOrderByOrderIndex(learningPackage.getLearningPath().getId());
    }

    // ---- Shared mapping/validation (Task 2) ----

    private List<UserLearningPathItem> orderedActivities(LearningPathPackage learningPackage) {
        List<UserLearningPathItem> activities = items
                .findByLearningPackageIdOrderByOrderIndex(learningPackage.getId());
        validateTargetPackage(learningPackage, activities);
        return activities;
    }

    private void validateTargetPackage(LearningPathPackage learningPackage, List<UserLearningPathItem> activities) {
        if (!"GOAL_LEARNING".equals(learningPackage.getPhase())) return;
        if (learningPackage.getSourceLesson() == null || learningPackage.getContentRevision() == null
                || learningPackage.getContentRevision().isBlank()) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                    "Package chưa có snapshot nguồn canonical hợp lệ.");
        }
        EnumSet<LearningActivityType> actual = EnumSet.noneOf(LearningActivityType.class);
        boolean invalidMember = activities.isEmpty();
        for (UserLearningPathItem item : activities) {
            LearningActivityType type = item.getActivityType();
            if (type == null || !TARGET_TYPES.contains(type) || item.getActivityState() == null
                    || !actual.add(type)) {
                invalidMember = true;
            }
        }
        if (!actual.contains(LearningActivityType.KANJI) && !actual.contains(LearningActivityType.GRAMMAR)) {
            invalidMember = true;
        }
        if (invalidMember) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                    "Package chưa có tập hoạt động Từ vựng/Kanji/Ngữ pháp/Hội thoại hợp lệ.");
        }
    }

    private void requireInteractive(LearningPathPackage learningPackage) {
        if (learningPackage.getLearningPath().getExperienceVersion() != LearningExperienceVersion.INTERACTIVE_V2) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
    }

    private InteractiveLearningPackageResponse mapPackage(LearningPathPackage learningPackage,
            List<UserLearningPathItem> activities, List<LearningPathPackage> siblings, boolean justCompleted,
            Map<Long, Map<LearningMemberType, Integer>> countsByPackage) {
        List<InteractiveLearningActivityResponse> activityViews = activities.stream()
                .map(item -> new InteractiveLearningActivityResponse(item.getId(), item.getActivityType(),
                        item.getActivityState(), item.getOrderIndex(), item.getReasonCode(), item.getReason()))
                .toList();
        int completed = (int) activityViews.stream()
                .filter(view -> view.state() != null && DONE.contains(view.state())).count();
        LearningActivityType resume = activityViews.stream()
                .filter(view -> view.state() != null && !DONE.contains(view.state()))
                .map(InteractiveLearningActivityResponse::type).findFirst().orElse(null);
        String lockReason = learningPackage.getStatus() == LearningPackageStatus.LOCKED
                ? "Hoàn thành ngày học trước để mở khóa." : null;
        List<ReferenceVocabularyResponse> references = List.of();
        if ("GOAL_LEARNING".equals(learningPackage.getPhase())
                && activityViews.stream().noneMatch(view -> view.type() == LearningActivityType.VOCABULARY)
                && contentCatalog != null && learningPackage.getSourceLesson() != null) {
            references = contentCatalog.referenceVocabularyForLesson(learningPackage.getSourceLesson().getId());
        }
        List<com.jlas.learning.dto.InteractiveLearningSessionResponse> sessions = "GOAL_LEARNING".equals(learningPackage.getPhase())
                ? sessionProjector.project(learningPackage.getStatus(), lockReason, activityViews,
                        countsByPackage.getOrDefault(learningPackage.getId(), Map.of()), references,
                        learningPackage.getEstimatedMinutes())
                : List.of();
        return new InteractiveLearningPackageResponse(learningPackage.getId(), learningPackage.getPhase(),
                learningPackage.getDayNumber(), learningPackage.getOrderIndex(), learningPackage.getTitle(),
                learningPackage.getStatus(), learningPackage.getEstimatedMinutes(), completed,
                activityViews.size(), percent(completed, activityViews.size()), resume, lockReason,
                learningPackage.getStartedAt(), learningPackage.getCompletedAt(), activityViews,
                learningPackage.getScorePercent(), nextAvailablePackageId(learningPackage, siblings),
                isLastPackage(learningPackage, siblings), justCompleted, sessions);
    }

    private Map<Long, Map<LearningMemberType, Integer>> memberCounts(List<LearningPathPackage> packages) {
        if (packages == null || packages.isEmpty() || activityMembers == null) return Map.of();
        List<Long> packageIds = packages.stream().map(LearningPathPackage::getId).filter(java.util.Objects::nonNull).toList();
        if (packageIds.isEmpty()) return Map.of();
        Map<Long, Map<LearningMemberType, Integer>> result = new HashMap<>();
        for (LearningActivityMemberRepository.PackageMemberCount row : activityMembers.findPackageMemberCounts(packageIds)) {
            result.computeIfAbsent(row.getPackageId(), ignored -> new EnumMap<>(LearningMemberType.class))
                    .put(row.getMemberType(), Math.toIntExact(Math.min(Integer.MAX_VALUE, row.getMemberCount())));
        }
        return result;
    }

    private int percent(int completed, int total) {
        return total == 0 ? 0 : Math.round(completed * 100f / total);
    }
}
