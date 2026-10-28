package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.repository.GrammarPointRepository;
import com.jlas.dictionary.repository.KanjiRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.dictionary.util.DictionaryUtil;
import com.jlas.learning.config.InteractiveLearningV2Properties;
import com.jlas.learning.dto.CoverageGap;
import com.jlas.learning.dto.ComprehensionExercise;
import com.jlas.learning.dto.InteractiveCompositionResult;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.WeaknessEvidence;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.entity.UserLearningStage;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.enums.LearningPackageStatus;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningPathPackageRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningStageRepository;
import com.jlas.learning.service.WarmUpBComposer;
import com.jlas.learning.service.ComprehensionExerciseCatalog;
import com.jlas.learning.service.impl.engine.ComprehensionRevision;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * [JLAS-W0193] Task 9 — Warm-up B: converts PROVEN Smart Test weakness evidence
 * ({@link WeaknessEvidence}, read through the existing {@code LearningProfileSource}, never a new query
 * against Smart Test's own tables) into item-scoped V2 activities. Vocabulary/Kanji/Grammar reuse the
 * exact same {@code learning_activity_member} membership shape and the Task 4/5/6 study/micro-check
 * engine unchanged — this class only decides WHICH canonical ids belong to a member, never how they are
 * studied or checked. This is deliberately independent of {@link InteractivePackageComposer} (which
 * builds GOAL_LEARNING packages from whole published lessons): warm-up must never schedule a whole
 * lesson for one wrong answer (rules doc "Interactive Learning Path V2 composition").
 *
 * <p>Reading/Listening use the same finalized profile snapshot, but resolve playable exact-level exercises
 * through {@link ComprehensionExerciseCatalog}. The composer stores question IDs and a combined immutable
 * revision; it never copies the canonical question bank or accepts a client-selected band.</p>
 */
@Service
@RequiredArgsConstructor
public class WarmUpBComposerImpl implements WarmUpBComposer {
    /** [JLAS-W0108] Same JLPT ladder every learning-selection class keeps its own private copy of. */
    private static final List<String> JLPT_LEVELS = List.of("N5", "N4", "N3", "N2", "N1");
    /** Design spec §5.3 — at most three grammar points scheduled into a single warm-up day. */
    private static final int MAX_GRAMMAR_POINTS_PER_DAY = 3;
    private static final String UNMAPPED_QUESTION_PREFIX = "UNMAPPED_QUESTION:";
    /** Review-only gap target for a wrong grammar question with no grammar lesson; read by the roadmap. */
    public static final String GRAMMAR_QUESTION_PREFIX = "GRAMMAR_QUESTION:";
    private static final int MINUTES_PER_COMPREHENSION_EXERCISE = 15;
    private static final int MAX_COMPREHENSION_EXERCISES_PER_DAY = 2;
    /** Smart Test item remediation is useful as a full study/quiz activity only from five mapped targets. */
    private static final int MINIMUM_FULL_ITEM_REMEDIATION_TARGETS = 5;

    private final InteractiveLearningV2Properties properties;
    private final LearningPathPackageRepository packages;
    private final UserLearningPathItemRepository items;
    private final LearningActivityMemberRepository members;
    private final UserLearningStageRepository stages;
    private final VocabularyEntryRepository vocabularyEntries;
    private final KanjiRepository kanjiRepo;
    private final GrammarPointRepository grammarPoints;
    private final WeakTagPolicy weakTagPolicy;
    private final ComprehensionExerciseCatalog comprehensionCatalog;
    private final LearningWorkloadPolicy workloadPolicy = new LearningWorkloadPolicy();
    private final LearningDailyWorkloadPacker workloadPacker = new LearningDailyWorkloadPacker();

    @Override
    @Transactional
    public InteractiveCompositionResult composeWarmUp(UserLearningPath path, LearningProfileSnapshot profile) {
        if (!properties.isEnabled()) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Learning Path V2 chưa được bật.");
        }
        if (path.getExperienceVersion() != LearningExperienceVersion.INTERACTIVE_V2) {
            throw new ApiException(ErrorCode.CONFLICT, "Warm-up B composer chỉ nhận path INTERACTIVE_V2.");
        }
        if (!LearningStartPolicy.needsMistakeWarmUp(profile)) {
            return new InteractiveCompositionResult(true, 0, List.of());
        }
        if (packages.existsByLearningPathIdAndPhase(path.getId(), "WARM_UP")) {
            int existing = (int) packages.findByLearningPathIdOrderByOrderIndex(path.getId()).stream()
                    .filter(p -> "WARM_UP".equals(p.getPhase())).count();
            return new InteractiveCompositionResult(true, existing, List.of());
        }
        if ((profile.weaknesses() == null || profile.weaknesses().isEmpty())
                && (profile.skillScores() == null || profile.skillScores().isEmpty())) {
            return new InteractiveCompositionResult(true, 0, List.of());
        }

        int assessedRank = rank(profile.assessedLevel());
        String revision = "warmup:" + (profile.assessmentRevision() == null ? "unknown" : profile.assessmentRevision());
        List<CoverageGap> gaps = new ArrayList<>();

        List<WeaknessEvidence> weaknesses = profile.weaknesses() == null ? List.of() : profile.weaknesses();
        List<Long> vocabIds = eligibleRemediationTargets(WeaknessEvidence.SKILL_VOCABULARY,
                resolveVocabulary(weaknesses, assessedRank, gaps), gaps);
        List<Long> kanjiIds = eligibleRemediationTargets(WeaknessEvidence.SKILL_KANJI,
                resolveKanji(weaknesses, assessedRank, gaps), gaps);
        List<Long> grammarIds = resolveGrammar(weaknesses, assessedRank, gaps);
        boolean hasSkillSnapshot = profile.skillScores() != null && !profile.skillScores().isEmpty();
        List<ComprehensionExercise> reading = hasSkillSnapshot
                ? resolveComprehension("READING", profile, gaps) : List.of();
        List<ComprehensionExercise> listening = hasSkillSnapshot
                ? resolveComprehension("LISTENING", profile, gaps) : List.of();

        int dailyBudget = path.getMinutesPerDay() != null ? path.getMinutesPerDay()
                : profile.minutesPerDay() == null ? 0 : profile.minutesPerDay();
        if (dailyBudget <= 0) {
            return new InteractiveCompositionResult(false, 0,
                    List.of(new CoverageGap("PATH:" + path.getId(), "MISSING_DAILY_WORKLOAD_BUDGET")));
        }
        if (dailyBudget < MINUTES_PER_COMPREHENSION_EXERCISE
                && (!reading.isEmpty() || !listening.isEmpty())) {
            gaps.add(new CoverageGap("PATH:" + path.getId(), "DAILY_BUDGET_TOO_SMALL_FOR_COMPREHENSION"));
            return new InteractiveCompositionResult(false, 0, gaps);
        }

        if (vocabIds.isEmpty() && kanjiIds.isEmpty() && grammarIds.isEmpty()
                && reading.isEmpty() && listening.isEmpty()) return new InteractiveCompositionResult(true, 0, gaps);

        Long stageId = stages.findByLearningPathIdOrderByStageIndexAsc(path.getId()).stream()
                .filter(stage -> !"FOUNDATION".equals(stage.getLevel()))
                .findFirst().map(UserLearningStage::getId).orElse(null);

        int dayNumber = items.findMaxDayNumberByLearningPathId(path.getId());
        // [JLAS-W0193] Two independent, path-wide numbering spaces, each with its own unique constraint
        // (uk_learning_package_path_order, uk_learning_path_item_order) — package order_index and activity
        // (item) order_index must NEVER be conflated, and neither may reset per package: every package this
        // call creates continues both sequences from where the path already left off.
        int packageOrder = packages.findByLearningPathIdOrderByOrderIndex(path.getId()).stream()
                .mapToInt(LearningPathPackage::getOrderIndex).max().orElse(0);
        int itemOrder = items.findMaxOrderIndexByLearningPathId(path.getId());
        boolean isFirstPackageOverall = !packages.existsByLearningPathId(path.getId());
        int packagesCreated = 0;

        List<LearningWorkloadPolicy.ActivityPlan> coreActivities = new ArrayList<>();
        addActivities(coreActivities, LearningActivityType.VOCABULARY, LearningMemberType.VOCABULARY_ENTRY,
                vocabIds, dailyBudget);
        addActivities(coreActivities, LearningActivityType.KANJI, LearningMemberType.KANJI,
                kanjiIds, dailyBudget);
        addGrammarActivities(coreActivities, grammarIds, dailyBudget);
        List<LearningDailyWorkloadPacker.PackageSegment> coreDays = workloadPacker.pack(coreActivities,
                dailyBudget, Map.of(LearningActivityType.GRAMMAR, MAX_GRAMMAR_POINTS_PER_DAY));
        for (var segment : coreDays) {
            dayNumber++;
            boolean vocabulary = segment.activities().stream().anyMatch(a -> a.activityType() == LearningActivityType.VOCABULARY);
            boolean kanji = segment.activities().stream().anyMatch(a -> a.activityType() == LearningActivityType.KANJI);
            boolean grammar = segment.activities().stream().anyMatch(a -> a.activityType() == LearningActivityType.GRAMMAR);
            boolean grammarContinuation = grammar && !vocabulary && !kanji && packagesCreated > 0;
            var pkg = buildPackage(path, stageId, dayNumber, ++packageOrder,
                    warmUpTitle(vocabulary, kanji, grammar, grammarContinuation),
                    segment.estimatedMinutes(), isFirstPackageOverall && packagesCreated == 0);
            // uk(package, activity_type, part_index): same-type parts of one package are numbered 1..n.
            Map<LearningActivityType, Integer> parts = new java.util.EnumMap<>(LearningActivityType.class);
            for (var activity : segment.activities()) {
                itemOrder = saveActivity(path, pkg, activity.activityType(), activity.memberType(),
                        activity.memberIds(), revision, activity.estimatedMinutes(), itemOrder,
                        reasonCode(activity.activityType()), reasonText(reasonLabel(activity.activityType()),
                                activity.memberIds().size()), dayNumber, parts.merge(activity.activityType(), 1, Integer::sum));
            }
            packagesCreated++;
        }

        List<ComprehensionWarmUpPlanner.DayAllocation> comprehensionDays =
                ComprehensionWarmUpPlanner.packIntoDays(reading.size(), listening.size(),
                        MAX_COMPREHENSION_EXERCISES_PER_DAY,
                        dailyBudget,
                        MINUTES_PER_COMPREHENSION_EXERCISE);
        int readingOffset = 0;
        int listeningOffset = 0;
        int comprehensionDay = dayNumber + 1;
        for (var allocation : comprehensionDays) {
            List<ComprehensionExercise> readingForDay = reading.subList(readingOffset,
                    readingOffset + allocation.readingCount());
            List<ComprehensionExercise> listeningForDay = listening.subList(listeningOffset,
                    listeningOffset + allocation.listeningCount());
            readingOffset += allocation.readingCount();
            listeningOffset += allocation.listeningCount();
            int currentDay = comprehensionDay++;
            int minutes = allocation.total() * MINUTES_PER_COMPREHENSION_EXERCISE;
            var pkg = buildPackage(path, stageId, currentDay, ++packageOrder,
                    comprehensionTitle(readingForDay, listeningForDay), minutes,
                    isFirstPackageOverall && packagesCreated == 0);
            if (!readingForDay.isEmpty()) {
                itemOrder = saveComprehensionActivity(path, pkg, LearningActivityType.READING,
                        readingForDay, itemOrder, currentDay, profile.assessedLevel());
            }
            if (!listeningForDay.isEmpty()) {
                itemOrder = saveComprehensionActivity(path, pkg, LearningActivityType.LISTENING,
                        listeningForDay, itemOrder, currentDay, profile.assessedLevel());
            }
            packagesCreated++;
        }

        return new InteractiveCompositionResult(true, packagesCreated, gaps);
    }

    private List<ComprehensionExercise> resolveComprehension(String skill, LearningProfileSnapshot profile,
            List<CoverageGap> gaps) {
        if (profile.weaknesses() != null) {
            profile.weaknesses().stream().filter(evidence -> skill.equals(evidence.skill()))
                    .flatMap(evidence -> evidence.outcomeBreakdown().stream())
                    .filter(outcome -> outcome.outcomeCode().startsWith("UNMAPPED_QUESTION:"))
                    .forEach(outcome -> gaps.add(new CoverageGap(skill + ":" + outcome.outcomeCode(),
                            "CONTENT_MAPPING_PENDING")));
        }
        var score = profile.skillScores() == null ? null : profile.skillScores().stream()
                .filter(value -> skill.equals(value.skill())).findFirst().orElse(null);
        if (score == null || score.assessedCount() <= 0) {
            gaps.add(new CoverageGap(skill, "NEEDS_VERIFICATION"));
            return List.of();
        }
        var band = ComprehensionWarmUpPlanner.band(score);
        if (band.needsVerification()) {
            gaps.add(new CoverageGap(skill, "NEEDS_VERIFICATION"));
            return List.of();
        }
        List<String> wrongOutcomes = profile.weaknesses() == null ? List.of()
                : profile.weaknesses().stream().filter(evidence -> skill.equals(evidence.skill()))
                        .flatMap(evidence -> evidence.outcomeBreakdown().stream())
                        .filter(outcome -> outcome.wrongCount() > 0
                                && !outcome.outcomeCode().startsWith("UNMAPPED_QUESTION:"))
                        .map(WeaknessEvidence.OutcomeBreakdown::outcomeCode).distinct().sorted().toList();
        if (band.lessonCount() == 0 && wrongOutcomes.isEmpty()) return List.of();

        var resolved = comprehensionCatalog.resolve(skill, profile.assessedLevel());
        resolved.gaps().forEach(gap -> gaps.add(new CoverageGap(skill + ":" + gap.canonicalRef(), gap.reasonCode())));
        Map<String, ComprehensionExercise> selected = new LinkedHashMap<>();
        for (String outcome : wrongOutcomes) {
            resolved.exercises().stream().filter(exercise -> outcome.equals(exercise.outcomeCode())).findFirst()
                    .ifPresentOrElse(exercise -> selected.putIfAbsent(exercise.fingerprint(), exercise),
                            () -> gaps.add(new CoverageGap(skill + ":OUTCOME:" + outcome,
                                    "CONTENT_COVERAGE_INSUFFICIENT")));
        }
        int targetCount = Math.max(band.lessonCount(), selected.size());
        for (ComprehensionExercise exercise : resolved.exercises()) {
            if (selected.size() >= targetCount) break;
            // Review 2026-10-20 / OD-08 / D3-04: chapter-bound material is studied in its chapter, never as filler.
            if (exercise.chapterBound()) continue;
            selected.putIfAbsent(exercise.fingerprint(), exercise);
        }
        if (selected.size() < band.lessonCount()) {
            gaps.add(new CoverageGap(skill + ":ready=" + selected.size() + ":required=" + band.lessonCount(),
                    "CONTENT_COVERAGE_INSUFFICIENT"));
        }
        return List.copyOf(selected.values());
    }

    private ResolvedRemediationTargets resolveVocabulary(List<WeaknessEvidence> weaknesses, int assessedRank,
            List<CoverageGap> gaps) {
        Map<Long, String> resolved = new LinkedHashMap<>();
        for (WeaknessEvidence evidence : orderedItemEvidence(weaknesses, WeaknessEvidence.SKILL_VOCABULARY)) {
            if (!weakTagPolicy.isWeak(evidence)) continue;
            if (!levelInBound(evidence.jlptLevel(), assessedRank)) continue;
            Long vocabularyEntryId = evidence.vocabularyEntryId();
            if (vocabularyEntryId == null) {
                String expression = canonicalExpression(evidence);
                if (expression != null) {
                    List<com.jlas.dictionary.entity.VocabularyEntry> exactMatches = vocabularyEntries
                            .findByNormalizedExpressionAndDictionaryStatusOrderByIdAsc(
                                    DictionaryUtil.normalizeReading(expression), "PUBLISHED");
                    // The same spelling may have different readings/meanings. Without a stable question FK,
                    // only a single exact published match is safe; ambiguity remains a mapping gap.
                    if (exactMatches.size() == 1) vocabularyEntryId = exactMatches.getFirst().getId();
                }
            }
            if (vocabularyEntryId == null || resolved.containsKey(vocabularyEntryId)) {
                if (vocabularyEntryId == null) gaps.add(new CoverageGap(evidence.canonicalId(), "CONTENT_MAPPING_PENDING"));
                continue;
            }
            boolean exists = vocabularyEntries.findById(vocabularyEntryId)
                    .map(entry -> "PUBLISHED".equals(entry.getDictionaryStatus())).orElse(false);
            if (!exists) {
                gaps.add(new CoverageGap(evidence.canonicalId(), "CONTENT_MAPPING_PENDING"));
                continue;
            }
            resolved.put(vocabularyEntryId, evidence.canonicalId());
        }
        return new ResolvedRemediationTargets(resolved);
    }

    /** Resolve only the exact vocabulary expression preserved in Smart Test's canonical evidence. */
    private String canonicalExpression(WeaknessEvidence evidence) {
        String prefix = WeaknessEvidence.SKILL_VOCABULARY + ":";
        if (evidence.canonicalId() == null || !evidence.canonicalId().startsWith(prefix)) return null;
        String expression = evidence.canonicalId().substring(prefix.length()).strip();
        return expression.isEmpty() || expression.startsWith("UNMAPPED_QUESTION:") ? null : expression;
    }

    private ResolvedRemediationTargets resolveKanji(List<WeaknessEvidence> weaknesses, int assessedRank,
            List<CoverageGap> gaps) {
        Map<Long, String> resolved = new LinkedHashMap<>();
        for (WeaknessEvidence evidence : orderedItemEvidence(weaknesses, WeaknessEvidence.SKILL_KANJI)) {
            if (!weakTagPolicy.isWeak(evidence)) continue;
            if (!levelInBound(evidence.jlptLevel(), assessedRank)) continue;
            String lexeme = lexemeOf(evidence);
            if (lexeme == null || lexeme.isBlank()) {
                gaps.add(new CoverageGap(evidence.canonicalId(), "CONTENT_MAPPING_PENDING"));
                continue;
            }
            var kanji = kanjiRepo.findByCharacter(lexeme);
            if (kanji.isEmpty()) {
                gaps.add(new CoverageGap(evidence.canonicalId(), "CONTENT_MAPPING_PENDING"));
                continue;
            }
            resolved.putIfAbsent(kanji.get().getId(), evidence.canonicalId());
        }
        return new ResolvedRemediationTargets(resolved);
    }

    private List<WeaknessEvidence> orderedItemEvidence(List<WeaknessEvidence> weaknesses, String skill) {
        return weaknesses.stream().filter(evidence -> skill.equals(evidence.skill()))
                .sorted(java.util.Comparator.comparing(WeaknessEvidence::canonicalId,
                        java.util.Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    /**
     * Count only distinct canonical targets that resolved to published learning content. A small set is
     * retained as explicit review-only targets in the existing path coverage snapshot, never as an activity
     * (which would implicitly unlock Flashcards/quiz, progress, mastery, or quota).
     */
    private List<Long> eligibleRemediationTargets(String skill, ResolvedRemediationTargets resolved,
            List<CoverageGap> gaps) {
        List<Long> ids = resolved.canonicalByTarget().keySet().stream().sorted().toList();
        if (ids.size() >= MINIMUM_FULL_ITEM_REMEDIATION_TARGETS || ids.isEmpty()) return ids;
        ids.stream().map(resolved.canonicalByTarget()::get).filter(java.util.Objects::nonNull).sorted()
                .forEach(canonicalId -> gaps.add(new CoverageGap(canonicalId,
                        "SMART_TEST_REVIEW_ONLY_BELOW_THRESHOLD")));
        return List.of();
    }

    private record ResolvedRemediationTargets(Map<Long, String> canonicalByTarget) {
        private ResolvedRemediationTargets {
            canonicalByTarget = Map.copyOf(canonicalByTarget);
        }
    }

    private List<Long> resolveGrammar(List<WeaknessEvidence> weaknesses, int assessedRank, List<CoverageGap> gaps) {
        Map<Long, Boolean> resolved = new LinkedHashMap<>();
        for (WeaknessEvidence evidence : weaknesses) {
            if (!WeaknessEvidence.SKILL_GRAMMAR.equals(evidence.skill())) continue;
            // Owner decision 2026-10-04: a wrong answer warrants review even when
            // the aggregate does not justify labelling the whole grammar point weak.
            if (evidence.wrongCount() <= 0) continue;
            Long grammarPointId = evidence.grammarPointId();
            if (grammarPointId == null) {
                gaps.add(new CoverageGap(evidence.canonicalId(), "CONTENT_MAPPING_PENDING"));
                // UAT 2026-10-06: no grammar lesson to teach yet, but the mistake stays visible for review.
                String canonicalId = evidence.canonicalId();
                if (canonicalId != null && canonicalId.startsWith(UNMAPPED_QUESTION_PREFIX)) {
                    gaps.add(new CoverageGap(GRAMMAR_QUESTION_PREFIX
                            + canonicalId.substring(UNMAPPED_QUESTION_PREFIX.length()),
                            "SMART_TEST_REVIEW_ONLY_UNMAPPED_GRAMMAR"));
                }
                continue;
            }
            // A mapped point above the assessed level belongs to future goal learning, not warm-up.
            // Unmapped questions were handled first so their review evidence is never lost.
            if (!levelInBound(evidence.jlptLevel(), assessedRank)) continue;
            if (resolved.containsKey(grammarPointId)) continue;
            if (grammarPoints.findById(grammarPointId).isEmpty()) {
                gaps.add(new CoverageGap(evidence.canonicalId(), "CONTENT_MAPPING_PENDING"));
                continue;
            }
            resolved.put(grammarPointId, true);
        }
        return resolved.keySet().stream().sorted().toList();
    }

    /** KANJI evidence's {@code canonicalId} is {@code "KANJI:<lexeme>"}; the lexeme is only a resolvable
     * single-character anchor when the Smart Test question tagged exactly one kanji. A multi-character
     * lexeme (a whole word) cannot resolve to one {@code kanji.id} and is reported as a gap, never guessed. */
    private String lexemeOf(WeaknessEvidence evidence) {
        String prefix = evidence.skill() + ":";
        if (evidence.canonicalId() == null || !evidence.canonicalId().startsWith(prefix)) return null;
        return evidence.canonicalId().substring(prefix.length());
    }

    /** [JLAS-W0108] Warm-up chỉ nhận item ở hoặc dưới trình độ đã kết luận (rules doc §2.2) — item sai ở
     * cấp cao hơn là "chưa từng học", thuộc phạm vi GOAL, không phải warm-up. A null level or an
     * unresolvable assessed rank fails open (does not exclude), matching {@code LearningResourceSelectionServiceImpl}'s
     * own null handling for the same rule. */
    private boolean levelInBound(String itemLevel, int assessedRank) {
        if (itemLevel == null || assessedRank < 0) return true;
        return rank(itemLevel) <= assessedRank;
    }

    private int rank(String level) {
        return JLPT_LEVELS.indexOf(level);
    }

    private List<List<Long>> chunk(List<Long> ids, int size) {
        List<List<Long>> result = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += size) {
            result.add(ids.subList(i, Math.min(i + size, ids.size())));
        }
        return result;
    }

    private void addActivities(List<LearningWorkloadPolicy.ActivityPlan> output, LearningActivityType type,
            LearningMemberType memberType, List<Long> ids, int dailyBudget) {
        for (List<Long> group : workloadPolicy.partition(type, ids, dailyBudget)) {
            output.add(new LearningWorkloadPolicy.ActivityPlan(type, memberType, group,
                    workloadPolicy.estimateMinutes(type, group.size())));
        }
    }

    private void addGrammarActivities(List<LearningWorkloadPolicy.ActivityPlan> output,
            List<Long> grammarIds, int dailyBudget) {
        for (List<Long> budgetGroup : workloadPolicy.partition(LearningActivityType.GRAMMAR, grammarIds, dailyBudget)) {
            for (List<Long> dailyGroup : chunk(budgetGroup, MAX_GRAMMAR_POINTS_PER_DAY)) {
                output.add(new LearningWorkloadPolicy.ActivityPlan(LearningActivityType.GRAMMAR,
                        LearningMemberType.GRAMMAR_POINT, dailyGroup,
                        workloadPolicy.estimateMinutes(LearningActivityType.GRAMMAR, dailyGroup.size())));
            }
        }
    }

    private LearningPathPackage buildPackage(UserLearningPath path, Long stageId, int dayNumber, int orderIndex,
            String title, int estimatedMinutes, boolean available) {
        return packages.save(LearningPathPackage.builder()
                .learningPath(path)
                .sourceLesson(null)
                .contentRevision(null)
                .stageId(stageId)
                .phase("WARM_UP")
                .dayNumber(dayNumber)
                .orderIndex(orderIndex)
                .title(title)
                .status(available ? LearningPackageStatus.AVAILABLE : LearningPackageStatus.LOCKED)
                .estimatedMinutes(Math.max(1, estimatedMinutes))
                .build());
    }

    private int saveActivity(UserLearningPath path, LearningPathPackage pkg, LearningActivityType type,
            LearningMemberType memberType, List<Long> ids, String revision, int estimatedMinutes, int globalOrder,
            String reasonCode, String reasonText, int dayNumber, int partIndex) {
        int next = globalOrder + 1;
        var activity = items.save(UserLearningPathItem.builder()
                .learningPath(path)
                .learningPackage(pkg)
                .orderIndex(next)
                .partIndex(partIndex)
                .phase("WARM_UP")
                .dayNumber(dayNumber)
                .estimatedMinutes(estimatedMinutes)
                .activityType(type)
                .activityState(LearningActivityState.LOCKED)
                .reasonCode(reasonCode)
                .reason(reasonText)
                .build());
        List<LearningActivityMember> rows = new ArrayList<>();
        int index = 0;
        for (Long id : new LinkedHashSet<>(ids)) {
            rows.add(LearningActivityMember.builder().activity(activity).memberType(memberType)
                    .canonicalId(id).orderIndex(++index).sourceRevision(revision).build());
        }
        members.saveAll(rows);
        return next;
    }

    private int saveComprehensionActivity(UserLearningPath path, LearningPathPackage pkg,
            LearningActivityType type, List<ComprehensionExercise> exercises, int globalOrder,
            int dayNumber, String level) {
        int next = globalOrder + 1;
        String revision = combinedRevision(type, exercises);
        int questionCount = exercises.stream().mapToInt(exercise -> exercise.questions().size()).sum();
        var activity = items.save(UserLearningPathItem.builder()
                .learningPath(path).learningPackage(pkg).orderIndex(next).phase("WARM_UP")
                .dayNumber(dayNumber).estimatedMinutes(exercises.size() * MINUTES_PER_COMPREHENSION_EXERCISE)
                .activityType(type).activityState(LearningActivityState.LOCKED)
                .reasonCode("WEAK_" + type.name() + "_SKILL")
                .reason("Smart Test cho thấy kỹ năng " + type.name() + " cần củng cố. "
                        + "Đã chọn " + exercises.size() + " bài luyện đúng cấp " + level
                        + " với " + questionCount + " câu hỏi.")
                .build());
        List<LearningActivityMember> rows = new ArrayList<>();
        int order = 0;
        for (ComprehensionExercise exercise : exercises) {
            for (var question : exercise.questions()) {
                rows.add(LearningActivityMember.builder().activity(activity)
                        .memberType(LearningMemberType.COMPREHENSION_QUESTION)
                        .canonicalId(question.id()).orderIndex(++order).sourceRevision(revision).build());
            }
        }
        members.saveAll(rows);
        return next;
    }

    private String combinedRevision(LearningActivityType type, List<ComprehensionExercise> exercises) {
        return ComprehensionRevision.combined(type, exercises);
    }

    private String comprehensionTitle(List<ComprehensionExercise> reading,
            List<ComprehensionExercise> listening) {
        if (!reading.isEmpty() && !listening.isEmpty()) return "Luyện đọc và nghe theo điểm yếu";
        return reading.isEmpty() ? "Luyện nghe theo điểm yếu" : "Luyện đọc theo điểm yếu";
    }

    private String reasonCode(LearningActivityType type) {
        return switch (type) {
            case VOCABULARY -> "WEAK_VOCAB_ITEM";
            case KANJI -> "WEAK_KANJI_ITEM";
            case GRAMMAR -> "WEAK_GRAMMAR_POINT";
            default -> throw new IllegalArgumentException("Unsupported item-remediation activity: " + type);
        };
    }

    private String reasonLabel(LearningActivityType type) {
        return switch (type) {
            case VOCABULARY -> "từ vựng";
            case KANJI -> "chữ Hán";
            case GRAMMAR -> "mẫu ngữ pháp";
            default -> throw new IllegalArgumentException("Unsupported item-remediation activity: " + type);
        };
    }
    private String reasonText(String label, int count) {
        return "Bạn gặp khó khăn với " + count + " " + label + " này trong Smart Test. "
                + "Hãy ôn lại và kiểm tra ngay sau đó.";
    }

    private String warmUpTitle(boolean vocab, boolean kanji, boolean grammar, boolean overflow) {
        if (overflow) return "Ôn tập ngữ pháp đã sai (tiếp)";
        List<String> parts = new ArrayList<>();
        if (vocab) parts.add("từ vựng");
        if (kanji) parts.add("Kanji");
        if (grammar) parts.add("ngữ pháp");
        return "Ôn tập điểm yếu: " + String.join(", ", parts);
    }
}
