package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.InteractiveComprehensionEvidence;
import com.jlas.learning.dto.InteractiveDialogueSummary;
import com.jlas.learning.dto.InteractivePathSummaryResponse;
import com.jlas.learning.dto.InteractiveReadinessConfidence;
import com.jlas.learning.dto.InteractiveSkillProgress;
import com.jlas.learning.dto.InteractiveUnresolvedBatch;
import com.jlas.learning.dto.FinalEligibilityResponse;
import com.jlas.learning.dto.MockTestDestination;
import com.jlas.learning.dto.SkillScore;
import com.jlas.learning.dto.WeaknessEvidence;
import com.jlas.learning.entity.LearningMicroCheckState;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningPackageStatus;
import com.jlas.learning.repository.LearningMicroCheckStateRepository;
import com.jlas.learning.repository.LearningPathPackageRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.service.InteractivePathSummaryService;
import com.jlas.learning.service.GoalEntryPolicy;
import com.jlas.learning.service.LearningProfileSource;
import com.jlas.learning.service.MockTestDestinationProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** [JLAS-W0193] Task 10 — aggregates real evidence for the interactive path's end-of-journey summary
 * (design spec §6.1/§6.2). Read-only: this class never mutates path/package/activity state — all state
 * transitions stay owned by {@link InteractiveLearningPathServiceImpl}. */
@Service
@RequiredArgsConstructor
public class InteractivePathSummaryServiceImpl implements InteractivePathSummaryService {

    private static final List<LearningActivityType> SCORED_SKILLS =
            List.of(LearningActivityType.VOCABULARY, LearningActivityType.KANJI, LearningActivityType.GRAMMAR);

    /** [JLAS-W0193] RC Task 6 — the two comprehension skills, aggregated path-wide the exact same way
     * {@link #skillProgress} already aggregates Vocabulary/Kanji/Grammar. This is in-path evidence (from the
     * learner's own Reading/Listening micro-check activities, RC Tasks 1-5), never to be confused with
     * {@link #comprehensionEvidence} below, which stays Smart-Test-only baseline evidence. */
    private static final List<LearningActivityType> COMPREHENSION_SKILLS =
            List.of(LearningActivityType.READING, LearningActivityType.LISTENING);

    private static final Map<LearningActivityType, String> QUIZ_TYPES = Map.of(
            LearningActivityType.VOCABULARY, "VOCAB_MICRO_CHECK",
            LearningActivityType.KANJI, "KANJI_MICRO_CHECK",
            LearningActivityType.GRAMMAR, "GRAMMAR_MICRO_CHECK");

    private static final String COMPREHENSION_NOTE =
            "Đọc/Nghe chưa được đánh giá lại trong lộ trình học tương tác; dữ liệu dưới đây đến từ Smart Test.";

    private final UserLearningPathRepository paths;
    private final UserLearningPathItemRepository items;
    private final LearningPathPackageRepository packages;
    private final LearningMicroCheckStateRepository microCheckStates;
    /** [JLAS-W0193] RC Task 7 — owned entirely by this module (unlike {@link #profileSource}, which reads
     * another team's module), so this is a required, non-optional dependency; the only shipped
     * implementation reports itself unavailable by default. */
    private final MockTestDestinationProvider mockTestDestinationProvider;

    /** [JLAS-W0193] Optional so slice tests don't need to wire Smart Test's read API — same optional-autowire
     * pattern {@code LearningServiceImpl}/{@code InteractiveLearningPathServiceImpl} already use for their own
     * best-effort dependencies. Missing/failing Smart Test data degrades Reading/Listening to "no evidence",
     * never blocks the summary from rendering the rest. */
    @Autowired(required = false)
    private LearningProfileSource profileSource;

    /** Owner 2026-10-26 (lỗi #3): the roadmap's Mock Test rule; optional so slice tests need not wire it. */
    @Autowired(required = false)
    private com.jlas.learning.service.LearningFinalEligibilityService finalEligibility;

    @Override
    @Transactional(readOnly = true)
    public InteractivePathSummaryResponse summary(Long userId) {
        UserLearningPath path = paths
                .findTopByUserIdAndExperienceVersionOrderByIdDesc(userId, LearningExperienceVersion.INTERACTIVE_V2)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Bạn chưa có lộ trình tương tác nào."));

        List<UserLearningPathItem> activities = items.findByLearningPathIdOrderByOrderIndex(path.getId());

        // [JLAS-W0193] RC Task 7 — the server, never the browser, decides availability/destination; only
        // the path's own target level is ever passed to the provider (no internal id, no JWT).
        boolean communicationGoal = GoalEntryPolicy.isCommunicationGoal(path.getGoal());
        // [JLAS-W0193] §2.24 — communication-goal paths have no quiz: Vocabulary/Grammar evidence is the
        // activities' own PASSED/COMPLETED state (no micro-check scores), and there is no Kanji card.
        List<InteractiveSkillProgress> skills = communicationGoal
                ? communicationSkillProgress(activities)
                : SCORED_SKILLS.stream().map(type -> skillProgress(type, activities)).toList();

        InteractiveDialogueSummary dialogue = dialogueSummary(activities);
        List<InteractiveComprehensionEvidence> comprehension = comprehensionEvidence(userId, path);
        // [JLAS-W0193] RC Task 6 — in-path Reading/Listening evidence, aggregated the same way as skills
        // above, kept as a *separate* list from the Smart Test baseline (`comprehension`) so neither ever
        // silently overwrites or averages into the other (RC plan Task 6, rule 3).
        List<InteractiveSkillProgress> comprehensionInPath = communicationGoal ? List.of()
                : COMPREHENSION_SKILLS.stream()
                        .map(type -> skillProgress(type, activities))
                        .toList();
        // [JLAS-W0193] §2.24 — communication paths have neither micro-check nor a Smart Test baseline, so
        // readiness only ever looks at content completion; the standard wording would force LOW forever.
        InteractiveReadinessConfidence readiness = communicationGoal
                ? communicationReadiness(skills, dialogue)
                : readiness(skills, dialogue, comprehension, comprehensionInPath);
        boolean certificateEligible = isCertificateEligible(path);
        MockTestDestination mockTest = communicationGoal
                ? MockTestDestination.notApplicable()
                : mockTestDestinationProvider.resolveDestination(path.getTargetLevel());
        // Owner 2026-10-26 (lỗi #3): the summary opens the Mock Test under the roadmap's rule — every prior stage
        // 100% and the target stage at least 50% — never earlier.
        if (mockTest != null && mockTest.available() && finalEligibility != null) {
            FinalEligibilityResponse eligibility = finalEligibility.forPath(path.getId());
            if (!eligibility.eligible()) {
                mockTest = MockTestDestination.lockedByProgress(path.getTargetLevel(), eligibility.reasonCode(),
                        eligibility.requiredTargetPercent());
            }
        }

        return new InteractivePathSummaryResponse(path.getId(), path.getStatus(), path.getCompletedAt(),
                skills, dialogue, comprehension, comprehensionInPath, readiness, certificateEligible, mockTest,
                studyLinks(activities), communicationGoal);
    }

    /** Finished activities with owned replay contracts, including score-free Dialogue rehearsal. */
    private static final java.util.Set<LearningActivityType> STUDY_LINK_TYPES = java.util.EnumSet.of(
            LearningActivityType.VOCABULARY, LearningActivityType.KANJI, LearningActivityType.FOUNDATION,
            LearningActivityType.GRAMMAR, LearningActivityType.DIALOGUE, LearningActivityType.READING, LearningActivityType.LISTENING);

    private List<com.jlas.learning.dto.InteractiveStudyLink> studyLinks(List<UserLearningPathItem> activities) {
        return activities.stream()
                .filter(a -> a.getActivityType() != null && STUDY_LINK_TYPES.contains(a.getActivityType()))
                .filter(a -> a.getActivityType() == LearningActivityType.DIALOGUE
                        ? a.getActivityState() == LearningActivityState.COMPLETED
                        : a.getActivityState() == LearningActivityState.PASSED
                            || a.getActivityState() == LearningActivityState.COMPLETED)
                .map(a -> new com.jlas.learning.dto.InteractiveStudyLink(a.getId(), a.getActivityType().name(),
                        a.getActivityState().name()))
                .toList();
    }

    // ---- Vocabulary/Kanji/Grammar: first/latest/best + unresolved batches, path-wide ----

    private InteractiveSkillProgress skillProgress(LearningActivityType type, List<UserLearningPathItem> activities) {
        List<Long> activityIds = activities.stream()
                .filter(a -> a.getActivityType() == type)
                .map(UserLearningPathItem::getId)
                .toList();
        List<LearningMicroCheckState> states = activityIds.isEmpty()
                ? List.of() : microCheckStates.findByActivityIdInOrderByActivityIdAscBatchIndexAsc(activityIds);
        List<LearningMicroCheckState> attempted = states.stream()
                .filter(s -> s.getAttemptsUsed() != null && s.getAttemptsUsed() > 0)
                .toList();
        if (attempted.isEmpty()) {
            return new InteractiveSkillProgress(type.name(), false, null, null, null, false, List.of());
        }

        Integer first = attempted.stream()
                .filter(s -> s.getFirstScore() != null)
                .min(Comparator.comparing(LearningMicroCheckState::getCreatedAt))
                .map(LearningMicroCheckState::getFirstScore).orElse(null);
        Integer latest = attempted.stream()
                .filter(s -> s.getLatestScore() != null)
                .max(Comparator.comparing(LearningMicroCheckState::getUpdatedAt))
                .map(LearningMicroCheckState::getLatestScore).orElse(null);
        Integer best = attempted.stream()
                .map(LearningMicroCheckState::getBestScore)
                .filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(null);

        List<InteractiveUnresolvedBatch> unresolved = attempted.stream()
                .filter(s -> !s.isPassed())
                .map(s -> new InteractiveUnresolvedBatch(s.getActivity().getId(), s.getBatchIndex(),
                        s.getLatestScore(), s.isReviewRequired(), s.getCooldownUntil()))
                .toList();

        return new InteractiveSkillProgress(type.name(), true, first, latest, best, unresolved.isEmpty(), unresolved);
    }

    // ---- Vocabulary/Grammar for communication goals: completion evidence, no scores, no Kanji ----

    /** [JLAS-W0193] §2.24 — communication-goal paths (BA/Travel/Daily Life) carry Vocabulary and Grammar
     * content with no quiz: completion is evidenced by the activities' own PASSED/COMPLETED state, so there
     * are no micro-check scores and no Kanji card (Kanji is not part of the communication trio). */
    private List<InteractiveSkillProgress> communicationSkillProgress(List<UserLearningPathItem> activities) {
        return List.of(LearningActivityType.VOCABULARY, LearningActivityType.GRAMMAR).stream()
                .map(type -> {
                    List<UserLearningPathItem> ofType = activities.stream()
                            .filter(a -> a.getActivityType() == type)
                            .toList();
                    if (ofType.isEmpty()) {
                        return new InteractiveSkillProgress(type.name(), false, null, null, null, false, List.of());
                    }
                    boolean resolved = ofType.stream().allMatch(a ->
                            a.getActivityState() == LearningActivityState.PASSED
                                    || a.getActivityState() == LearningActivityState.COMPLETED);
                    List<InteractiveUnresolvedBatch> unresolved = resolved
                            ? List.of()
                            : ofType.stream()
                                    .filter(a -> a.getActivityState() != LearningActivityState.PASSED
                                            && a.getActivityState() != LearningActivityState.COMPLETED)
                                    .map(a -> new InteractiveUnresolvedBatch(a.getId(), a.getOrderIndex(),
                                            null, false, null))
                                    .toList();
                    return new InteractiveSkillProgress(type.name(), true, null, null, null, resolved, unresolved);
                })
                .toList();
    }

    // ---- Dialogue: completion count only, never a score ----

    private InteractiveDialogueSummary dialogueSummary(List<UserLearningPathItem> activities) {
        List<UserLearningPathItem> dialogues = activities.stream()
                .filter(a -> a.getActivityType() == LearningActivityType.DIALOGUE)
                .toList();
        int completed = (int) dialogues.stream()
                .filter(a -> a.getActivityState() == LearningActivityState.COMPLETED).count();
        return new InteractiveDialogueSummary(dialogues.size(), completed,
                !dialogues.isEmpty() && completed == dialogues.size());
    }

    // ---- Reading/Listening: Smart Test snapshot only (never re-tested inside the path this session) ----

    private List<InteractiveComprehensionEvidence> comprehensionEvidence(Long userId, UserLearningPath path) {
        Map<String, SkillScore> byskill = loadSkillScores(userId, path);
        return List.of(
                toComprehensionEvidence(WeaknessEvidence.SKILL_READING, byskill.get(WeaknessEvidence.SKILL_READING)),
                toComprehensionEvidence(WeaknessEvidence.SKILL_LISTENING, byskill.get(WeaknessEvidence.SKILL_LISTENING)));
    }

    private Map<String, SkillScore> loadSkillScores(Long userId, UserLearningPath path) {
        if (profileSource == null || path.getAssessmentAttemptId() == null) return Map.of();
        try {
            var snapshot = profileSource.loadForUser(userId, path.getAssessmentAttemptId());
            if (snapshot == null || snapshot.skillScores() == null) return Map.of();
            Map<String, SkillScore> byskill = new java.util.HashMap<>();
            for (SkillScore score : snapshot.skillScores()) {
                byskill.put(score.skill(), score);
            }
            return byskill;
        } catch (RuntimeException ex) {
            // Best-effort read of another module's data; missing/broken evidence degrades to "no evidence"
            // rather than failing the whole summary — same non-fatal posture Gemini's narrative call uses.
            return Map.of();
        }
    }

    private InteractiveComprehensionEvidence toComprehensionEvidence(String skill, SkillScore score) {
        if (score == null || score.assessedCount() <= 0) {
            return new InteractiveComprehensionEvidence(skill, false, null, null, null, false, COMPREHENSION_NOTE);
        }
        int correctPercent = Math.round(score.correctCount() * 100f / score.assessedCount());
        boolean weak = WeakTagPolicy.comprehensionLessonCount(score) > 0;
        return new InteractiveComprehensionEvidence(skill, true, score.assessedCount(), score.wrongCount(),
                correctPercent, weak, COMPREHENSION_NOTE);
    }

    // ---- Readiness: never lets one skill's high score compensate for another's missing/weak evidence ----

    private InteractiveReadinessConfidence readiness(List<InteractiveSkillProgress> skills,
            InteractiveDialogueSummary dialogue, List<InteractiveComprehensionEvidence> comprehension,
            List<InteractiveSkillProgress> comprehensionInPath) {
        List<String> gaps = new ArrayList<>();
        for (InteractiveSkillProgress skill : skills) {
            if (!skill.hasEvidence()) {
                gaps.add(skill.skill() + ": chưa có bằng chứng trong lộ trình");
            } else if (!skill.resolved()) {
                gaps.add(skill.skill() + ": còn nội dung chưa đạt micro-check");
            }
        }
        for (InteractiveComprehensionEvidence c : comprehension) {
            if (!c.hasEvidence()) {
                gaps.add(c.skill() + ": không có dữ liệu Smart Test");
            }
        }
        // [JLAS-W0193] RC Task 6, rule 4 — an unresolved in-path Reading/Listening batch is named as a gap
        // exactly like an unresolved Vocabulary/Kanji/Grammar batch above; a high score elsewhere (or a
        // strong Smart Test baseline for the SAME skill) can never hide it. A comprehension skill that was
        // simply never attempted in-path is not a gap by itself — the Smart Test baseline above already
        // covers "no evidence" for these two skills, and RC Task 6 must not force LOW merely because the
        // learner hasn't yet done any in-path Reading/Listening (rule 5).
        for (InteractiveSkillProgress c : comprehensionInPath) {
            if (c.hasEvidence() && !c.resolved()) {
                gaps.add(c.skill() + ": còn bài đọc/nghe trong lộ trình chưa đạt micro-check");
            }
        }
        if (!gaps.isEmpty()) {
            return new InteractiveReadinessConfidence("LOW",
                    "Chưa đủ bằng chứng để ước tính độ sẵn sàng đáng tin cậy: " + String.join("; ", gaps) + ".");
        }

        StringBuilder explanation = new StringBuilder(
                "Từ vựng, Kanji và Ngữ pháp đã đạt micro-check trong lộ trình.");
        List<String> weakComprehension = comprehension.stream()
                .filter(InteractiveComprehensionEvidence::weak)
                .map(InteractiveComprehensionEvidence::skill)
                .toList();
        if (!weakComprehension.isEmpty()) {
            explanation.append(" Smart Test cho thấy còn yếu ở ")
                    .append(String.join(", ", weakComprehension)).append(".");
        }
        List<String> inPathComprehensionNames = comprehensionInPath.stream()
                .filter(InteractiveSkillProgress::hasEvidence)
                .map(InteractiveSkillProgress::skill)
                .toList();
        if (!inPathComprehensionNames.isEmpty()) {
            // [JLAS-W0193] RC Task 6, rule 3 — surface that fresher in-path evidence exists without deleting
            // or recomputing the Smart Test baseline sentence above; both stay visible, separately labeled.
            explanation.append(" Đã có bằng chứng làm bài Đọc/Nghe trong lộ trình cho ")
                    .append(String.join(", ", inPathComprehensionNames))
                    .append(" — ưu tiên xem đây là tình trạng hiện tại hơn kết quả Smart Test cũ.");
        }
        if (!dialogue.allCompleted()) {
            explanation.append(" Hội thoại chưa hoàn thành toàn bộ.");
        }
        explanation.append(' ').append(COMPREHENSION_NOTE);
        // Deliberately capped at MEDIUM, never HIGH: even with in-path Reading/Listening evidence now
        // possible (RC Tasks 1-5), it is not required for every learner's path (Warm-up B only composes
        // comprehension activities from a proven Smart Test weakness) and is never re-verified against the
        // full JLPT skill/level range the way Vocabulary/Kanji/Grammar's own micro-check gate is. RC plan
        // Task 6, rule 5: never claim HIGH merely because Reading/Listening activities now exist.
        return new InteractiveReadinessConfidence("MEDIUM", explanation.toString());
    }

    /** [JLAS-W0193] §2.24 — communication-goal paths have no quiz and no Smart Test baseline, so readiness
     * only ever looks at Vocabulary/Grammar content completion and Dialogue. The standard micro-check gap
     * wording above ("chưa đạt micro-check", "không có dữ liệu Smart Test") must never apply to these
     * paths — it would force LOW forever on a goal that is complete by design without a quiz. */
    private InteractiveReadinessConfidence communicationReadiness(List<InteractiveSkillProgress> skills,
            InteractiveDialogueSummary dialogue) {
        List<String> gaps = new ArrayList<>();
        for (InteractiveSkillProgress skill : skills) {
            if (!skill.hasEvidence()) {
                gaps.add(skill.skill() + ": chưa có bằng chứng trong lộ trình");
            } else if (!skill.resolved()) {
                gaps.add(skill.skill() + ": còn nội dung chưa hoàn thành trong lộ trình");
            }
        }
        if (!gaps.isEmpty()) {
            return new InteractiveReadinessConfidence("LOW",
                    "Chưa đủ bằng chứng để ước tính độ sẵn sàng đáng tin cậy: " + String.join("; ", gaps) + ".");
        }

        StringBuilder explanation = new StringBuilder(
                "Từ vựng và Ngữ pháp đã hoàn thành nội dung trong lộ trình giao tiếp.");
        if (!dialogue.allCompleted()) {
            explanation.append(" Hội thoại chưa hoàn thành toàn bộ.");
        }
        explanation.append(" Lộ trình giao tiếp không có bài kiểm tra — mức sẵn sàng phản ánh nội dung đã hoàn thành.");
        return new InteractiveReadinessConfidence("MEDIUM", explanation.toString());
    }

    // ---- Path-completion eligibility; JLPT mock-pass gating is enforced by the certificate controller/UI ----

    private boolean isCertificateEligible(UserLearningPath path) {
        List<LearningPathPackage> pathPackages = packages.findByLearningPathIdOrderByOrderIndex(path.getId());
        if (!"COMPLETED".equals(path.getStatus())) return false;
        boolean hasGoalLearningPackage = false;
        for (LearningPathPackage p : pathPackages) {
            if (!"GOAL_LEARNING".equals(p.getPhase())) continue;
            hasGoalLearningPackage = true;
            if (p.getStatus() != LearningPackageStatus.COMPLETED) return false;
        }
        return hasGoalLearningPackage;
    }
}
