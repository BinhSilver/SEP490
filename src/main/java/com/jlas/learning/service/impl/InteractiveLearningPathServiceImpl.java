package com.jlas.learning.service.impl;

import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.InteractiveLearningActivityResponse;
import com.jlas.learning.dto.InteractiveLearningPackageResponse;
import com.jlas.learning.dto.InteractiveLearningPathResponse;
import com.jlas.learning.dto.ReferenceVocabularyResponse;
import com.jlas.learning.dto.SmartTestReviewTarget;
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
import com.jlas.learning.repository.LessonLearningScopeRepository;
import com.jlas.learning.service.GoalPathPolicy;
import com.jlas.learning.repository.LearningTopicRepository;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.service.InteractiveLearningPathService;
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
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** [JLAS-W0193] Task 2 owns the V2 roadmap/package read+start surface; Task 8 extends it with chapter/package
 * completion: free skill choice within a started package, the composed-activity completion gate, real (never
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
                    LearningActivityType.GRAMMAR, LearningActivityType.DIALOGUE,
                    LearningActivityType.READING, LearningActivityType.LISTENING);
    /** [JLAS-W0193] Task 8 — micro-check quiz types that contribute a real score to package completion.
     * DIALOGUE is intentionally absent: it has no score field anywhere, by design (rules doc §2.14), so it
     * can never enter the numerator or denominator of {@link #computePackageScore}. */
    private static final Map<LearningActivityType, String> SCORED_MICRO_CHECK_TYPES = Map.of(
            LearningActivityType.VOCABULARY, "VOCAB_MICRO_CHECK",
            LearningActivityType.KANJI, "KANJI_MICRO_CHECK",
            LearningActivityType.GRAMMAR, "GRAMMAR_MICRO_CHECK");
    private static final String SUBMITTED = "SUBMITTED";
    /** [JLAS-W0193] Chapter-completion credit reward. A chapter is a source lesson (owner decision
     * 2026-10-06, the same mapping the roadmap/UI uses: a lesson split across the daily budget is one
     * chapter, its ordered day packages are the parts); a lesson-less package is its own chapter. The
     * release directive sets the magnitude to exactly 5 CP per completed chapter — not per package — so a
     * lesson split into N parts still rewards exactly 5 CP once. Idempotency is guaranteed by
     * {@link UserCreditService#earn} de-duping on (userId, reason, referenceType, referenceId), where the
     * reference is the stable source-lesson id (unchanged across replan, which re-schedules packages but
     * never re-keys the source lesson). */
    private static final int CHAPTER_COMPLETION_CREDIT = 5;
    /** Ledger reference for a chapter that has a source lesson; a lesson-less package uses {@code PACKAGE}. */
    private static final String REF_SOURCE_LESSON = "SOURCE_LESSON";
    private static final String REF_PACKAGE = "PACKAGE";

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
    @Autowired(required = false)
    private InteractiveComprehensionPathUpgradeService comprehensionPathUpgrade;
    @Autowired(required = false)
    private HeldComprehensionQuestionPruner heldQuestionPruner;
    @Autowired(required = false)
    private InteractiveCommunicationPathUpgradeService communicationPathUpgrade;
    @Autowired(required = false)
    private com.jlas.learning.service.CommunicationGuideService communicationGuides;
    @Autowired(required = false)
    private LessonLearningScopeRepository learningScopes;
    @Autowired(required = false)
    private LearningTopicRepository learningTopics;
    @Override
    @Transactional
    public InteractiveLearningPathResponse current(Long userId) {
        UserLearningPath path = paths.findByUserIdAndStatusAndExperienceVersion(
                        userId, "ACTIVE", LearningExperienceVersion.INTERACTIVE_V2)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Bạn chưa có lộ trình tương tác đang hoạt động."));
        // A second request may finish the in-progress package while this roadmap is loading.
        // Load its version only after the lock; loading it first and locking it later can
        // make Hibernate reject the newer row against the stale managed entity.
        List<LearningPathPackage> packageEntities = packages.findByPathForUpdate(path.getId());
        if (packageEntities.isEmpty()) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                    "EMPTY_LESSON: Lộ trình chưa có package học hợp lệ.");
        }
        // OD-09: checks switched off at the source leave unstarted activities before the shape upgrade inspects them.
        if (heldQuestionPruner != null) heldQuestionPruner.prune(userId, path);
        if (comprehensionPathUpgrade != null) comprehensionPathUpgrade.reconcile(userId, path);
        if (communicationPathUpgrade != null) communicationPathUpgrade.reconcile(userId, path);
        unlockCurrentChapterSegments(packageEntities);
        Long justCompletedId = refreshInProgressPackage(userId, packageEntities);
        Map<Long, Map<LearningMemberType, Integer>> countsByPackage = memberCounts(packageEntities);
        Map<Long, Long> topicKeys = topicKeys(packageEntities);
        List<InteractiveLearningPackageResponse> packageViews = packageEntities.stream()
                .map(p -> mapPackage(p, orderedActivities(p), packageEntities,
                        p.getId().equals(justCompletedId), countsByPackage, topicKeys))
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
                packageViews.size(), percent(completed, packageViews.size()), currentId, packageViews,
                smartTestReviewOnlyTargets(path), communicationGuide(path, packageEntities));
    }

    /** Owner 2026-10-19: topic grouping for communication roadmaps (display only; never locks a unit). */
    private com.jlas.learning.dto.CommunicationGuide communicationGuide(UserLearningPath path,
            List<LearningPathPackage> packageEntities) {
        if (communicationGuides == null) return null;
        List<Long> lessonIds = packageEntities.stream().map(LearningPathPackage::getSourceLesson)
                .filter(java.util.Objects::nonNull).map(lesson -> lesson.getId()).distinct().toList();
        return communicationGuides.guide(path, lessonIds);
    }

    /** Finished-roadmap replay upgrades old Reading activity shape, but never refreshes, unlocks, or grants rewards. */
    @Override
    @Transactional
    public InteractiveLearningPathResponse completedRoadmap(Long userId) {
        UserLearningPath path = paths.findTopByUserIdAndExperienceVersionOrderByIdDesc(
                userId, LearningExperienceVersion.INTERACTIVE_V2)
                .filter(p -> "COMPLETED".equals(p.getStatus()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        packages.findByPathForUpdate(path.getId());
        if (comprehensionPathUpgrade != null) comprehensionPathUpgrade.reconcile(userId, path);
        if (communicationPathUpgrade != null) communicationPathUpgrade.reconcile(userId, path);
        var entities = packages.findByLearningPathIdOrderByOrderIndex(path.getId());
        var counts = memberCounts(entities);
        var topics = topicKeys(entities);
        var views = entities.stream().map(p -> mapPackage(p, orderedActivities(p), entities, false, counts, topics)).toList();
        int completed = (int) views.stream().filter(p -> p.status() == LearningPackageStatus.COMPLETED).count();
        return new InteractiveLearningPathResponse(path.getId(), path.getTitle(), path.getGoal(),
                path.getCurrentLevel(), path.getTargetLevel(), path.getMinutesPerDay(), completed,
                views.size(), percent(completed, views.size()), null, views, smartTestReviewOnlyTargets(path),
                communicationGuide(path, entities));
    }

    private List<SmartTestReviewTarget> smartTestReviewOnlyTargets(UserLearningPath path) {
        var gaps = LearningMapper.readCoverageGapsSnapshot(path.getCoverageGapsJson());
        return itemReviewTargets(gaps);
    }

    private List<SmartTestReviewTarget> itemReviewTargets(List<com.jlas.learning.dto.CoverageGap> gaps) {
        return gaps.stream()
                .filter(gap -> "SMART_TEST_REVIEW_ONLY_BELOW_THRESHOLD".equals(gap.reasonCode()))
                .map(gap -> {
                    String canonicalId = gap.tagCode();
                    if (canonicalId == null) return null;
                    int separator = canonicalId.indexOf(':');
                    if (separator <= 0 || separator == canonicalId.length() - 1) return null;
                    String skill = canonicalId.substring(0, separator);
                    if (!"VOCABULARY".equals(skill) && !"KANJI".equals(skill)) return null;
                    return new SmartTestReviewTarget(skill, canonicalId.substring(separator + 1));
                })
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted(java.util.Comparator.comparing(SmartTestReviewTarget::skill)
                        .thenComparing(SmartTestReviewTarget::target))
                .toList();
    }

    @Override
    @Transactional
    public InteractiveLearningPackageResponse packageDetail(Long userId, Long packageId) {
        LearningPathPackage learningPackage = packages.findOwnedByIdForUpdate(packageId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        requireInteractive(learningPackage);
        List<UserLearningPathItem> activities = orderedActivities(learningPackage);
        List<LearningPathPackage> siblings = siblingsOf(learningPackage);
        if (learningPackage.getStatus() == LearningPackageStatus.IN_PROGRESS
                || learningPackage.getStatus() == LearningPackageStatus.AVAILABLE) {
            unlockChapterSegments(learningPackage, siblings);
        }
        boolean justCompleted = refresh(userId, learningPackage, activities, siblings);
        return mapPackage(learningPackage, activities, siblings, justCompleted, memberCounts(siblings), topicKeys(siblings));
    }

    @Override
    @Transactional
    public InteractiveLearningPackageResponse start(Long userId, Long packageId) {
        LearningPathPackage learningPackage = packages.findOwnedByIdForUpdate(packageId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        requireInteractive(learningPackage);
        if (learningPackage.getStatus() == LearningPackageStatus.LOCKED) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET,
                    "Hãy hoàn thành các phần bắt buộc của chương trước để mở chương này.");
        }
        List<UserLearningPathItem> activities = orderedActivities(learningPackage);
        if (activities.isEmpty()) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT, "Package chưa có hoạt động học hợp lệ.");
        }
        validateTargetPackage(learningPackage, activities);
        if (learningPackage.getStatus() == LearningPackageStatus.AVAILABLE) {
            learningPackage.setStatus(LearningPackageStatus.IN_PROGRESS);
            learningPackage.setStartedAt(LocalDateTime.now());
        }
        // The package is the ordering boundary. Its skills are independent choices for the learner.
        unlockActivities(learningPackage, activities);
        List<LearningPathPackage> siblings = siblingsOf(learningPackage);
        unlockChapterSegments(learningPackage, siblings);
        return mapPackage(learningPackage, activities, siblings, false, memberCounts(siblings), topicKeys(siblings));
    }

    // ---- Task 8: package completion, unlock, score, reward ----

    /** A learner can work on several segments of the current chapter in any order, so refresh every in-progress
     * segment. Returns the first package completed as part of THIS call for the completion popup. */
    private Long refreshInProgressPackage(Long userId, List<LearningPathPackage> packageEntities) {
        Long justCompletedId = null;
        List<LearningPathPackage> inProgress = packageEntities.stream()
                .filter(p -> p.getStatus() == LearningPackageStatus.IN_PROGRESS)
                .sorted(Comparator.comparing(LearningPathPackage::getOrderIndex)).toList();
        for (LearningPathPackage learningPackage : inProgress) {
            boolean justCompleted = refresh(userId, learningPackage, orderedActivities(learningPackage), packageEntities);
            if (justCompleted && justCompletedId == null) justCompletedId = learningPackage.getId();
        }
        return justCompletedId;
    }

    /** Cascades intra-package activity unlock, then completes the package exactly once (transactionally,
     * guarded by the caller's pessimistic lock on {@code learningPackage} plus the {@code status != COMPLETED}
     * check below) once Vocabulary/Kanji/Grammar are {@code PASSED} and Dialogue is {@code COMPLETED}. Returns
     * {@code true} only when THIS call performed the completion transition (never for a package that was
     * already {@code COMPLETED} on entry), so callers can show the completion popup exactly once. */
    private boolean refresh(Long userId, LearningPathPackage learningPackage, List<UserLearningPathItem> activities,
            List<LearningPathPackage> siblings) {
        unlockActivities(learningPackage, activities);
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
        recordCompletionActivity(learningPackage);
        // Communication goals have no approved CP policy yet; do not inherit the JLPT chapter reward.
        if (chapterComplete(learningPackage, siblings)
                && !com.jlas.learning.service.GoalPathPolicy.isCommunicationGoal(learningPackage.getLearningPath())) {
            grantChapterCredit(userId, learningPackage);
        }
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
        // Owner decision 2026-10-06 (option A): the JLPT Mock Test is a self-assessment the learner is pointed to,
        // not a completion gate, so every goal completes when its GOAL_LEARNING packages are done.
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

    /** Skills within a started package are freely selectable. Also upgrades already-started paths. */
    private void unlockActivities(LearningPathPackage learningPackage, List<UserLearningPathItem> activities) {
        if (learningPackage.getStatus() != LearningPackageStatus.IN_PROGRESS) return;
        for (UserLearningPathItem activity : activities) {
            if (activity.getActivityState() == LearningActivityState.LOCKED) {
                activity.setActivityState(LearningActivityState.STUDYING);
                items.save(activity);
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
     * 3 — and each skill's own score is {@code round(Σcorrect / Σtotal)} pooled across every batch of every
     * part of that skill
     * (design spec §2.2: "không lấy trung bình các phần trăm batch"), not an average of per-batch percentages.
     * Dialogue never contributes to either the numerator or the denominator. An activity with no passing
     * micro-check session (should not happen once {@link #allRequiredActivitiesDone} is true, but checked
     * defensively) is skipped rather than counted as a zero. */
    private Integer computePackageScore(List<UserLearningPathItem> activities) {
        // A pace replan may split one skill into several parts; pool them so the skill still counts once.
        Map<LearningActivityType, long[]> pooled = new EnumMap<>(LearningActivityType.class);
        for (UserLearningPathItem activity : activities) {
            String quizType = SCORED_MICRO_CHECK_TYPES.get(activity.getActivityType());
            if (quizType == null) continue;
            long[] tally = pooled.computeIfAbsent(activity.getActivityType(), type -> new long[2]);
            for (LearningQuizSession session : quizSessions
                    .findByActivityIdAndQuizTypeAndStatusAndPassed(activity.getId(), quizType, SUBMITTED, true)) {
                tally[0] += session.getCorrectCount() == null ? 0 : session.getCorrectCount();
                tally[1] += session.getQuestionCount() == null ? 0 : session.getQuestionCount();
            }
        }
        int sum = 0;
        int count = 0;
        for (long[] tally : pooled.values()) {
            if (tally[1] == 0) continue;
            sum += Math.round(tally[0] * 100f / tally[1]);
            count++;
        }
        return count == 0 ? null : Math.round((float) sum / count);
    }

    /** Streak records the completion *activity* on its own policy, independently of the chapter reward: every
     * completed package (warm-up or GOAL_LEARNING) advances the streak once, and a multi-segment chapter's first
     * segments still count as study activity even though they grant no chapter credit yet. */
    private void recordCompletionActivity(LearningPathPackage learningPackage) {
        if (streakRepository != null) {
            updateStreak(learningPackage.getLearningPath().getUser());
        }
    }

    /** Chapter credit (5 CP) is granted only once the WHOLE chapter is complete — not when its first segment
     * completes (owner decision 2026-10-14: first segment of a multi-segment chapter earns 0 CP; all mandatory
     * segments done earns 5 CP once). A chapter is the source lesson; a lesson-less package is its own
     * single-package chapter. The reference is the chapter (source lesson), not the package, so it stays stable
     * across a pace replan (which re-schedules packages but never changes the source lesson id). Exactly-once
     * rests on the caller's pessimistic lock on {@code learningPackage} plus the {@code status != COMPLETED} guard
     * in {@link #refresh}, with {@link UserCreditService#earn} de-duping by {@code (userId, reason, referenceType,
     * referenceId)} as a second line of defense against replan/retry/concurrency. */
    private void grantChapterCredit(Long userId, LearningPathPackage learningPackage) {
        if (creditService == null) return;
        creditService.earn(userId, CHAPTER_COMPLETION_CREDIT, CreditReason.STUDY_REWARD,
                chapterReferenceType(learningPackage), chapterReferenceId(learningPackage),
                "Hoàn thành chương học tương tác");
    }

    /** True when every sibling package that belongs to this package's chapter is already {@code COMPLETED}. A
     * lesson-less package (no source lesson) is its own chapter, so it is complete the moment it completes. */
    private boolean chapterComplete(LearningPathPackage completed, List<LearningPathPackage> siblings) {
        if (completed.getSourceLesson() == null) return true;
        Long lessonId = completed.getSourceLesson().getId();
        return siblings.stream()
                .filter(pkg -> pkg.getSourceLesson() != null && lessonId.equals(pkg.getSourceLesson().getId()))
                .allMatch(pkg -> pkg.getStatus() == LearningPackageStatus.COMPLETED);
    }

    /** A chapter with a source lesson is referenced by that lesson; a lesson-less package is its own chapter. */
    private static String chapterReferenceType(LearningPathPackage pkg) {
        return pkg.getSourceLesson() == null ? REF_PACKAGE : REF_SOURCE_LESSON;
    }

    private static Long chapterReferenceId(LearningPathPackage pkg) {
        return pkg.getSourceLesson() == null ? pkg.getId() : pkg.getSourceLesson().getId();
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

    /** Unlocks every package segment of the next chapter, and only after the current chapter is complete. */
    private void unlockNextPackage(LearningPathPackage learningPackage, List<LearningPathPackage> siblings) {
        if (!chapterComplete(learningPackage, siblings)) return;
        int chapterEndOrder = siblings.stream()
                .filter(pkg -> sameChapter(learningPackage, pkg))
                .mapToInt(LearningPathPackage::getOrderIndex).max().orElse(learningPackage.getOrderIndex());
        siblings.stream()
                .filter(pkg -> pkg.getOrderIndex() > chapterEndOrder)
                .min(Comparator.comparing(LearningPathPackage::getOrderIndex))
                .ifPresent(nextChapter -> unlockChapterSegments(nextChapter, siblings));
    }

    /** Once a chapter becomes the earliest startable chapter, all its daily-budget segments are selectable. */
    private void unlockCurrentChapterSegments(List<LearningPathPackage> siblings) {
        siblings.stream()
                .filter(pkg -> pkg.getStatus() == LearningPackageStatus.IN_PROGRESS
                        || pkg.getStatus() == LearningPackageStatus.AVAILABLE)
                .min(Comparator.comparing(LearningPathPackage::getOrderIndex))
                .ifPresent(chapter -> unlockChapterSegments(chapter, siblings));
    }

    private void unlockChapterSegments(LearningPathPackage chapter, List<LearningPathPackage> siblings) {
        for (LearningPathPackage segment : siblings) {
            if (sameChapter(chapter, segment) && segment.getStatus() == LearningPackageStatus.LOCKED) {
                segment.setStatus(LearningPackageStatus.AVAILABLE);
                packages.save(segment);
            }
        }
    }

    private boolean sameChapter(LearningPathPackage left, LearningPathPackage right) {
        if (left.getSourceLesson() == null || right.getSourceLesson() == null) {
            return left.getSourceLesson() == null && right.getSourceLesson() == null
                    && left.getId().equals(right.getId());
        }
        return left.getSourceLesson().getId().equals(right.getSourceLesson().getId());
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
        boolean invalidMember = activities.isEmpty();
        for (UserLearningPathItem item : activities) {
            LearningActivityType type = item.getActivityType();
            if (type == null || !TARGET_TYPES.contains(type) || item.getActivityState() == null) {
                invalidMember = true;
            }
        }
        if (invalidMember) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                    "Package chưa có tập hoạt động học hợp lệ.");
        }
    }

    private void requireInteractive(LearningPathPackage learningPackage) {
        if (learningPackage.getLearningPath().getExperienceVersion() != LearningExperienceVersion.INTERACTIVE_V2) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
    }

    private InteractiveLearningPackageResponse mapPackage(LearningPathPackage learningPackage,
            List<UserLearningPathItem> activities, List<LearningPathPackage> siblings, boolean justCompleted,
            Map<Long, Map<LearningMemberType, Integer>> countsByPackage, Map<Long, Long> topicKeys) {
        Map<LearningActivityType, Long> partCounts = activities.stream()
                .filter(item -> item.getActivityType() != null)
                .collect(Collectors.groupingBy(UserLearningPathItem::getActivityType,
                        () -> new EnumMap<>(LearningActivityType.class), Collectors.counting()));
        // "Phần x" follows study order: a repeated split appends part_index values after the part it was cut from.
        Map<LearningActivityType, Integer> seen = new EnumMap<>(LearningActivityType.class);
        List<InteractiveLearningActivityResponse> activityViews = activities.stream()
                .map(item -> new InteractiveLearningActivityResponse(item.getId(), item.getActivityType(),
                        item.getActivityState(), item.getOrderIndex(), item.getReasonCode(), item.getReason(),
                        item.getEstimatedMinutes(), item.getDayNumber(),
                        item.getActivityType() == null ? 1 : seen.merge(item.getActivityType(), 1, Integer::sum),
                        Math.toIntExact(partCounts.getOrDefault(item.getActivityType(), 1L))))
                .toList();
        int completed = (int) activityViews.stream()
                .filter(view -> view.state() != null && DONE.contains(view.state())).count();
        LearningActivityType resume = activityViews.stream()
                .filter(view -> view.state() != null && !DONE.contains(view.state()))
                .map(InteractiveLearningActivityResponse::type).findFirst().orElse(null);
        String lockReason = learningPackage.getStatus() == LearningPackageStatus.LOCKED
                ? "Hoàn thành phần trước để mở khóa." : null;
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
                isLastPackage(learningPackage, siblings), justCompleted, sessions,
                chapterLessonId(learningPackage), chapterTitle(learningPackage), chapterNumber(learningPackage, siblings),
                learningPackage.getSourceSegmentIndex() == null ? 1 : learningPackage.getSourceSegmentIndex(),
                segmentCount(learningPackage, siblings), sourceReviewNote(learningPackage),
                learningPackage.getSourceLesson() == null ? null : topicKeys.get(learningPackage.getSourceLesson().getId()));
    }

    /** Communication paths only: source lesson id -> root topic id (its vocabulary lesson and its situations share it). */
    private Map<Long, Long> topicKeys(List<LearningPathPackage> pathPackages) {
        if (learningScopes == null || learningTopics == null || pathPackages.isEmpty()
                || !GoalPathPolicy.isCommunicationGoal(pathPackages.getFirst().getLearningPath())) return Map.of();
        List<Long> lessonIds = pathPackages.stream().map(LearningPathPackage::getSourceLesson).filter(Objects::nonNull)
                .map(com.jlas.dictionary.entity.Lesson::getId).distinct().toList();
        if (lessonIds.isEmpty()) return Map.of();
        Map<Long, Long> topicByLesson = new HashMap<>();
        learningScopes.findByLessonIdIn(lessonIds).stream().filter(scope -> scope.getTopicId() != null)
                .forEach(scope -> topicByLesson.putIfAbsent(scope.getLessonId(), scope.getTopicId()));
        Map<Long, Long> rootByTopic = new HashMap<>();
        learningTopics.findAllById(topicByLesson.values().stream().distinct().toList())
                .forEach(topic -> rootByTopic.put(topic.getId(), topic.rootTopicId()));
        Map<Long, Long> result = new HashMap<>();
        topicByLesson.forEach((lessonId, topicId) -> result.put(lessonId, rootByTopic.getOrDefault(topicId, topicId)));
        return result;
    }

    static final String UNREVIEWED_SOURCE_NOTE = "Giáo trình chưa duyệt — nội dung đang được rà soát.";

    /** §2.28 / OD-44: the owner opened the DRAFT J-LAS N3 textbook for study without calling it reviewed. */
    private static String sourceReviewNote(LearningPathPackage pkg) {
        var lesson = pkg.getSourceLesson();
        var textbook = lesson == null || lesson.getUnit() == null ? null : lesson.getUnit().getTextbook();
        return textbook != null && com.jlas.common.learning.DraftStudySourcePolicy.isOpenDraftTextbook(
                textbook.getStatus(), textbook.getIsLearningPathSource(), textbook.getJlptLevel())
                ? UNREVIEWED_SOURCE_NOTE : null;
    }

    // ---- Chapter view (owner decision 2026-10-06): a source lesson is a chapter, its day segments are parts ----

    private static Long chapterLessonId(LearningPathPackage pkg) {
        return pkg.getSourceLesson() == null ? null : pkg.getSourceLesson().getId();
    }

    /** Packages of the same source lesson and phase form one chapter; a lesson-less package is its own chapter. */
    private static String chapterKey(LearningPathPackage pkg) {
        Long lessonId = chapterLessonId(pkg);
        return lessonId == null ? "PACKAGE:" + pkg.getId() : pkg.getPhase() + ":" + lessonId;
    }

    /** "Bài 17: ..." — the textbook's own lesson number, which leaks catalog order into the learner's roadmap. */
    private static final java.util.regex.Pattern LESSON_NUMBER_PREFIX =
            java.util.regex.Pattern.compile("^\\s*Bài\\s*\\d+\\s*[:：.\\-–—]\\s*");

    private static String chapterTitle(LearningPathPackage pkg) {
        String lessonTitle = pkg.getSourceLesson() == null ? null : pkg.getSourceLesson().getTitle();
        if (lessonTitle == null || lessonTitle.isBlank()) return pkg.getTitle();
        String withoutNumber = LESSON_NUMBER_PREFIX.matcher(lessonTitle).replaceFirst("");
        return withoutNumber.isBlank() ? lessonTitle : withoutNumber;
    }

    private static int chapterNumber(LearningPathPackage pkg, List<LearningPathPackage> siblings) {
        var keys = new java.util.LinkedHashSet<String>();
        siblings.stream().sorted(Comparator.comparingInt(LearningPathPackage::getOrderIndex))
                .forEach(sibling -> keys.add(chapterKey(sibling)));
        int number = 1;
        for (String key : keys) {
            if (key.equals(chapterKey(pkg))) return number;
            number++;
        }
        return number;
    }

    private static int segmentCount(LearningPathPackage pkg, List<LearningPathPackage> siblings) {
        String key = chapterKey(pkg);
        return (int) Math.max(1, siblings.stream().filter(sibling -> key.equals(chapterKey(sibling))).count());
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
