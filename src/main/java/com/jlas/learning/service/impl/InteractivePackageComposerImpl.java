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
    /** F1-3: readings / listenings per chapter a path squeezed by its exam date keeps (the §2.32 floor, N3's standard). */
    static final int CORE_COMPREHENSION_PER_SKILL = 2;

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
    // Optional so unit tests that build the composer by hand need no persistence context (E4 read phase).
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    // F1-3: optional for the same reason; without it every word stays required.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CompiledVocabularyTiers vocabularyTiers;

    @Override @Transactional
    public InteractiveCompositionResult compose(UserLearningPath path, List<ScheduledLearningResource> schedule) {
        return compose(path, schedule, null);
    }

    @Override @Transactional
    public InteractiveCompositionResult compose(UserLearningPath path, List<ScheduledLearningResource> schedule,
            Integer availableDays) {
        return compose(path, schedule, availableDays, KnownLearningUnits.NONE);
    }

    @Override @Transactional
    public InteractiveCompositionResult compose(UserLearningPath path, List<ScheduledLearningResource> schedule,
            Integer availableDays, KnownLearningUnits known) {
        return compose(path, schedule, availableDays, known, List.of());
    }

    @Override @Transactional
    public InteractiveCompositionResult compose(UserLearningPath path, List<ScheduledLearningResource> schedule,
            Integer availableDays, KnownLearningUnits known, List<String> focusSkills) {
        KnownLearningUnits knownUnits = known == null ? KnownLearningUnits.NONE : known;
        Set<String> focus = focusSkills == null ? Set.of() : Set.copyOf(focusSkills);
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
        List<LessonWorkPlan> lessonPlans = new ArrayList<>();
        boolean communicationGoal = GoalPathPolicy.isCommunicationGoal(path);
        var comprehension = new ComprehensionIndex();
        // E4 (2026-10-27): planning only reads the catalog (lessons, words, questions), never the path rows written so
        // far, yet with AUTO flush Hibernate dirty-checked every loaded catalog entity before each of its hundreds of
        // queries (~40% of a two-stage path's creation). Flush once, plan without auto flush, then write as before.
        jakarta.persistence.FlushModeType previousFlush = null;
        if (entityManager != null) {
            entityManager.flush();
            previousFlush = entityManager.getFlushMode();
            entityManager.setFlushMode(jakarta.persistence.FlushModeType.COMMIT);
        }
        try {
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

        // F1-4 (owner 2026-10-28): known words/Kanji leave the required part. Should that empty a required skill of a
        // stage (a learner who passed a whole level before), the stage keeps its full content instead of failing the gate.
        boolean skipKnown = !communicationGoal && !knownUnits.isEmpty();
        java.util.function.UnaryOperator<InteractiveContentSnapshot> knownNarrow = skipKnown
                ? snapshot -> withoutKnown(snapshot, knownUnits) : snapshot -> snapshot;
        List<CoverageGap> knownGaps = new ArrayList<>();
        var plans = planLessons(inspected, dailyBudget, communicationGoal, comprehension, knownGaps, knownNarrow,
                Integer.MAX_VALUE, Integer.MAX_VALUE);
        if (skipKnown) {
            List<CoverageGap> fullGaps = new ArrayList<>();
            var fullPlans = planLessons(inspected, dailyBudget, communicationGoal, comprehension, fullGaps,
                    snapshot -> snapshot, Integer.MAX_VALUE, Integer.MAX_VALUE);
            // Only what leaving the known units out adds counts: the full plan may already miss a skill (reported).
            if (!fullGaps.containsAll(knownGaps)
                    || !missingRequiredSkills(fullPlans).containsAll(missingRequiredSkills(plans))) {
                knownNarrow = snapshot -> snapshot;
                plans = fullPlans;
                knownGaps = fullGaps;
            }
        }
        gaps.addAll(knownGaps);
        lessonPlans.addAll(plans);
        final java.util.function.UnaryOperator<InteractiveContentSnapshot> narrowKnown = knownNarrow;
        // F1-3 (owner 2026-10-27): a JLPT path that cannot finish before the exam requires only the CORE words of each
        // compiled chapter and its first CORE_COMPREHENSION_PER_SKILL readings and listenings (the main book's; the
        // others become that chapter's extra practice, §2.32); EXTENDED words stay in the lesson as "Mở rộng"
        // (LessonReviewItems). Still too long: keep the CORE plan and record DEADLINE_SHORT (never blocks).
        if (availableDays != null && !communicationGoal && gaps.isEmpty() && vocabularyTiers != null) {
            int firstDay = items.findMaxDayNumberByLearningPathId(path.getId());
            if (firstDay + plannedDays(lessonPlans) > availableDays) {
                List<CoverageGap> squeezeGaps = new ArrayList<>();
                // F1-5 (owner 2026-10-28): a focus skill is never trimmed — all its words / readings / listenings stay.
                java.util.function.UnaryOperator<InteractiveContentSnapshot> squeeze = focus.contains("VOCABULARY")
                        ? narrowKnown : snapshot -> coreWordsOnly(narrowKnown.apply(snapshot));
                var corePlans = planLessons(inspected, dailyBudget, false, comprehension, squeezeGaps, squeeze,
                        focus.contains("READING") ? Integer.MAX_VALUE : CORE_COMPREHENSION_PER_SKILL,
                        focus.contains("LISTENING") ? Integer.MAX_VALUE : CORE_COMPREHENSION_PER_SKILL);
                if (squeezeGaps.isEmpty()) {
                    lessonPlans.clear();
                    lessonPlans.addAll(corePlans);
                }
                int days = firstDay + plannedDays(lessonPlans);
                if (days > availableDays) {
                    deferred.add(new CoverageGap("PATH:" + path.getId() + ":DAYS:" + days + "/" + availableDays, "DEADLINE_SHORT"));
                }
            }
        }
        } finally {
            if (previousFlush != null) entityManager.setFlushMode(previousFlush);
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
        List<List<LearningActivityMember>> pendingMembers = new ArrayList<>();
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
                            parts.merge(activity.activityType(), 1, Integer::sum), snapshot.vocabularyContexts(), comprehension,
                            pendingMembers);
                }
            }
        }
        writeMembers(pendingMembers);
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
            InteractiveContentSnapshot snapshot, int dailyBudget, boolean communicationGoal, ComprehensionIndex comprehension,
            int readingCap, int listeningCap) {
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
            result.addAll(readingExerciseActivities(snapshot, comprehension).stream().limit(readingCap).toList());
        }
        if (!communicationGoal && !snapshot.listeningQuestionIds().isEmpty()) {
            result.addAll(listeningClipActivities(snapshot, comprehension).stream().limit(listeningCap).toList());
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
            Map<Long,VocabularyLessonContext> vocabularyContexts, ComprehensionIndex comprehension,
            List<List<LearningActivityMember>> pendingMembers){
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
        pendingMembers.add(rows);
        return next;
    }

    /** Rows per multi-row INSERT: 500 × 6 parameters stays far below MySQL's 65,535-placeholder limit. */
    static final int MEMBER_INSERT_CHUNK = 500;

    /**
     * E6 (2026-10-28): an N5 → N3 path writes ~14,000 members. With IDENTITY ids Hibernate inserts them one statement at
     * a time (~58% of the creation time), so they go in multi-row INSERTs on the transaction's own connection instead.
     * Nothing in the creating transaction holds these rows as entities; later reads query the table. Without a
     * persistence context (hand-built unit tests) they are saved as before.
     */
    private void writeMembers(List<List<LearningActivityMember>> perActivity) {
        if (entityManager == null) {
            perActivity.forEach(members::saveAll); // one saveAll per activity, in creation order, as before
            return;
        }
        List<LearningActivityMember> rows = perActivity.stream().flatMap(List::stream).toList();
        if (rows.isEmpty()) return;
        entityManager.flush();
        entityManager.unwrap(org.hibernate.Session.class).doWork(connection -> {
            for (int from = 0; from < rows.size(); from += MEMBER_INSERT_CHUNK) {
                var chunk = rows.subList(from, Math.min(rows.size(), from + MEMBER_INSERT_CHUNK));
                var sql = new StringBuilder("INSERT INTO learning_activity_member "
                        + "(activity_item_id, member_type, canonical_id, order_index, source_revision, content_snapshot_json) VALUES ");
                for (int i = 0; i < chunk.size(); i++) sql.append(i == 0 ? "(?,?,?,?,?,?)" : ",(?,?,?,?,?,?)");
                try (var statement = connection.prepareStatement(sql.toString())) {
                    int p = 0;
                    for (var row : chunk) {
                        statement.setLong(++p, row.getActivity().getId());
                        statement.setString(++p, row.getMemberType().name());
                        statement.setLong(++p, row.getCanonicalId());
                        statement.setInt(++p, row.getOrderIndex());
                        statement.setString(++p, row.getSourceRevision());
                        if (row.getContentSnapshotJson() == null) statement.setNull(++p, java.sql.Types.VARCHAR);
                        else statement.setString(++p, row.getContentSnapshotJson());
                    }
                    statement.executeUpdate();
                }
            }
        });
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

    /** Packs each inspected lesson into day segments; {@code narrow} may drop words before the OD-D2-05 dedupe. */
    private List<LessonWorkPlan> planLessons(List<Map.Entry<ScheduledLearningResource,InteractiveContentSnapshot>> inspected,
            int dailyBudget, boolean communicationGoal, ComprehensionIndex comprehension, List<CoverageGap> gaps,
            java.util.function.UnaryOperator<InteractiveContentSnapshot> narrow, int readingCap, int listeningCap) {
        List<LessonWorkPlan> lessonPlans = new ArrayList<>();
        // D3-05 (owner 2026-10-24): a JLPT day is filled by minutes only — no per-day grammar, reading or listening
        // count. A J-LAS compiled-book chapter also starts in what is left of the day its previous chapter ended in, so
        // the number of days follows the workload; each stage starts on a new day and warm-up days are never shared.
        // Only compiled chapters share days: the shared-day schema (migration 052) ships together with that book.
        int usedToday = 0;
        String dayLevel = null;
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
            var narrowed = narrow.apply(entry.getValue());
            var activities = activitiesFor(firstOccurrence == null ? narrowed : firstOccurrence.keepFirst(narrowed),
                    dailyBudget, communicationGoal, comprehension, readingCap, listeningCap);
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
        return lessonPlans;
    }

    /** Days the plans add after the path's last planned day (a chapter may start in its previous chapter's day). */
    private static int plannedDays(List<LessonWorkPlan> plans) {
        return plans.stream().mapToInt(plan -> plan.segments().size() - (plan.firstContinuesDay() ? 1 : 0)).sum();
    }

    /**
     * F1-4: the lesson without the learner's known words and Kanji, keeping two words so its choice question has a real
     * distractor (the kept ones stay required, in lesson order).
     */
    static InteractiveContentSnapshot withoutKnown(InteractiveContentSnapshot snapshot, KnownLearningUnits known) {
        List<Long> words = new ArrayList<>(new LinkedHashSet<>(snapshot.vocabularyIds()));
        List<Long> kept = new ArrayList<>(words.stream().filter(id -> !known.vocabularyIds().contains(id)).toList());
        for (Long id : words) {
            if (kept.size() >= 2 || kept.size() == words.size()) break;
            if (!kept.contains(id)) kept.add(id);
        }
        kept.sort(Comparator.comparingInt(words::indexOf));
        List<Long> kanji = snapshot.kanjiIds().stream().filter(id -> !known.kanjiIds().contains(id)).toList();
        if (kept.size() == words.size() && kanji.size() == snapshot.kanjiIds().size()) return snapshot;
        return new InteractiveContentSnapshot(snapshot.lesson(), snapshot.revision(), kept, kanji,
                snapshot.grammarPointIds(), snapshot.dialogueLineIds(), snapshot.gaps(), snapshot.vocabularyContexts(),
                snapshot.readingQuestionIds(), snapshot.listeningQuestionIds());
    }

    /** F1-3: the lesson without its EXTENDED words, keeping two words so its choice question has a real distractor. */
    private InteractiveContentSnapshot coreWordsOnly(InteractiveContentSnapshot snapshot) {
        Set<Long> extended = vocabularyTiers.extendedVocabularyIds(snapshot.lesson().getId());
        if (extended.isEmpty()) return snapshot;
        List<Long> words = new ArrayList<>(new LinkedHashSet<>(snapshot.vocabularyIds()));
        List<Long> core = new ArrayList<>(words.stream().filter(id -> !extended.contains(id)).toList());
        for (Long id : words) {
            if (core.size() >= 2 || core.size() == words.size()) break;
            if (!core.contains(id)) core.add(id);
        }
        core.sort(Comparator.comparingInt(words::indexOf));
        return new InteractiveContentSnapshot(snapshot.lesson(), snapshot.revision(), core, snapshot.kanjiIds(),
                snapshot.grammarPointIds(), snapshot.dialogueLineIds(), snapshot.gaps(), snapshot.vocabularyContexts(),
                snapshot.readingQuestionIds(), snapshot.listeningQuestionIds());
    }

    private record LessonWorkPlan(ScheduledLearningResource seed, InteractiveContentSnapshot snapshot,
            List<LearningDailyWorkloadPacker.PackageSegment> segments, boolean firstContinuesDay) { }
}
