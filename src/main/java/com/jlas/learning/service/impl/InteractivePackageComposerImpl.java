package com.jlas.learning.service.impl;

import com.jlas.common.exception.*;
import com.jlas.learning.config.InteractiveLearningV2Properties;
import com.jlas.learning.dto.*;
import com.jlas.learning.entity.*;
import com.jlas.learning.enums.*;
import com.jlas.learning.repository.*;
import com.jlas.learning.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor
public class InteractivePackageComposerImpl implements InteractivePackageComposer {
    static final String DEFERRED_DIALOGUE_AUDIO = "DEFERRED_DIALOGUE_AUDIO_NOT_READY";

    /** A listening-only lesson (dialogue, nothing else) whose only problem is missing dialogue audio. */
    private static boolean isListeningAwaitingAudio(InteractiveContentSnapshot snapshot) {
        return snapshot.vocabularyIds().isEmpty() && snapshot.kanjiIds().isEmpty() && snapshot.grammarPointIds().isEmpty()
                && !snapshot.dialogueLineIds().isEmpty() && !snapshot.gaps().isEmpty()
                && snapshot.gaps().stream().allMatch(g -> "DIALOGUE_AUDIO_NOT_READY".equals(g.reasonCode()));
    }

    private final InteractiveLearningV2Properties properties;
    private final InteractiveContentCatalog catalog;
    private final LearningPathPackageRepository packages;
    private final UserLearningPathItemRepository items;
    private final LearningActivityMemberRepository members;
    private final UserLearningStageRepository stages;
    private final ComprehensionExerciseCatalog comprehensionCatalog;
    private final LearningWorkloadPolicy workloadPolicy = new LearningWorkloadPolicy();
    private final ComprehensionWorkloadPolicy comprehensionWorkloadPolicy = new ComprehensionWorkloadPolicy();
    private final LearningDailyWorkloadPacker workloadPacker = new LearningDailyWorkloadPacker();

    @Override @Transactional
    public InteractiveCompositionResult compose(UserLearningPath path, List<ScheduledLearningResource> schedule) {
        if (!properties.isEnabled()) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Learning Path V2 chưa được bật.");
        if (path.getExperienceVersion()!=LearningExperienceVersion.INTERACTIVE_V2) throw new ApiException(ErrorCode.CONFLICT,"Composer V2 chỉ nhận path INTERACTIVE_V2.");
        // [JLAS-W0193] Task 9 fix: this idempotency/retry check must be scoped to GOAL_LEARNING packages
        // only. Warm-up B (WarmUpBComposerImpl) now composes WARM_UP packages for the same path BEFORE
        // this composer runs; an unscoped existsByLearningPathId would make this composer wrongly believe
        // target packages already exist (because warm-up ones do) and silently compose zero target
        // packages for every V2 path that has any warm-up content — a genuine integration-blocking bug,
        // not a hypothetical one, caught while wiring Task 9.
        boolean anyPackageExistsAlready = packages.existsByLearningPathId(path.getId());
        if (packages.existsByLearningPathIdAndPhase(path.getId(), LearningPhase.GOAL_LEARNING.name())) {
            long existingTargetCount = packages.findByLearningPathIdOrderByOrderIndex(path.getId()).stream()
                    .filter(p -> LearningPhase.GOAL_LEARNING.name().equals(p.getPhase())).count();
            return new InteractiveCompositionResult(true, (int) existingTargetCount, List.of());
        }

        Map<Long,ScheduledLearningResource> targets=new LinkedHashMap<>();
        if(schedule!=null) schedule.stream().filter(s->s.phase()==LearningPhase.GOAL_LEARNING)
                .forEach(s->targets.putIfAbsent(s.resource().lessonId(),s));
        if(targets.isEmpty()) return new InteractiveCompositionResult(false,0,List.of(new CoverageGap("PATH:"+path.getId(),"MISSING_TARGET_PACKAGES")));

        List<Map.Entry<ScheduledLearningResource,InteractiveContentSnapshot>> inspected=new ArrayList<>();
        List<CoverageGap> gaps=new ArrayList<>();
        List<CoverageGap> deferred=new ArrayList<>();
        targets.values().forEach(seed->{var snapshot=catalog.inspect(seed.resource().lessonId(),seed.resource().level());
            if (isListeningAwaitingAudio(snapshot)) {
                // Owner decision 2026-10-06: defer, never silently drop; the lesson joins once its audio exists.
                deferred.add(new CoverageGap("LESSON:" + snapshot.lesson().getId(), DEFERRED_DIALOGUE_AUDIO));
                return;
            }
            inspected.add(Map.entry(seed,snapshot)); gaps.addAll(snapshot.gaps());});
        if(!gaps.isEmpty()) return new InteractiveCompositionResult(false,0,gaps);
        if(inspected.isEmpty()) return new InteractiveCompositionResult(false,0,deferred);

        int dailyBudget = path.getMinutesPerDay() == null ? 0 : path.getMinutesPerDay();
        if (dailyBudget <= 0) {
            return new InteractiveCompositionResult(false, 0,
                    List.of(new CoverageGap("PATH:" + path.getId(), "MISSING_DAILY_WORKLOAD_BUDGET")));
        }

        List<LessonWorkPlan> lessonPlans = new ArrayList<>();
        boolean communicationGoal = GoalPathPolicy.isCommunicationGoal(path);
        // D3-05 (owner 2026-10-24): a JLPT day is filled by minutes only — no per-day grammar, reading or listening
        // count. A J-LAS compiled-book chapter also starts in what is left of the day its previous chapter ended in, so
        // the number of days follows the workload; each stage starts on a new day and warm-up days are never shared.
        // Only compiled chapters share days: the shared-day schema (migration 052) ships together with that book.
        int usedToday = 0;
        String dayLevel = null;
        var comprehension = new ComprehensionIndex();
        // OD-D2-05: on a JLPT path each word/Kanji is required only where it first appears (lessons in path order).
        CanonicalFirstOccurrence firstOccurrence = communicationGoal ? null : new CanonicalFirstOccurrence();
        for (var entry : inspected) {
            // Curriculum membership is not Smart Test remediation: never discard 2–4 real lesson words.
            // A single distinct word cannot yield a valid same-lesson choice question, so surface the
            // source gap before persisting any partial path instead of silently losing that word.
            if (!communicationGoal && new LinkedHashSet<>(entry.getValue().vocabularyIds()).size() == 1) {
                gaps.add(new CoverageGap("LESSON:" + entry.getValue().lesson().getId(),
                        "VOCAB_QUIZ_DISTRACTOR_NOT_READY"));
                continue;
            }
            var activities = activitiesFor(firstOccurrence == null ? entry.getValue() : firstOccurrence.keepFirst(entry.getValue()),
                    dailyBudget, communicationGoal, comprehension);
            if (activities.isEmpty()) {
                gaps.add(new CoverageGap("LESSON:" + entry.getValue().lesson().getId(), "NO_LEARNABLE_CONTENT"));
                continue;
            }
            if (communicationGoal) {
                lessonPlans.add(new LessonWorkPlan(entry.getKey(), entry.getValue(),
                        workloadPacker.pack(activities, dailyBudget), false));
                continue;
            }
            String level = entry.getKey().resource().level();
            boolean compiledChapter = isCompiledChapter(entry.getValue().lesson());
            boolean sharesDay = compiledChapter && java.util.Objects.equals(level, dayLevel);
            var dayPlan = workloadPacker.packContinuing(activities, dailyBudget, sharesDay ? usedToday : 0);
            var segments = dayPlan.segments();
            int lastMinutes = segments.getLast().estimatedMinutes();
            usedToday = !compiledChapter ? 0
                    : segments.size() == 1 && dayPlan.firstContinuesDay() ? usedToday + lastMinutes : lastMinutes;
            dayLevel = compiledChapter ? level : null;
            lessonPlans.add(new LessonWorkPlan(entry.getKey(), entry.getValue(), segments, dayPlan.firstContinuesDay()));
        }
        if (!gaps.isEmpty()) return new InteractiveCompositionResult(false, 0, gaps);

        Map<String,Long> stageByLevel=new HashMap<>();
        stages.findByLearningPathIdOrderByStageIndexAsc(path.getId())
                .forEach(stage -> stageByLevel.put(stage.getLevel(), stage.getId()));
        lessonPlans.stream().map(plan -> plan.seed().resource().level()).distinct()
                .filter(level -> !stageByLevel.containsKey(level))
                .forEach(level -> gaps.add(new CoverageGap("LEVEL:" + level, "LEVEL_MISMATCH")));
        if(!gaps.isEmpty()) return new InteractiveCompositionResult(false,0,gaps);

        // Spec §2.1: a JLPT stage needs content for every required skill — never a partial path. REPORT records the
        // missing skills as non-blocking notes; ENFORCE (owner-approved switch) refuses the path before saving anything.
        if (!communicationGoal) {
            List<CoverageGap> missing = missingRequiredSkills(lessonPlans);
            if (!missing.isEmpty() && properties.enforcesReadiness()) return new InteractiveCompositionResult(false, 0, missing);
            deferred.addAll(missing);
        }

        int packageOrder=0; int activityOrder=items.findMaxOrderIndexByLearningPathId(path.getId());
        int dayNumber=items.findMaxDayNumberByLearningPathId(path.getId());
        // [JLAS-W0193] Task 9 fix: package order_index must continue after any Warm-up B packages already
        // saved for this path (uk_learning_package_path_order is unique per path) — restarting at 1 here
        // would collide with an existing warm-up package's order_index.
        int packageOrderBase = packages.findByLearningPathIdOrderByOrderIndex(path.getId()).stream()
                .mapToInt(LearningPathPackage::getOrderIndex).max().orElse(0);
        for (LessonWorkPlan lessonPlan : lessonPlans) {
            int segmentIndex = 0;
            for (LearningDailyWorkloadPacker.PackageSegment segment : lessonPlan.segments()) {
                packageOrder++;
                segmentIndex++;
                // The first part of a chapter may share the day its previous chapter ended in (D3-05).
                if (!(segmentIndex == 1 && lessonPlan.firstContinuesDay())) dayNumber++;
                var seed = lessonPlan.seed();
                var snapshot = lessonPlan.snapshot();
                String title = lessonPlan.segments().size() == 1 ? seed.resource().title()
                        : seed.resource().title() + " · Phần " + segmentIndex + "/" + lessonPlan.segments().size();
                var learningPackage=packages.save(LearningPathPackage.builder().learningPath(path).sourceLesson(snapshot.lesson())
                    .sourceSegmentIndex(segmentIndex)
                    .contentRevision(snapshot.revision()).stageId(stageByLevel.get(seed.resource().level())).phase(LearningPhase.GOAL_LEARNING.name())
                    .dayNumber(dayNumber).orderIndex(packageOrderBase+packageOrder).title(title)
                    // [JLAS-W0193] Task 9 fix: only AVAILABLE when this is truly the first package in the
                    // whole path (no Warm-up B package precedes it) — otherwise the learner would see both
                    // a warm-up package and a target package startable at once, contradicting design spec
                    // §5.6 ("không gộp warm-up và target vào cùng ngày"). When warm-up packages exist, the
                    // first target package starts LOCKED and unlocks via the existing next-package-unlock
                    // mechanism (Task 8) once the last warm-up package completes.
                    .status(communicationGoal || packageOrder==1&&!anyPackageExistsAlready
                            ? LearningPackageStatus.AVAILABLE : LearningPackageStatus.LOCKED)
                    .estimatedMinutes(segment.estimatedMinutes()).build());
                // uk(package, activity_type, part_index): same-type activities of one package are parts 1..n.
                Map<LearningActivityType,Integer> parts = new java.util.EnumMap<>(LearningActivityType.class);
                for (var activity : segment.activities()) {
                    activityOrder = saveActivity(path, learningPackage, activity.activityType(), activity.memberType(),
                            activity.memberIds(), snapshot.revision(), activity.estimatedMinutes(), activityOrder,
                            parts.merge(activity.activityType(), 1, Integer::sum), snapshot.vocabularyContexts(), comprehension);
                }
            }
        }
        return new InteractiveCompositionResult(true,packageOrder,deferred);
    }

    static boolean isCompiledChapter(com.jlas.dictionary.entity.Lesson lesson) {
        return lesson != null && lesson.getUnit() != null && lesson.getUnit().getTextbook() != null
                && com.jlas.common.learning.CompiledBookPolicy.isCompiledBook(lesson.getUnit().getTextbook().getSourceCode());
    }

    static final List<LearningActivityType> REQUIRED_JLPT_SKILLS = List.of(LearningActivityType.VOCABULARY,
            LearningActivityType.KANJI, LearningActivityType.GRAMMAR, LearningActivityType.READING, LearningActivityType.LISTENING);

    /** Required skills with no activity at a stage level, in a stable level-then-skill order. */
    private static List<CoverageGap> missingRequiredSkills(List<LessonWorkPlan> lessonPlans) {
        Map<String, java.util.Set<LearningActivityType>> covered = new LinkedHashMap<>();
        for (LessonWorkPlan plan : lessonPlans) {
            var skills = covered.computeIfAbsent(plan.seed().resource().level(), level -> java.util.EnumSet.noneOf(LearningActivityType.class));
            plan.segments().forEach(segment -> segment.activities().forEach(activity -> skills.add(activity.activityType())));
        }
        List<CoverageGap> missing = new ArrayList<>();
        covered.forEach((level, skills) -> REQUIRED_JLPT_SKILLS.stream().filter(skill -> !skills.contains(skill))
                .forEach(skill -> missing.add(new CoverageGap("LEVEL:" + level, "MISSING_SKILL_" + skill.name()))));
        return missing;
    }

    private List<LearningWorkloadPolicy.ActivityPlan> activitiesFor(
            InteractiveContentSnapshot snapshot, int dailyBudget, boolean communicationGoal, ComprehensionIndex comprehension) {
        List<LearningWorkloadPolicy.ActivityPlan> result = new ArrayList<>();
        addActivities(result, LearningActivityType.VOCABULARY, LearningMemberType.VOCABULARY_ENTRY,
                snapshot.vocabularyIds(), dailyBudget);
        if (!communicationGoal) {
            addActivities(result, LearningActivityType.KANJI, LearningMemberType.KANJI,
                    snapshot.kanjiIds(), dailyBudget);
        }
        addActivities(result, LearningActivityType.GRAMMAR, LearningMemberType.GRAMMAR_POINT,
                snapshot.grammarPointIds(), dailyBudget,
                communicationGoal ? Integer.MAX_VALUE : LearningWorkloadPolicy.JLPT_GRAMMAR_POINTS_PER_ACTIVITY);
        addActivities(result, LearningActivityType.DIALOGUE, LearningMemberType.DIALOGUE_LINE,
                snapshot.dialogueLineIds(), dailyBudget);
        if (!communicationGoal && !snapshot.readingQuestionIds().isEmpty()) {
            result.addAll(readingExerciseActivities(snapshot, comprehension));
        }
        if (!communicationGoal && !snapshot.listeningQuestionIds().isEmpty()) {
            result.addAll(listeningClipActivities(snapshot, comprehension));
        }
        return List.copyOf(result);
    }

    /**
     * Comprehension exercises by question id, resolved once per skill and level for one composition: a resolve loads
     * every question of the level, and a compiled-book path asks for it for every chapter and every activity.
     */
    private final class ComprehensionIndex {
        private final Map<String, Map<Long, com.jlas.learning.dto.ComprehensionExercise>> bySkillAndLevel = new HashMap<>();

        Map<Long, com.jlas.learning.dto.ComprehensionExercise> byQuestion(String skill, String level) {
            return bySkillAndLevel.computeIfAbsent(skill + "|" + level, key -> {
                Map<Long, com.jlas.learning.dto.ComprehensionExercise> index = new HashMap<>();
                comprehensionCatalog.resolve(skill, level).exercises().forEach(exercise ->
                        exercise.questions().forEach(question -> index.put(question.id(), exercise)));
                return index;
            });
        }
    }

    private List<LearningWorkloadPolicy.ActivityPlan> readingExerciseActivities(InteractiveContentSnapshot snapshot,
            ComprehensionIndex comprehension) {
        var byQuestion = comprehension.byQuestion("READING", snapshot.lesson().getUnit().getTextbook().getJlptLevel());
        Map<String,List<Long>> groups = new LinkedHashMap<>();
        for (Long id : snapshot.readingQuestionIds()) {
            var exercise = byQuestion.get(id);
            if (exercise == null) throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                    "Bài đọc của chương chưa khớp với bài đọc đã phát hành.");
            groups.computeIfAbsent(exercise.fingerprint(), ignored -> new ArrayList<>()).add(id);
        }
        return groups.values().stream().map(ids -> comprehensionWorkloadPolicy.plan(LearningActivityType.READING,ids)).toList();
    }

    private List<LearningWorkloadPolicy.ActivityPlan> listeningClipActivities(InteractiveContentSnapshot snapshot,
            ComprehensionIndex comprehension) {
        var byQuestion = comprehension.byQuestion("LISTENING", snapshot.lesson().getUnit().getTextbook().getJlptLevel());
        Map<String,List<Long>> groups = new LinkedHashMap<>();
        for (Long id : snapshot.listeningQuestionIds()) {
            var exercise = byQuestion.get(id);
            String group = exercise == null ? null : exercise.fingerprint();
            if (group == null) throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                    "Bài nghe của chương chưa khớp với câu hỏi đã phát hành.");
            groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(id);
        }
        return groups.values().stream().map(comprehensionWorkloadPolicy::planAudioClip).toList();
    }

    private void addActivities(List<LearningWorkloadPolicy.ActivityPlan> output, LearningActivityType type,
            LearningMemberType memberType, List<Long> memberIds, int dailyBudget) {
        addActivities(output, type, memberType, memberIds, dailyBudget, Integer.MAX_VALUE);
    }

    private void addActivities(List<LearningWorkloadPolicy.ActivityPlan> output, LearningActivityType type,
            LearningMemberType memberType, List<Long> memberIds, int dailyBudget, int memberCap) {
        for (List<Long> group : workloadPolicy.partition(type, memberIds, dailyBudget, memberCap)) {
            output.add(new LearningWorkloadPolicy.ActivityPlan(type, memberType, group,
                    workloadPolicy.estimateMinutes(type, group.size())));
        }
    }

    private int saveActivity(UserLearningPath path,LearningPathPackage pkg,LearningActivityType type,
            LearningMemberType memberType,List<Long> ids,String revision,int estimatedMinutes,int globalOrder,int partIndex,
            Map<Long,VocabularyLessonContext> vocabularyContexts, ComprehensionIndex comprehension){
        int next=globalOrder+1;
        var activity=items.save(UserLearningPathItem.builder().learningPath(path).learningPackage(pkg).lesson(pkg.getSourceLesson())
                .orderIndex(next).partIndex(partIndex).phase(LearningPhase.GOAL_LEARNING.name()).dayNumber(pkg.getDayNumber())
                .estimatedMinutes(estimatedMinutes).activityType(type).activityState(LearningActivityState.LOCKED).build());
        List<LearningActivityMember> rows=new ArrayList<>(); int index=0;
        String memberRevision = type == LearningActivityType.READING || type == LearningActivityType.LISTENING
                ? comprehensionRevision(type, ids, pkg.getSourceLesson().getUnit().getTextbook().getJlptLevel(), comprehension)
                : revision;
        for(Long id:new LinkedHashSet<>(ids)) rows.add(LearningActivityMember.builder().activity(activity).memberType(memberType)
                .canonicalId(id).orderIndex(++index).sourceRevision(memberRevision)
                .contentSnapshotJson(type == LearningActivityType.VOCABULARY && vocabularyContexts.containsKey(id)
                        ? vocabularyContexts.get(id).toJson() : null).build());
        members.saveAll(rows);
        return next;
    }

    private String comprehensionRevision(LearningActivityType type, List<Long> questionIds, String level,
            ComprehensionIndex comprehension) {
        var byQuestion = comprehension.byQuestion(type.name(), level);
        List<com.jlas.learning.dto.ComprehensionExercise> ordered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Long id : new LinkedHashSet<>(questionIds)) {
            var exercise = byQuestion.get(id);
            if (exercise == null) throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                    "Câu hỏi đọc/nghe không còn khớp với nội dung của lộ trình.");
            if (seen.add(exercise.fingerprint())) ordered.add(exercise);
        }
        return com.jlas.learning.service.impl.engine.ComprehensionRevision.combined(type,ordered);
    }

    private record LessonWorkPlan(ScheduledLearningResource seed, InteractiveContentSnapshot snapshot,
            List<LearningDailyWorkloadPacker.PackageSegment> segments, boolean firstContinuesDay) { }
}
