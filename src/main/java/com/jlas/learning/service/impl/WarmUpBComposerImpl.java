package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.repository.GrammarPointRepository;
import com.jlas.dictionary.repository.KanjiRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
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
    private static final int MINUTES_PER_VOCAB_ITEM = 1;
    private static final int MINUTES_PER_KANJI_ITEM = 1;
    private static final int MINUTES_PER_GRAMMAR_POINT = 5;
    private static final int MINUTES_PER_COMPREHENSION_EXERCISE = 15;
    private static final int MAX_COMPREHENSION_EXERCISES_PER_DAY = 2;

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

    @Override
    @Transactional
    public InteractiveCompositionResult composeWarmUp(UserLearningPath path, LearningProfileSnapshot profile) {
        if (!properties.isEnabled()) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Learning Path V2 chưa được bật.");
        }
        if (path.getExperienceVersion() != LearningExperienceVersion.INTERACTIVE_V2) {
            throw new ApiException(ErrorCode.CONFLICT, "Warm-up B composer chỉ nhận path INTERACTIVE_V2.");
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
        List<Long> vocabIds = resolveVocabulary(weaknesses, assessedRank, gaps);
        List<Long> kanjiIds = resolveKanji(weaknesses, assessedRank, gaps);
        List<Long> grammarIds = resolveGrammar(weaknesses, assessedRank, gaps);
        boolean hasSkillSnapshot = profile.skillScores() != null && !profile.skillScores().isEmpty();
        List<ComprehensionExercise> reading = hasSkillSnapshot
                ? resolveComprehension("READING", profile, gaps) : List.of();
        List<ComprehensionExercise> listening = hasSkillSnapshot
                ? resolveComprehension("LISTENING", profile, gaps) : List.of();

        if (vocabIds.isEmpty() && kanjiIds.isEmpty() && grammarIds.isEmpty()
                && reading.isEmpty() && listening.isEmpty()) return new InteractiveCompositionResult(true, 0, gaps);

        Long stageId = stages.findByLearningPathIdOrderByStageIndexAsc(path.getId()).stream()
                .filter(stage -> !"FOUNDATION".equals(stage.getLevel()))
                .findFirst().map(UserLearningStage::getId).orElse(null);

        int dayNumber = items.findMaxDayNumberByLearningPathId(path.getId()) + 1;
        // [JLAS-W0193] Two independent, path-wide numbering spaces, each with its own unique constraint
        // (uk_learning_package_path_order, uk_learning_path_item_order) — package order_index and activity
        // (item) order_index must NEVER be conflated, and neither may reset per package: every package this
        // call creates continues both sequences from where the path already left off.
        int packageOrder = packages.findByLearningPathIdOrderByOrderIndex(path.getId()).stream()
                .mapToInt(LearningPathPackage::getOrderIndex).max().orElse(0);
        int itemOrder = items.findMaxOrderIndexByLearningPathId(path.getId());
        boolean isFirstPackageOverall = !packages.existsByLearningPathId(path.getId());
        int packagesCreated = 0;

        // Day 1: everything that fits without exceeding the per-day grammar cap.
        List<Long> firstDayGrammar = grammarIds.stream().limit(MAX_GRAMMAR_POINTS_PER_DAY).toList();
        List<List<Long>> overflowGrammarDays = chunk(
                grammarIds.size() > MAX_GRAMMAR_POINTS_PER_DAY
                        ? grammarIds.subList(MAX_GRAMMAR_POINTS_PER_DAY, grammarIds.size()) : List.of(),
                MAX_GRAMMAR_POINTS_PER_DAY);

        if (!vocabIds.isEmpty() || !kanjiIds.isEmpty() || !firstDayGrammar.isEmpty()) {
            var pkg = buildPackage(path, stageId, dayNumber, ++packageOrder,
                    warmUpTitle(!vocabIds.isEmpty(), !kanjiIds.isEmpty(), !firstDayGrammar.isEmpty(), false),
                    estimateMinutes(vocabIds.size(), kanjiIds.size(), firstDayGrammar.size()),
                    isFirstPackageOverall);
            if (!vocabIds.isEmpty()) {
                itemOrder = saveActivity(path, pkg, LearningActivityType.VOCABULARY, LearningMemberType.VOCABULARY_ENTRY,
                        vocabIds, revision, itemOrder, "WEAK_VOCAB_ITEM",
                        reasonText("từ vựng", vocabIds.size()), dayNumber);
            }
            if (!kanjiIds.isEmpty()) {
                itemOrder = saveActivity(path, pkg, LearningActivityType.KANJI, LearningMemberType.KANJI,
                        kanjiIds, revision, itemOrder, "WEAK_KANJI_ITEM",
                        reasonText("chữ Hán", kanjiIds.size()), dayNumber);
            }
            if (!firstDayGrammar.isEmpty()) {
                itemOrder = saveActivity(path, pkg, LearningActivityType.GRAMMAR, LearningMemberType.GRAMMAR_POINT,
                        firstDayGrammar, revision, itemOrder, "WEAK_GRAMMAR_POINT",
                        reasonText("mẫu ngữ pháp", firstDayGrammar.size()), dayNumber);
            }
            packagesCreated++;
        }

        for (List<Long> overflowDay : overflowGrammarDays) {
            dayNumber++;
            var pkg = buildPackage(path, stageId, dayNumber, ++packageOrder,
                    warmUpTitle(false, false, true, true), estimateMinutes(0, 0, overflowDay.size()), false);
            itemOrder = saveActivity(path, pkg, LearningActivityType.GRAMMAR, LearningMemberType.GRAMMAR_POINT,
                    overflowDay, revision, itemOrder, "WEAK_GRAMMAR_POINT", reasonText("mẫu ngữ pháp", overflowDay.size()), dayNumber);
            packagesCreated++;
        }

        List<ComprehensionWarmUpPlanner.DayAllocation> comprehensionDays =
                ComprehensionWarmUpPlanner.packIntoDays(reading.size(), listening.size(),
                        MAX_COMPREHENSION_EXERCISES_PER_DAY,
                        profile.minutesPerDay() == null ? 0 : profile.minutesPerDay(),
                        MINUTES_PER_COMPREHENSION_EXERCISE);
        int readingOffset = 0;
        int listeningOffset = 0;
        int comprehensionDay = packagesCreated == 0 ? dayNumber : dayNumber + 1;
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
        if (band.lessonCount() == 0) return List.of();

        var resolved = comprehensionCatalog.resolve(skill, profile.assessedLevel());
        resolved.gaps().forEach(gap -> gaps.add(new CoverageGap(skill + ":" + gap.canonicalRef(), gap.reasonCode())));
        int selectedCount = Math.min(band.lessonCount(), resolved.exercises().size());
        if (selectedCount < band.lessonCount()) {
            gaps.add(new CoverageGap(skill + ":ready=" + selectedCount + ":required=" + band.lessonCount(),
                    "CONTENT_COVERAGE_INSUFFICIENT"));
        }
        return List.copyOf(resolved.exercises().subList(0, selectedCount));
    }

    private List<Long> resolveVocabulary(List<WeaknessEvidence> weaknesses, int assessedRank, List<CoverageGap> gaps) {
        Map<Long, Boolean> resolved = new LinkedHashMap<>();
        for (WeaknessEvidence evidence : weaknesses) {
            if (!WeaknessEvidence.SKILL_VOCABULARY.equals(evidence.skill())) continue;
            if (!weakTagPolicy.isWeak(evidence)) continue;
            if (!levelInBound(evidence.jlptLevel(), assessedRank)) continue;
            Long vocabularyEntryId = evidence.vocabularyEntryId();
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
            resolved.put(vocabularyEntryId, true);
        }
        return resolved.keySet().stream().sorted().toList();
    }

    private List<Long> resolveKanji(List<WeaknessEvidence> weaknesses, int assessedRank, List<CoverageGap> gaps) {
        Map<Long, Boolean> resolved = new LinkedHashMap<>();
        for (WeaknessEvidence evidence : weaknesses) {
            if (!WeaknessEvidence.SKILL_KANJI.equals(evidence.skill())) continue;
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
            resolved.putIfAbsent(kanji.get().getId(), true);
        }
        return resolved.keySet().stream().sorted().toList();
    }

    private List<Long> resolveGrammar(List<WeaknessEvidence> weaknesses, int assessedRank, List<CoverageGap> gaps) {
        Map<Long, Boolean> resolved = new LinkedHashMap<>();
        for (WeaknessEvidence evidence : weaknesses) {
            if (!WeaknessEvidence.SKILL_GRAMMAR.equals(evidence.skill())) continue;
            if (!weakTagPolicy.isWeak(evidence)) continue;
            if (!levelInBound(evidence.jlptLevel(), assessedRank)) continue;
            Long grammarPointId = evidence.grammarPointId();
            if (grammarPointId == null || resolved.containsKey(grammarPointId)) continue;
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
            LearningMemberType memberType, List<Long> ids, String revision, int globalOrder,
            String reasonCode, String reasonText, int dayNumber) {
        int next = globalOrder + 1;
        var activity = items.save(UserLearningPathItem.builder()
                .learningPath(path)
                .learningPackage(pkg)
                .orderIndex(next)
                .phase("WARM_UP")
                .dayNumber(dayNumber)
                .estimatedMinutes(pkg.getEstimatedMinutes())
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

    private int estimateMinutes(int vocabCount, int kanjiCount, int grammarCount) {
        return vocabCount * MINUTES_PER_VOCAB_ITEM + kanjiCount * MINUTES_PER_KANJI_ITEM
                + grammarCount * MINUTES_PER_GRAMMAR_POINT;
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
