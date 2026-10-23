package com.jlas.learning.service.impl;

import com.jlas.learning.dto.InteractiveContentSnapshot;
import com.jlas.learning.entity.*;
import com.jlas.learning.enums.*;
import com.jlas.learning.repository.*;
import com.jlas.learning.service.InteractiveContentCatalog;
import com.jlas.learning.service.GoalPathPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Adds only source-backed Grammar and Dialogue activities missing from older communication paths. */
@Service
@RequiredArgsConstructor
@Slf4j
public class InteractiveCommunicationPathUpgradeService {
    private static final List<LearningActivityType> SITUATION_SKILLS =
            List.of(LearningActivityType.GRAMMAR, LearningActivityType.DIALOGUE);

    private final UserLearningPathItemRepository items;
    private final LearningPathPackageRepository packages;
    private final LearningActivityMemberRepository members;
    private final InteractiveContentCatalog catalog;
    private final LearningWorkloadPolicy workloadPolicy = new LearningWorkloadPolicy();

    @Transactional
    public ReconciliationReport reconcile(Long userId, UserLearningPath path) {
        if (path == null || path.getId() == null || userId == null || path.getUser() == null
                || !userId.equals(path.getUser().getId())
                || path.getExperienceVersion() != LearningExperienceVersion.INTERACTIVE_V2
                || !GoalPathPolicy.isCommunicationGoal(path)) {
            return new ReconciliationReport(0, List.of());
        }

        List<LearningPathPackage> pathPackages = packages.findByPathForUpdate(path.getId());
        List<UserLearningPathItem> pathItems = new ArrayList<>(items.findByPathForUpdate(path.getId()));
        int nextOrder = items.findMaxOrderIndexByLearningPathId(path.getId());
        Map<Long, Integer> nextPartByPackageAndSkill = new HashMap<>();
        for (UserLearningPathItem item : pathItems) {
            if (item.getLearningPackage() == null || item.getLearningPackage().getId() == null) continue;
            nextPartByPackageAndSkill.merge(partKey(item.getLearningPackage().getId(), item.getActivityType()),
                    item.getPartIndex() == null ? 1 : item.getPartIndex(), Math::max);
        }

        // Runs on every roadmap read: one member query for the situation activities, not one per activity.
        List<Long> situationIds = pathItems.stream()
                .filter(item -> item.getId() != null && SITUATION_SKILLS.contains(item.getActivityType()))
                .map(UserLearningPathItem::getId).toList();
        Map<Long, List<LearningActivityMember>> membersByActivity = new HashMap<>();
        if (!situationIds.isEmpty()) {
            for (LearningActivityMember member : members.findByActivityIdsInOrder(situationIds)) {
                membersByActivity.computeIfAbsent(member.getActivity().getId(), ignored -> new ArrayList<>()).add(member);
            }
        }

        var diagnostics = new ArrayList<Diagnostic>();
        int added = 0;
        for (LearningPathPackage learningPackage : pathPackages) {
            if (!LearningPhase.GOAL_LEARNING.name().equals(learningPackage.getPhase())
                    || learningPackage.getSourceLesson() == null) continue;
            String level = sourceLevel(learningPackage.getSourceLesson());
            if (level == null) {
                diagnostics.add(new Diagnostic(path.getId(), learningPackage.getId(), "SOURCE_LEVEL_MISSING"));
                continue;
            }
            InteractiveContentSnapshot source = catalog.inspect(learningPackage.getSourceLesson().getId(), level);
            if (!source.gaps().isEmpty()) {
                diagnostics.add(new Diagnostic(path.getId(), learningPackage.getId(), "SOURCE_CONTENT_NOT_READY"));
                continue;
            }
            // Vocabulary-only topic lessons remain their own path entries. Grammar and Dialogue belong to
            // a conversation-backed situation and are backfilled together only when both source sets exist.
            if (source.dialogueLineIds().isEmpty()) continue;
            if (source.grammarPointIds().isEmpty()) {
                diagnostics.add(new Diagnostic(path.getId(), learningPackage.getId(), "SITUATION_GRAMMAR_NOT_READY"));
                continue;
            }

            Map<LearningActivityType, Set<Long>> existingBySkill = new EnumMap<>(LearningActivityType.class);
            boolean packageSafe = true;
            for (LearningActivityType skill : SITUATION_SKILLS) {
                LearningMemberType memberType = skill == LearningActivityType.GRAMMAR
                        ? LearningMemberType.GRAMMAR_POINT : LearningMemberType.DIALOGUE_LINE;
                Set<Long> existingIds = new HashSet<>();
                for (UserLearningPathItem activity : activitiesInPackage(pathItems, learningPackage.getId(), skill)) {
                    List<LearningActivityMember> activityMembers = membersByActivity.getOrDefault(activity.getId(), List.of());
                    if (activityMembers.stream().anyMatch(member -> member.getMemberType() != memberType)) {
                        diagnostics.add(new Diagnostic(path.getId(), learningPackage.getId(), "MEMBER_TYPE_MISMATCH_" + skill));
                        packageSafe = false;
                        break;
                    }
                    activityMembers.stream().map(LearningActivityMember::getCanonicalId).forEach(existingIds::add);
                }
                existingBySkill.put(skill, existingIds);
            }
            if (!packageSafe) continue;

            int dailyBudget = path.getMinutesPerDay() == null || path.getMinutesPerDay() <= 0
                    ? 30 : path.getMinutesPerDay();
            var pending = new ArrayList<PlannedActivity>();
            for (LearningActivityType skill : SITUATION_SKILLS) {
                LearningMemberType memberType = skill == LearningActivityType.GRAMMAR
                        ? LearningMemberType.GRAMMAR_POINT : LearningMemberType.DIALOGUE_LINE;
                List<Long> canonicalIds = skill == LearningActivityType.GRAMMAR
                        ? source.grammarPointIds() : source.dialogueLineIds();
                if (canonicalIds.isEmpty()) continue;
                Set<Long> existingIds = existingBySkill.getOrDefault(skill, Set.of());
                List<Long> missingIds = canonicalIds.stream().filter(Objects::nonNull).distinct()
                        .filter(id -> !existingIds.contains(id)).toList();
                if (missingIds.isEmpty()) continue;

                List<List<Long>> groups;
                try {
                    groups = workloadPolicy.partition(skill, missingIds, dailyBudget);
                } catch (IllegalArgumentException ex) {
                    diagnostics.add(new Diagnostic(path.getId(), learningPackage.getId(), "WORKLOAD_PARTITION_UNAVAILABLE_" + skill));
                    packageSafe = false;
                    break;
                }
                groups.forEach(group -> pending.add(new PlannedActivity(skill, memberType, group,
                        workloadPolicy.estimateMinutes(skill, group.size()))));
            }
            if (!packageSafe || pending.isEmpty()) continue;

            boolean reopenPackage = learningPackage.getStatus() == LearningPackageStatus.COMPLETED;
            int addedToPackage = 0;
            for (PlannedActivity planned : pending) {
                LearningActivityType skill = planned.skill();
                LearningMemberType memberType = planned.memberType();
                List<Long> group = planned.memberIds();
                int partIndex = nextPartByPackageAndSkill.merge(partKey(learningPackage.getId(), skill), 1, Integer::sum);
                LearningActivityState initialState = learningPackage.getStatus() == LearningPackageStatus.IN_PROGRESS
                        || reopenPackage ? LearningActivityState.STUDYING : LearningActivityState.LOCKED;
                UserLearningPathItem activity = items.save(UserLearningPathItem.builder()
                        .learningPath(path)
                        .learningPackage(learningPackage)
                        .lesson(learningPackage.getSourceLesson())
                        .activityType(skill)
                        .activityState(initialState)
                        .partIndex(partIndex)
                        .orderIndex(++nextOrder)
                        .stageId(learningPackage.getStageId())
                        .reason("Hoạt động bổ sung từ nội dung của tình huống giao tiếp.")
                        .kind("CURRICULUM")
                        .phase(learningPackage.getPhase())
                        .reasonCode("COMMUNICATION_SKILL_BACKFILL")
                        .dayNumber(learningPackage.getDayNumber())
                        .estimatedMinutes(planned.estimatedMinutes())
                        .studyStatus("NOT_STARTED")
                        .assessmentStatus("NOT_READY")
                        .build());
                List<LearningActivityMember> rows = new ArrayList<>();
                int memberOrder = 0;
                for (Long canonicalId : group) {
                    rows.add(LearningActivityMember.builder().activity(activity).memberType(memberType)
                            .canonicalId(canonicalId).orderIndex(++memberOrder).sourceRevision(source.revision()).build());
                }
                members.saveAll(rows);
                pathItems.add(activity);
                added++;
                addedToPackage++;
            }
            if (reopenPackage && addedToPackage > 0) {
                learningPackage.setStatus(LearningPackageStatus.IN_PROGRESS);
                learningPackage.setCompletedAt(null);
                learningPackage.setScorePercent(null);
            }
        }

        if (added > 0 && "COMPLETED".equals(path.getStatus())) {
            path.setStatus("ACTIVE");
            path.setCompletedAt(null);
        }
        diagnostics.forEach(diagnostic -> log.warn(
                "Communication path upgrade skipped pathId={} packageId={} reason={}",
                diagnostic.pathId(), diagnostic.packageId(), diagnostic.reasonCode()));
        return new ReconciliationReport(added, List.copyOf(diagnostics));
    }

    private List<UserLearningPathItem> activitiesInPackage(List<UserLearningPathItem> pathItems,
            Long packageId, LearningActivityType skill) {
        return pathItems.stream().filter(item -> item.getLearningPackage() != null
                && Objects.equals(item.getLearningPackage().getId(), packageId) && item.getActivityType() == skill).toList();
    }

    private String sourceLevel(com.jlas.dictionary.entity.Lesson lesson) {
        if (lesson.getUnit() == null || lesson.getUnit().getTextbook() == null) return null;
        return lesson.getUnit().getTextbook().getJlptLevel();
    }

    private Long partKey(Long packageId, LearningActivityType skill) {
        return packageId * 31 + skill.ordinal();
    }

    public record Diagnostic(Long pathId, Long packageId, String reasonCode) { }
    public record ReconciliationReport(int addedActivities, List<Diagnostic> diagnostics) { }
    private record PlannedActivity(LearningActivityType skill, LearningMemberType memberType,
            List<Long> memberIds, int estimatedMinutes) { }
}
