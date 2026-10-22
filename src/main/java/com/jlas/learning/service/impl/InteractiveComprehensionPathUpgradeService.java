package com.jlas.learning.service.impl;

import com.jlas.learning.dto.ComprehensionExercise;
import com.jlas.learning.service.ComprehensionExerciseCatalog.CatalogResult;
import com.jlas.learning.entity.*;
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

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** Idempotently upgrades older multi-passage Reading activities while retaining their learner evidence. */
@Service
@RequiredArgsConstructor
@Slf4j
public class InteractiveComprehensionPathUpgradeService {
    private static final String READING_MICRO_CHECK = "READING_MICRO_CHECK";
    private static final String PRACTICE_REPLAY = "PRACTICE_REPLAY";

    private final UserLearningPathItemRepository items;
    private final LearningActivityMemberRepository members;
    private final LearningMicroCheckStateRepository checkStates;
    private final LearningQuizSessionRepository quizSessions;
    private final LearningStudySessionRepository studySessions;
    private final LearningStudyCardRepository studyCards;
    private final ComprehensionExerciseCatalog catalog;

    @Transactional
    public ReconciliationReport reconcile(Long userId, UserLearningPath path) {
        if (path == null || path.getId() == null || userId == null || path.getUser() == null
                || !userId.equals(path.getUser().getId())
                || path.getExperienceVersion() != LearningExperienceVersion.INTERACTIVE_V2) {
            return new ReconciliationReport(0, List.of());
        }

        List<UserLearningPathItem> orderedPathItems = items.findByPathForUpdate(path.getId()).stream()
                .sorted(Comparator.comparing(UserLearningPathItem::getOrderIndex,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (orderedPathItems.isEmpty()) return new ReconciliationReport(0, List.of());

        var diagnostics = new ArrayList<Diagnostic>();
        var plans = new LinkedHashMap<Long, SplitPlan>();
        Map<String, CatalogResult> readingCatalogByLevel = new HashMap<>();
        for (UserLearningPathItem activity : orderedPathItems) {
            if (activity.getActivityType() != LearningActivityType.READING || activity.getId() == null) continue;
            String level = sourceLevel(activity);
            if (level == null) {
                diagnostics.add(diagnostic(activity, "SOURCE_LEVEL_MISSING"));
                continue;
            }
            CatalogResult readingCatalog = readingCatalogByLevel.computeIfAbsent(level,
                    value -> catalog.resolve("READING", value));
            var plan = inspect(activity, orderedPathItems, readingCatalog, diagnostics);
            if (plan != null) plans.put(activity.getId(), plan);
        }
        if (plans.isEmpty()) return report(path.getId(), 0, diagnostics);

        // Park every row above both the current and the final dense order before renumbering, so a split that inserts
        // rows mid-path never collides in (path_id, order_index). order_index is INT UNSIGNED in MySQL: no negatives.
        int finalCount = orderedPathItems.size() + plans.values().stream().mapToInt(plan -> plan.groups().size() - 1).sum();
        int currentMax = orderedPathItems.stream().map(UserLearningPathItem::getOrderIndex).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).max().orElse(0);
        int parkingBase = Math.max(currentMax, finalCount);
        for (int i = 0; i < orderedPathItems.size(); i++) {
            orderedPathItems.get(i).setOrderIndex(parkingBase + i + 1);
        }
        items.saveAll(orderedPathItems);
        items.flush();

        Map<Long, Integer> nextPartIndexByPackage = new HashMap<>();
        for (UserLearningPathItem activity : orderedPathItems) {
            if (activity.getActivityType() == LearningActivityType.READING && activity.getLearningPackage() != null) {
                Long packageId = activity.getLearningPackage().getId();
                nextPartIndexByPackage.merge(packageId, activity.getPartIndex() == null ? 1 : activity.getPartIndex(), Math::max);
            }
        }

        var childrenToCreate = new ArrayList<PlannedChild>();
        int order = 1;
        for (UserLearningPathItem activity : orderedPathItems) {
            activity.setOrderIndex(order++);
            SplitPlan plan = plans.get(activity.getId());
            if (plan != null) {
                for (int groupIndex = 1; groupIndex < plan.groups().size(); groupIndex++) {
                    int partIndex = activity.getLearningPackage() == null
                            ? Math.max(1, (activity.getPartIndex() == null ? 1 : activity.getPartIndex()) + groupIndex)
                            : nextPartIndexByPackage.merge(activity.getLearningPackage().getId(), 1, Integer::sum);
                    UserLearningPathItem child = copyActivity(activity, plan, groupIndex, partIndex, order);
                    childrenToCreate.add(new PlannedChild(plan, groupIndex, child));
                    order++;
                }
            }
        }
        items.saveAll(orderedPathItems);
        items.flush();

        Map<Long, Map<Integer, UserLearningPathItem>> targets = new HashMap<>();
        for (SplitPlan plan : plans.values()) {
            targets.computeIfAbsent(plan.activity().getId(), ignored -> new HashMap<>()).put(0, plan.activity());
        }
        for (PlannedChild child : childrenToCreate) {
            UserLearningPathItem saved = items.save(child.activity());
            targets.get(child.plan().activity().getId()).put(child.groupIndex(), saved);
        }

        for (SplitPlan plan : plans.values()) {
            migrateGroupData(plan, targets.get(plan.activity().getId()));
        }
        items.flush();
        return report(path.getId(), plans.size(), diagnostics);
    }

    private SplitPlan inspect(UserLearningPathItem activity, List<UserLearningPathItem> pathItems,
            CatalogResult resolved, List<Diagnostic> diagnostics) {
        List<LearningActivityMember> activityMembers = members.findByActivityIdOrderByOrderIndex(activity.getId()).stream()
                .sorted(Comparator.comparing(LearningActivityMember::getOrderIndex,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (activityMembers.isEmpty()) return null;
        if (activityMembers.stream().anyMatch(member -> member.getMemberType() != LearningMemberType.COMPREHENSION_QUESTION)) {
            diagnostics.add(diagnostic(activity, "UNEXPECTED_MEMBER_TYPE"));
            return null;
        }
        Map<Long, ComprehensionExercise> exerciseByQuestion = new HashMap<>();
        for (ComprehensionExercise exercise : resolved.exercises()) {
            for (var question : exercise.questions()) {
                if (exerciseByQuestion.putIfAbsent(question.id(), exercise) != null) {
                    diagnostics.add(diagnostic(activity, "DUPLICATE_CATALOG_QUESTION"));
                    return null;
                }
            }
        }

        Map<String, ExerciseGroupBuilder> grouped = new LinkedHashMap<>();
        Set<String> closed = new HashSet<>();
        String lastFingerprint = null;
        Set<Long> uniqueQuestionIds = new HashSet<>();
        for (LearningActivityMember member : activityMembers) {
            Long questionId = member.getCanonicalId();
            if (questionId == null || !uniqueQuestionIds.add(questionId)) {
                diagnostics.add(diagnostic(activity, "DUPLICATE_OR_EMPTY_MEMBERSHIP"));
                return null;
            }
            ComprehensionExercise exercise = exerciseByQuestion.get(questionId);
            if (exercise == null) {
                diagnostics.add(diagnostic(activity, "QUESTION_NOT_IN_CATALOG"));
                return null;
            }
            if (!Objects.equals(lastFingerprint, exercise.fingerprint())) {
                if (closed.contains(exercise.fingerprint())) {
                    diagnostics.add(diagnostic(activity, "EXERCISE_ORDER_AMBIGUOUS"));
                    return null;
                }
                if (lastFingerprint != null) closed.add(lastFingerprint);
                lastFingerprint = exercise.fingerprint();
            }
            grouped.computeIfAbsent(exercise.fingerprint(), ignored -> new ExerciseGroupBuilder(exercise))
                    .members().add(member);
        }
        if (grouped.size() <= 1) return null;

        var groups = grouped.values().stream().map(ExerciseGroupBuilder::build).toList();
        Set<String> revisions = activityMembers.stream().map(LearningActivityMember::getSourceRevision)
                .collect(Collectors.toSet());
        if (revisions.size() != 1 || revisions.contains(null)) {
            diagnostics.add(diagnostic(activity, "MEMBER_REVISION_AMBIGUOUS"));
            return null;
        }
        for (UserLearningPathItem other : pathItems) {
            if (other.getId().equals(activity.getId()) || other.getActivityType() != LearningActivityType.READING
                    || !samePackage(activity, other)) continue;
            Set<Long> overlap = members.findByActivityIdOrderByOrderIndex(other.getId()).stream()
                    .map(LearningActivityMember::getCanonicalId).filter(uniqueQuestionIds::contains)
                    .collect(Collectors.toSet());
            if (!overlap.isEmpty()) {
                diagnostics.add(diagnostic(activity, "OVERLAPPING_READING_ACTIVITY"));
                return null;
            }
        }

        List<LearningMicroCheckState> activityStates = checkStates.findByActivityIdOrderByBatchIndex(activity.getId());
        Set<Integer> stateBatches = new HashSet<>();
        for (LearningMicroCheckState state : activityStates) {
            if (state.getBatchIndex() == null || state.getBatchIndex() < 0 || state.getBatchIndex() >= groups.size()
                    || !stateBatches.add(state.getBatchIndex())) {
                diagnostics.add(diagnostic(activity, "MICRO_CHECK_BATCH_AMBIGUOUS"));
                return null;
            }
        }
        List<LearningQuizSession> attempts = quizSessions.findByActivityIdOrderByIdAsc(activity.getId());
        for (LearningQuizSession attempt : attempts) {
            if (!READING_MICRO_CHECK.equals(attempt.getQuizType()) && !PRACTICE_REPLAY.equals(attempt.getQuizType())) {
                diagnostics.add(diagnostic(activity, "UNEXPECTED_ATTEMPT_TYPE"));
                return null;
            }
            if (attempt.getBatchIndex() == null || attempt.getBatchIndex() < 0 || attempt.getBatchIndex() >= groups.size()) {
                diagnostics.add(diagnostic(activity, "ATTEMPT_BATCH_AMBIGUOUS"));
                return null;
            }
        }
        LearningStudySession studySession = studySessions.findByActivityId(activity.getId()).orElse(null);
        List<LearningStudyCard> cards = studySession == null ? List.of()
                : studyCards.findBySessionIdOrderByOrderIndex(studySession.getId());
        Set<Long> allMemberIds = new HashSet<>(uniqueQuestionIds);
        if (cards.stream().anyMatch(card -> !allMemberIds.contains(card.getCanonicalId()))) {
            diagnostics.add(diagnostic(activity, "STUDY_CARD_MEMBERSHIP_AMBIGUOUS"));
            return null;
        }
        return new SplitPlan(activity, groups, activityStates, attempts, studySession, cards,
                activity.getActivityState());
    }

    private void migrateGroupData(SplitPlan plan, Map<Integer, UserLearningPathItem> targets) {
        UserLearningPathItem original = plan.activity();
        Map<Integer, LearningMicroCheckState> stateByGroup = plan.states().stream()
                .collect(Collectors.toMap(LearningMicroCheckState::getBatchIndex, state -> state));
        Map<Long, Integer> groupByQuestion = new HashMap<>();
        for (int i = 0; i < plan.groups().size(); i++) {
            for (LearningActivityMember member : plan.groups().get(i).members()) groupByQuestion.put(member.getCanonicalId(), i);
        }

        for (int groupIndex = 0; groupIndex < plan.groups().size(); groupIndex++) {
            ExerciseGroup group = plan.groups().get(groupIndex);
            UserLearningPathItem target = targets.get(groupIndex);
            LearningMicroCheckState state = stateByGroup.get(groupIndex);
            target.setActivityState(activityStateFor(plan, groupIndex, state));
            target.setStudyStatus(studyStatusFor(plan, group));
            target.setAssessmentStatus(assessmentStatusFor(plan, state));

            int memberOrder = 0;
            for (LearningActivityMember member : group.members()) {
                member.setActivity(target);
                member.setOrderIndex(++memberOrder);
                member.setSourceRevision(group.revision());
            }
            members.saveAll(group.members());

            if (state != null) {
                state.setActivity(target);
                state.setBatchIndex(0);
                checkStates.save(state);
            }
            int originalBatchIndex = groupIndex;
            plan.attempts().stream().filter(attempt -> Objects.equals(attempt.getBatchIndex(), originalBatchIndex))
                    .forEach(attempt -> {
                        attempt.setActivityId(target.getId());
                        attempt.setBatchIndex(0);
                    });
        }
        quizSessions.saveAll(plan.attempts());

        if (plan.studySession() != null) {
            plan.studySession().setSourceRevision(plan.groups().getFirst().revision());
            studySessions.save(plan.studySession());
            Map<Integer, LearningStudySession> sessionByGroup = new HashMap<>();
            sessionByGroup.put(0, plan.studySession());
            for (int i = 1; i < plan.groups().size(); i++) {
                sessionByGroup.put(i, studySessions.save(LearningStudySession.builder()
                        .activity(targets.get(i)).sourceRevision(plan.groups().get(i).revision()).build()));
            }
            Map<Integer, Integer> orderByGroup = new HashMap<>();
            for (LearningStudyCard card : plan.cards()) {
                Integer groupIndex = groupByQuestion.get(card.getCanonicalId());
                LearningStudySession targetSession = sessionByGroup.get(groupIndex);
                card.setSession(targetSession);
                card.setOrderIndex(orderByGroup.merge(groupIndex, 1, Integer::sum));
            }
            studyCards.saveAll(plan.cards());
        }
        items.saveAll(targets.values());
    }

    private LearningActivityState activityStateFor(SplitPlan plan, int groupIndex) {
        return activityStateFor(plan, groupIndex, stateFor(plan, groupIndex));
    }

    private LearningActivityState activityStateFor(SplitPlan plan, int groupIndex, LearningMicroCheckState state) {
        if (state != null) {
            if (state.isPassed()) return LearningActivityState.PASSED;
            if (state.isReviewRequired() || state.getCooldownUntil() != null
                    && state.getCooldownUntil().isAfter(LocalDateTime.now())) return LearningActivityState.COOLDOWN;
            if (state.getAttemptsUsed() != null && state.getAttemptsUsed() > 0) return LearningActivityState.CHECK_READY;
        }
        boolean issuedAttempt = plan.attempts().stream().anyMatch(attempt -> Objects.equals(attempt.getBatchIndex(), groupIndex)
                && READING_MICRO_CHECK.equals(attempt.getQuizType()) && "ISSUED".equals(attempt.getStatus()));
        if (issuedAttempt) return LearningActivityState.CHECK_READY;
        if (state == null && (plan.originalActivityState() == LearningActivityState.PASSED
                || plan.originalActivityState() == LearningActivityState.COMPLETED)) return LearningActivityState.PASSED;
        return plan.originalActivityState();
    }

    private String assessmentStatusFor(SplitPlan plan, LearningMicroCheckState state) {
        return state != null && state.isPassed()
                || state == null && (plan.originalActivityState() == LearningActivityState.PASSED
                        || plan.originalActivityState() == LearningActivityState.COMPLETED)
                ? "PASSED" : "NOT_READY";
    }

    private LearningMicroCheckState stateFor(SplitPlan plan, int groupIndex) {
        return plan.states().stream().filter(state -> Objects.equals(state.getBatchIndex(), groupIndex)).findFirst().orElse(null);
    }

    private String studyStatusFor(SplitPlan plan, ExerciseGroup group) {
        if (plan.studySession() == null) return plan.activity().getStudyStatus();
        Set<Long> questionIds = group.members().stream().map(LearningActivityMember::getCanonicalId).collect(Collectors.toSet());
        List<LearningStudyCard> groupCards = plan.cards().stream().filter(card -> questionIds.contains(card.getCanonicalId())).toList();
        if (groupCards.isEmpty() || groupCards.stream().noneMatch(LearningStudyCard::isFlipped)) return "NOT_STARTED";
        if (groupCards.stream().allMatch(LearningStudyCard::isFlipped)) return "STUDIED";
        return "IN_PROGRESS";
    }

    private UserLearningPathItem copyActivity(UserLearningPathItem source, SplitPlan plan, int groupIndex,
            int partIndex, int orderIndex) {
        return UserLearningPathItem.builder()
                .learningPath(source.getLearningPath())
                .learningPackage(source.getLearningPackage())
                .activityType(LearningActivityType.READING)
                .activityState(activityStateFor(plan, groupIndex))
                .partIndex(partIndex)
                .lesson(source.getLesson())
                .grammarPoint(source.getGrammarPoint())
                .vocabularyEntry(source.getVocabularyEntry())
                .orderIndex(orderIndex)
                .stageId(source.getStageId())
                .reason(source.getReason())
                .kind(source.getKind())
                .phase(source.getPhase())
                .reasonCode(source.getReasonCode())
                .dayNumber(source.getDayNumber())
                .estimatedMinutes(minutesFor(source.getEstimatedMinutes(), plan.groups().size(), groupIndex))
                .matchedTagCodes(source.getMatchedTagCodes())
                .studyStatus(studyStatusFor(plan, plan.groups().get(groupIndex)))
                .assessmentStatus(assessmentStatusFor(plan, stateFor(plan, groupIndex)))
                .build();
    }

    private Integer minutesFor(Integer total, int parts, int part) {
        if (total == null) return null;
        int base = total / parts;
        int extra = total % parts;
        return base + (part < extra ? 1 : 0);
    }

    private String sourceLevel(UserLearningPathItem activity) {
        LessonSource source = lessonSource(activity);
        return source == null ? null : source.level();
    }

    private LessonSource lessonSource(UserLearningPathItem activity) {
        var lesson = activity.getLearningPackage() != null && activity.getLearningPackage().getSourceLesson() != null
                ? activity.getLearningPackage().getSourceLesson() : activity.getLesson();
        if (lesson == null || lesson.getUnit() == null || lesson.getUnit().getTextbook() == null) return null;
        return new LessonSource(lesson.getUnit().getTextbook().getJlptLevel());
    }

    private boolean samePackage(UserLearningPathItem left, UserLearningPathItem right) {
        Long leftId = left.getLearningPackage() == null ? null : left.getLearningPackage().getId();
        Long rightId = right.getLearningPackage() == null ? null : right.getLearningPackage().getId();
        return Objects.equals(leftId, rightId);
    }

    private Diagnostic diagnostic(UserLearningPathItem activity, String reason) {
        return new Diagnostic(activity.getLearningPath().getId(), activity.getId(), reason);
    }

    private ReconciliationReport report(Long pathId, int splitActivities, List<Diagnostic> diagnostics) {
        diagnostics.forEach(diagnostic -> log.warn(
                "Reading path upgrade skipped pathId={} activityId={} reason={}",
                diagnostic.pathId(), diagnostic.activityId(), diagnostic.reasonCode()));
        return new ReconciliationReport(splitActivities, List.copyOf(diagnostics));
    }

    public record Diagnostic(Long pathId, Long activityId, String reasonCode) { }
    public record ReconciliationReport(int splitActivities, List<Diagnostic> diagnostics) { }

    private record LessonSource(String level) { }
    private record ExerciseGroup(ComprehensionExercise exercise, List<LearningActivityMember> members, String revision) { }
    private static final class ExerciseGroupBuilder {
        private final ComprehensionExercise exercise;
        private final List<LearningActivityMember> members = new ArrayList<>();
        private ExerciseGroupBuilder(ComprehensionExercise exercise) { this.exercise = exercise; }
        private List<LearningActivityMember> members() { return members; }
        private ExerciseGroup build() {
            return new ExerciseGroup(exercise, List.copyOf(members),
                    ComprehensionRevision.combined(LearningActivityType.READING, List.of(exercise)));
        }
    }
    private record SplitPlan(UserLearningPathItem activity, List<ExerciseGroup> groups,
            List<LearningMicroCheckState> states, List<LearningQuizSession> attempts,
            LearningStudySession studySession, List<LearningStudyCard> cards,
            LearningActivityState originalActivityState) { }
    private record PlannedChild(SplitPlan plan, int groupIndex, UserLearningPathItem activity) { }
}
