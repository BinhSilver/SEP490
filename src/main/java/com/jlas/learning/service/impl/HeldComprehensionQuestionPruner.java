package com.jlas.learning.service.impl;

import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.repository.AssessmentQuestionRepository;
import com.jlas.learning.dto.ComprehensionExercise;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.repository.*;
import com.jlas.learning.service.ComprehensionExerciseCatalog;
import com.jlas.learning.service.impl.engine.ComprehensionRevision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** [JLAS-W0108] Owner 2026-10-21 (OD-09): a Reading/Listening check switched off at the source (is_active = 0, e.g. a
 * Minna passage whose picture is missing) must not block a path that has not reached it yet.
 *
 * <p>Only activities with no learner evidence (no study session, micro-check state or quiz attempt) are touched: the
 * switched-off members are dropped and the remaining members take the revision of what is left; an activity made only
 * of switched-off checks leaves the path, unless it is the only activity of its chapter. Started work keeps its
 * snapshot. Idempotent: a second run finds nothing to change. */
@Service
@RequiredArgsConstructor
@Slf4j
public class HeldComprehensionQuestionPruner {
    private static final Set<LearningActivityType> TYPES = EnumSet.of(LearningActivityType.READING,
            LearningActivityType.LISTENING);
    private static final Set<LearningActivityState> UNSTARTED = EnumSet.of(LearningActivityState.LOCKED,
            LearningActivityState.STUDYING);

    private final UserLearningPathItemRepository items;
    private final LearningActivityMemberRepository members;
    private final LearningMicroCheckStateRepository checkStates;
    private final LearningQuizSessionRepository quizSessions;
    private final LearningStudySessionRepository studySessions;
    private final AssessmentQuestionRepository questions;
    private final ComprehensionExerciseCatalog catalog;

    public record Report(int removedActivities, int prunedActivities) { }

    @Transactional
    public Report prune(Long userId, UserLearningPath path) {
        if (path == null || path.getId() == null || path.getUser() == null || !Objects.equals(userId, path.getUser().getId())
                || path.getExperienceVersion() != LearningExperienceVersion.INTERACTIVE_V2) {
            return new Report(0, 0);
        }
        List<UserLearningPathItem> pathItems = items.findByPathForUpdate(path.getId());
        Map<Long, List<LearningActivityMember>> membersByActivity = new HashMap<>();
        Set<Long> questionIds = new HashSet<>();
        List<Long> candidateIds = pathItems.stream()
                .filter(activity -> TYPES.contains(activity.getActivityType()) && UNSTARTED.contains(activity.getActivityState()))
                .map(UserLearningPathItem::getId).filter(Objects::nonNull).toList();
        if (candidateIds.isEmpty()) return new Report(0, 0);
        // Runs on every roadmap read: one member query for the whole path, not one per activity.
        Map<Long, List<LearningActivityMember>> loaded = new HashMap<>();
        for (LearningActivityMember row : members.findByActivityIdsInOrder(candidateIds)) {
            loaded.computeIfAbsent(row.getActivity().getId(), ignored -> new ArrayList<>()).add(row);
        }
        for (UserLearningPathItem activity : pathItems) {
            if (!TYPES.contains(activity.getActivityType()) || !UNSTARTED.contains(activity.getActivityState())) continue;
            List<LearningActivityMember> rows = loaded.getOrDefault(activity.getId(), List.of());
            if (rows.isEmpty() || rows.stream().anyMatch(row -> row.getMemberType() != LearningMemberType.COMPREHENSION_QUESTION)) {
                continue;
            }
            membersByActivity.put(activity.getId(), rows);
            rows.forEach(row -> questionIds.add(row.getCanonicalId()));
        }
        if (questionIds.isEmpty()) return new Report(0, 0);
        Map<Long, AssessmentQuestion> rowsById = new HashMap<>();
        questions.findAllById(questionIds).forEach(row -> rowsById.put(row.getId(), row));
        Set<Long> switchedOff = new HashSet<>();
        for (Long id : questionIds) {
            AssessmentQuestion row = rowsById.get(id);
            if (row != null && !Boolean.TRUE.equals(row.getIsActive())) switchedOff.add(id);
        }
        if (switchedOff.isEmpty()) return new Report(0, 0);

        int removed = 0;
        int pruned = 0;
        for (UserLearningPathItem activity : pathItems) {
            List<LearningActivityMember> rows = membersByActivity.get(activity.getId());
            if (rows == null || rows.stream().noneMatch(row -> switchedOff.contains(row.getCanonicalId()))) continue;
            if (hasEvidence(activity.getId())) continue;
            List<LearningActivityMember> gone = rows.stream().filter(row -> switchedOff.contains(row.getCanonicalId())).toList();
            List<LearningActivityMember> kept = rows.stream().filter(row -> !switchedOff.contains(row.getCanonicalId())).toList();
            if (kept.isEmpty()) {
                if (onlyActivityOfItsChapter(activity, pathItems)) {
                    log.warn("Held checks fill the only activity {} of its chapter; left for review", activity.getId());
                    continue;
                }
                // Members first: a managed member still pointing at the deleted activity would fail the flush.
                members.deleteAll(rows);
                members.flush();
                items.delete(activity);
                removed++;
                continue;
            }
            String revision = revisionOf(activity.getActivityType(), kept, rowsById);
            if (revision == null) {
                log.warn("Activity {} keeps checks missing from the catalog; left unchanged", activity.getId());
                continue;
            }
            members.deleteAll(gone);
            kept.forEach(row -> row.setSourceRevision(revision));
            members.saveAll(kept);
            pruned++;
        }
        if (removed + pruned > 0) {
            log.info("Path {}: {} held-only activities removed, {} activities pruned", path.getId(), removed, pruned);
        }
        return new Report(removed, pruned);
    }

    private boolean hasEvidence(Long activityId) {
        return studySessions.findByActivityId(activityId).isPresent()
                || !checkStates.findByActivityIdOrderByBatchIndex(activityId).isEmpty()
                || !quizSessions.findByActivityIdOrderByIdAsc(activityId).isEmpty();
    }

    private static boolean onlyActivityOfItsChapter(UserLearningPathItem activity, List<UserLearningPathItem> pathItems) {
        if (activity.getLearningPackage() == null) return true;
        Long packageId = activity.getLearningPackage().getId();
        return pathItems.stream().noneMatch(other -> !other.getId().equals(activity.getId())
                && other.getLearningPackage() != null && Objects.equals(other.getLearningPackage().getId(), packageId));
    }

    /** The same combined revision the activity was composed with, over the exercises that remain, in member order. */
    private String revisionOf(LearningActivityType type, List<LearningActivityMember> kept,
            Map<Long, AssessmentQuestion> rowsById) {
        Set<String> levels = new HashSet<>();
        for (LearningActivityMember row : kept) {
            AssessmentQuestion question = rowsById.get(row.getCanonicalId());
            if (question == null) return null;
            levels.add(question.getJlptLevel());
        }
        if (levels.size() != 1) return null;
        Map<Long, ComprehensionExercise> exerciseByQuestion = new HashMap<>();
        for (ComprehensionExercise exercise : catalog.resolve(type.name(), levels.iterator().next()).exercises()) {
            exercise.questions().forEach(question -> exerciseByQuestion.put(question.id(), exercise));
        }
        List<ComprehensionExercise> ordered = new ArrayList<>();
        String last = null;
        for (LearningActivityMember row : kept) {
            ComprehensionExercise exercise = exerciseByQuestion.get(row.getCanonicalId());
            if (exercise == null) return null;
            if (!exercise.fingerprint().equals(last)) ordered.add(exercise);
            last = exercise.fingerprint();
        }
        return ComprehensionRevision.combined(type, ordered);
    }
}
