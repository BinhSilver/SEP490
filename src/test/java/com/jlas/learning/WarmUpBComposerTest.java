package com.jlas.learning;

import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.Kanji;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.repository.GrammarPointRepository;
import com.jlas.dictionary.repository.KanjiRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.learning.config.InteractiveLearningV2Properties;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.ComprehensionExercise;
import com.jlas.learning.dto.ComprehensionQuestionSpec;
import com.jlas.learning.dto.SkillScore;
import com.jlas.learning.dto.WeaknessEvidence;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningPackageStatus;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningPathPackageRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningStageRepository;
import com.jlas.learning.service.ComprehensionExerciseCatalog;
import com.jlas.learning.service.impl.WarmUpBComposerImpl;
import com.jlas.learning.service.impl.WeakTagPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** [JLAS-W0193] Task 9 — Warm-up B composition: converts proven Smart Test weakness evidence into
 * item-scoped V2 activities, never a whole lesson. */
@ExtendWith(MockitoExtension.class)
class WarmUpBComposerTest {
    @Mock LearningPathPackageRepository packages;
    @Mock UserLearningPathItemRepository items;
    @Mock LearningActivityMemberRepository members;
    @Mock UserLearningStageRepository stages;
    @Mock VocabularyEntryRepository vocabularyEntries;
    @Mock KanjiRepository kanjiRepo;
    @Mock GrammarPointRepository grammarPoints;
    @Mock ComprehensionExerciseCatalog comprehensionCatalog;

    InteractiveLearningV2Properties properties;
    WeakTagPolicy weakTagPolicy;
    WarmUpBComposerImpl composer;

    @BeforeEach
    void setUp() {
        properties = new InteractiveLearningV2Properties();
        properties.setEnabled(true);
        weakTagPolicy = new WeakTagPolicy(1, new BigDecimal("0.5"));
        composer = new WarmUpBComposerImpl(properties, packages, items, members, stages,
                vocabularyEntries, kanjiRepo, grammarPoints, weakTagPolicy, comprehensionCatalog);
        lenient().when(packages.existsByLearningPathIdAndPhase(anyLong(), eq("WARM_UP"))).thenReturn(false);
        lenient().when(packages.existsByLearningPathId(anyLong())).thenReturn(false);
        lenient().when(packages.findByLearningPathIdOrderByOrderIndex(anyLong())).thenReturn(List.of());
        lenient().when(items.findMaxOrderIndexByLearningPathId(anyLong())).thenReturn(0);
        lenient().when(items.findMaxDayNumberByLearningPathId(anyLong())).thenReturn(0);
        lenient().when(stages.findByLearningPathIdOrderByStageIndexAsc(anyLong())).thenReturn(List.of());
        lenient().when(packages.save(any())).thenAnswer(i -> {
            var p = i.getArgument(0, LearningPathPackage.class);
            p.setId((long) p.getOrderIndex());
            return p;
        });
        lenient().when(items.save(any())).thenAnswer(i -> {
            var item = i.getArgument(0, UserLearningPathItem.class);
            item.setId((long) item.getOrderIndex());
            return item;
        });
    }

    private UserLearningPath path() {
        return UserLearningPath.builder().id(5L).user(User.builder().id(7L).build())
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).title("P").build();
    }

    private LearningProfileSnapshot profile(String assessedLevel, List<WeaknessEvidence> weaknesses) {
        return new LearningProfileSnapshot(7L, 99L, 1L, "rev-1", true, true, assessedLevel, "N3", "JLPT", 30, weaknesses);
    }

    private LearningProfileSnapshot profile(String assessedLevel, List<WeaknessEvidence> weaknesses,
            List<SkillScore> skillScores, int minutesPerDay) {
        return new LearningProfileSnapshot(7L, 99L, 1L, "rev-1", true, true, assessedLevel, "N3", "JLPT",
                minutesPerDay, weaknesses, skillScores);
    }

    @Test void readingBandIsPackedAsAtMostTwoExercisesPerDay() {
        var ready = List.of(exercise("READING", 1), exercise("READING", 2),
                exercise("READING", 3), exercise("READING", 4));
        when(comprehensionCatalog.resolve("READING", "N4"))
                .thenReturn(new ComprehensionExerciseCatalog.CatalogResult(ready, List.of()));

        var result = composer.composeWarmUp(path(), profile("N4", List.of(),
                List.of(new SkillScore("READING", 10, 4)), 30));

        assertEquals(2, result.packageCount());
        var activities = ArgumentCaptor.forClass(UserLearningPathItem.class);
        verify(items, times(2)).save(activities.capture());
        assertTrue(activities.getAllValues().stream().allMatch(a -> a.getActivityType() == LearningActivityType.READING));
        assertTrue(activities.getAllValues().stream().allMatch(a -> a.getEstimatedMinutes() == 30));
        var saved = ArgumentCaptor.forClass(List.class);
        verify(members, times(2)).saveAll(saved.capture());
        assertTrue(saved.getAllValues().stream().allMatch(rows -> rows.size() == 4));
    }

    @Test void readingAndListeningAreInterleavedWithinCombinedDailyCap() {
        when(comprehensionCatalog.resolve(eq("READING"), eq("N4"))).thenReturn(
                new ComprehensionExerciseCatalog.CatalogResult(List.of(exercise("READING", 1), exercise("READING", 2),
                        exercise("READING", 3), exercise("READING", 4)), List.of()));
        when(comprehensionCatalog.resolve(eq("LISTENING"), eq("N4"))).thenReturn(
                new ComprehensionExerciseCatalog.CatalogResult(List.of(exercise("LISTENING", 11), exercise("LISTENING", 12),
                        exercise("LISTENING", 13), exercise("LISTENING", 14)), List.of()));

        var result = composer.composeWarmUp(path(), profile("N4", List.of(), List.of(
                new SkillScore("READING", 10, 4), new SkillScore("LISTENING", 10, 4)), 30));

        assertEquals(4, result.packageCount());
        var activities = ArgumentCaptor.forClass(UserLearningPathItem.class);
        verify(items, times(8)).save(activities.capture());
        for (int day = 1; day <= 4; day++) {
            int current = day;
            assertEquals(2, activities.getAllValues().stream().filter(a -> a.getDayNumber() == current).count());
        }
    }

    @Test void shortageCreatesCoverageGapAndNeverDuplicatesExercise() {
        when(comprehensionCatalog.resolve("READING", "N4")).thenReturn(
                new ComprehensionExerciseCatalog.CatalogResult(List.of(exercise("READING", 1)), List.of()));
        var result = composer.composeWarmUp(path(), profile("N4", List.of(),
                List.of(new SkillScore("READING", 10, 6)), 30));
        assertEquals(1, result.packageCount());
        assertTrue(result.gaps().stream().anyMatch(g -> g.reasonCode().equals("CONTENT_COVERAGE_INSUFFICIENT")));
        var saved = ArgumentCaptor.forClass(List.class);
        verify(members).saveAll(saved.capture());
        List<LearningActivityMember> rows = saved.getValue();
        assertEquals(List.of(11L, 12L), rows.stream().map(LearningActivityMember::getCanonicalId).toList());
    }

    @Test void missingEvidenceRequestsVerificationButDoesNotCreateWeaknessActivity() {
        var result = composer.composeWarmUp(path(), profile("N4", List.of(),
                List.of(new SkillScore("READING", 0, 0)), 30));
        assertEquals(0, result.packageCount());
        assertTrue(result.gaps().stream().anyMatch(g -> g.reasonCode().equals("NEEDS_VERIFICATION")));
        verifyNoInteractions(comprehensionCatalog);
    }

    @Test void scoreAboveSeventySkipsComprehensionWarmUp() {
        var result = composer.composeWarmUp(path(), profile("N4", List.of(),
                List.of(new SkillScore("READING", 10, 2)), 30));
        assertEquals(0, result.packageCount());
        verifyNoInteractions(comprehensionCatalog);
    }

    private ComprehensionExercise exercise(String skill, long index) {
        long base = index * 10;
        return new ComprehensionExercise(skill + "-" + index, skill, "N4", "source-" + index,
                "OUTCOME", null, List.of(
                new ComprehensionQuestionSpec(base + 1, "Q1", List.of("A", "B"), 1, "E1"),
                new ComprehensionQuestionSpec(base + 2, "Q2", List.of("A", "B"), 2, "E2")),
                String.format("%064d", index));
    }

    // ---- guard clauses ----

    @Test void disabledFlagPreventsComposition() {
        properties.setEnabled(false);
        var error = assertThrows(ApiException.class, () -> composer.composeWarmUp(path(), profile("N4", List.of())));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCode());
        verifyNoInteractions(packages, items, members);
    }

    @Test void legacyPathIsRejected() {
        var legacy = UserLearningPath.builder().id(5L).user(User.builder().id(7L).build())
                .experienceVersion(LearningExperienceVersion.LEGACY_V1).title("P").build();
        var error = assertThrows(ApiException.class, () -> composer.composeWarmUp(legacy, profile("N4", List.of())));
        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
    }

    @Test void noWeaknessEvidenceComposesNothing() {
        var result = composer.composeWarmUp(path(), profile("N4", List.of()));
        assertTrue(result.published());
        assertEquals(0, result.packageCount());
        verify(packages, never()).save(any());
    }

    @Test void retryIsIdempotentAndDoesNotRecompose() {
        when(packages.existsByLearningPathIdAndPhase(5L, "WARM_UP")).thenReturn(true);
        when(packages.findByLearningPathIdOrderByOrderIndex(5L)).thenReturn(
                List.of(LearningPathPackage.builder().phase("WARM_UP").build(),
                        LearningPathPackage.builder().phase("GOAL_LEARNING").build()));
        var result = composer.composeWarmUp(path(), profile("N4", List.of(vocabEvidence(1, "N4", 42L))));
        assertTrue(result.published());
        assertEquals(1, result.packageCount());
        verify(packages, never()).save(any());
        verifyNoInteractions(vocabularyEntries, kanjiRepo, grammarPoints);
    }

    // ---- Vocabulary ----

    @Test void weakVocabularyItemBecomesOwnActivityScopedToExactItems() {
        when(vocabularyEntries.findById(42L)).thenReturn(Optional.of(published()));
        var result = composer.composeWarmUp(path(), profile("N4", List.of(vocabEvidence(1, "N4", 42L))));
        assertTrue(result.published());
        assertEquals(1, result.packageCount());

        var pkgCaptor = ArgumentCaptor.forClass(LearningPathPackage.class);
        verify(packages).save(pkgCaptor.capture());
        assertEquals("WARM_UP", pkgCaptor.getValue().getPhase());
        assertNull(pkgCaptor.getValue().getSourceLesson());

        var itemCaptor = ArgumentCaptor.forClass(UserLearningPathItem.class);
        verify(items).save(itemCaptor.capture());
        assertEquals(LearningActivityType.VOCABULARY, itemCaptor.getValue().getActivityType());
        assertEquals("WEAK_VOCAB_ITEM", itemCaptor.getValue().getReasonCode());
        assertTrue(itemCaptor.getValue().getReason().contains("1"));

        var memberCaptor = ArgumentCaptor.forClass(List.class);
        verify(members).saveAll(memberCaptor.capture());
        List<LearningActivityMember> rows = memberCaptor.getValue();
        assertEquals(1, rows.size());
        assertEquals(42L, rows.getFirst().getCanonicalId());
    }

    @Test void vocabularyItemWithoutAnchorIsSkippedAsGapNotFabricated() {
        var evidence = WeaknessEvidence.item(WeaknessEvidence.SKILL_VOCABULARY, "VOCABULARY:未知", 3, 1, "N4", null);
        var result = composer.composeWarmUp(path(), profile("N4", List.of(evidence)));
        assertEquals(0, result.packageCount());
        assertEquals(1, result.gaps().size());
        assertEquals("CONTENT_MAPPING_PENDING", result.gaps().getFirst().reasonCode());
        verify(packages, never()).save(any());
    }

    @Test void vocabularyItemAboveAssessedLevelIsExcludedNeverLearnedGoesToGoal() {
        // Assessed at N4; an item that surfaced at N3 (harder) is "never learned" -> GOAL, not warm-up.
        var result = composer.composeWarmUp(path(), profile("N4", List.of(vocabEvidence(1, "N3", 42L))));
        assertEquals(0, result.packageCount());
        verifyNoInteractions(vocabularyEntries);
    }

    // ---- Kanji ----

    @Test void weakKanjiSingleCharacterResolvesToCanonicalKanjiId() {
        var evidence = WeaknessEvidence.item(WeaknessEvidence.SKILL_KANJI, "KANJI:食", 2, 1, "N4", 7L);
        when(kanjiRepo.findByCharacter("食")).thenReturn(Optional.of(Kanji.builder().id(88L).character("食").build()));
        var result = composer.composeWarmUp(path(), profile("N4", List.of(evidence)));
        assertEquals(1, result.packageCount());
        var memberCaptor = ArgumentCaptor.forClass(List.class);
        verify(members).saveAll(memberCaptor.capture());
        List<LearningActivityMember> rows = (List<LearningActivityMember>) memberCaptor.getValue();
        assertEquals(88L, rows.getFirst().getCanonicalId());
    }

    @Test void kanjiMultiCharacterLexemeCannotResolveAndIsReportedAsGap() {
        var evidence = WeaknessEvidence.item(WeaknessEvidence.SKILL_KANJI, "KANJI:食べる", 2, 1, "N4", 7L);
        when(kanjiRepo.findByCharacter("食べる")).thenReturn(Optional.empty());
        var result = composer.composeWarmUp(path(), profile("N4", List.of(evidence)));
        assertEquals(0, result.packageCount());
        assertEquals("CONTENT_MAPPING_PENDING", result.gaps().getFirst().reasonCode());
    }

    // ---- Grammar ----

    @Test void grammarBelowWeakThresholdIsExcluded() {
        // 1 wrong out of 4 assessed = 25% < 50% threshold -> not weak, no warm-up scheduled.
        var evidence = WeaknessEvidence.grammar(10L, "TAG", 1L, 4, 1, "N4");
        var result = composer.composeWarmUp(path(), profile("N4", List.of(evidence)));
        assertEquals(0, result.packageCount());
        verifyNoInteractions(grammarPoints);
    }

    @Test void grammarAtOrAboveWeakThresholdIsIncluded() {
        // 2 wrong out of 4 assessed = 50% >= 50% threshold -> weak.
        var evidence = WeaknessEvidence.grammar(10L, "TAG", 1L, 4, 2, "N4");
        when(grammarPoints.findById(10L)).thenReturn(Optional.of(GrammarPoint.builder().id(10L).build()));
        var result = composer.composeWarmUp(path(), profile("N4", List.of(evidence)));
        assertEquals(1, result.packageCount());
        var itemCaptor = ArgumentCaptor.forClass(UserLearningPathItem.class);
        verify(items).save(itemCaptor.capture());
        assertEquals(LearningActivityType.GRAMMAR, itemCaptor.getValue().getActivityType());
    }

    @Test void moreThanThreeWeakGrammarPointsOverflowsIntoASecondDay() {
        List<WeaknessEvidence> weaknesses = List.of(
                WeaknessEvidence.grammar(1L, "TAG", 1L, 2, 2, "N4"),
                WeaknessEvidence.grammar(2L, "TAG", 1L, 2, 2, "N4"),
                WeaknessEvidence.grammar(3L, "TAG", 1L, 2, 2, "N4"),
                WeaknessEvidence.grammar(4L, "TAG", 1L, 2, 2, "N4"),
                WeaknessEvidence.grammar(5L, "TAG", 1L, 2, 2, "N4"));
        for (long id = 1; id <= 5; id++) {
            when(grammarPoints.findById(id)).thenReturn(Optional.of(GrammarPoint.builder().id(id).build()));
        }
        var result = composer.composeWarmUp(path(), profile("N4", weaknesses));
        assertEquals(2, result.packageCount());

        var pkgCaptor = ArgumentCaptor.forClass(LearningPathPackage.class);
        verify(packages, times(2)).save(pkgCaptor.capture());
        assertEquals(List.of(1, 2), pkgCaptor.getAllValues().stream().map(LearningPathPackage::getDayNumber).toList());

        var memberCaptor = ArgumentCaptor.forClass(List.class);
        verify(members, times(2)).saveAll(memberCaptor.capture());
        List<List> allBatches = memberCaptor.getAllValues();
        assertEquals(3, allBatches.get(0).size());
        assertEquals(2, allBatches.get(1).size());
    }

    // ---- combined day-1 packaging, ordering, dedupe ----

    @Test void combinedDayOnePackageOrdersVocabularyThenKanjiThenGrammar() {
        when(vocabularyEntries.findById(42L)).thenReturn(Optional.of(published()));
        when(kanjiRepo.findByCharacter("食")).thenReturn(Optional.of(Kanji.builder().id(88L).character("食").build()));
        when(grammarPoints.findById(10L)).thenReturn(Optional.of(GrammarPoint.builder().id(10L).build()));
        List<WeaknessEvidence> weaknesses = List.of(
                vocabEvidence(1, "N4", 42L),
                WeaknessEvidence.item(WeaknessEvidence.SKILL_KANJI, "KANJI:食", 2, 1, "N4", 7L),
                WeaknessEvidence.grammar(10L, "TAG", 1L, 2, 2, "N4"));
        var result = composer.composeWarmUp(path(), profile("N4", weaknesses));
        assertEquals(1, result.packageCount());
        var itemCaptor = ArgumentCaptor.forClass(UserLearningPathItem.class);
        verify(items, times(3)).save(itemCaptor.capture());
        assertEquals(List.of(LearningActivityType.VOCABULARY, LearningActivityType.KANJI, LearningActivityType.GRAMMAR),
                itemCaptor.getAllValues().stream().map(UserLearningPathItem::getActivityType).toList());
        assertEquals(List.of(1, 2, 3), itemCaptor.getAllValues().stream().map(UserLearningPathItem::getOrderIndex).toList());
    }

    @Test void duplicateVocabularyAnchorAcrossEvidenceRowsIsDeduplicated() {
        when(vocabularyEntries.findById(42L)).thenReturn(Optional.of(published()));
        List<WeaknessEvidence> weaknesses = List.of(
                WeaknessEvidence.item(WeaknessEvidence.SKILL_VOCABULARY, "VOCABULARY:先生", 1, 1, "N4", 42L),
                WeaknessEvidence.item(WeaknessEvidence.SKILL_VOCABULARY, "VOCABULARY:先生に", 1, 1, "N4", 42L));
        var result = composer.composeWarmUp(path(), profile("N4", weaknesses));
        assertEquals(1, result.packageCount());
        var memberCaptor = ArgumentCaptor.forClass(List.class);
        verify(members).saveAll(memberCaptor.capture());
        List<LearningActivityMember> rows = (List<LearningActivityMember>) memberCaptor.getValue();
        assertEquals(1, rows.size());
    }

    @Test void packageAndDayNumberingContinueAfterExistingPathContent() {
        when(vocabularyEntries.findById(42L)).thenReturn(Optional.of(published()));
        when(items.findMaxDayNumberByLearningPathId(5L)).thenReturn(3);
        when(items.findMaxOrderIndexByLearningPathId(5L)).thenReturn(7);
        when(packages.findByLearningPathIdOrderByOrderIndex(5L)).thenReturn(
                List.of(LearningPathPackage.builder().orderIndex(2).phase("GOAL_LEARNING").build()));
        when(packages.existsByLearningPathId(5L)).thenReturn(true);

        var result = composer.composeWarmUp(path(), profile("N4", List.of(vocabEvidence(1, "N4", 42L))));
        assertEquals(1, result.packageCount());

        var pkgCaptor = ArgumentCaptor.forClass(LearningPathPackage.class);
        verify(packages).save(pkgCaptor.capture());
        assertEquals(4, pkgCaptor.getValue().getDayNumber());
        assertEquals(3, pkgCaptor.getValue().getOrderIndex());
        assertEquals(LearningPackageStatus.LOCKED, pkgCaptor.getValue().getStatus());

        var itemCaptor = ArgumentCaptor.forClass(UserLearningPathItem.class);
        verify(items).save(itemCaptor.capture());
        assertEquals(8, itemCaptor.getValue().getOrderIndex());
    }

    @Test void firstPackageOverallIsAvailableWhenNothingElseExistsYet() {
        when(vocabularyEntries.findById(42L)).thenReturn(Optional.of(published()));
        var result = composer.composeWarmUp(path(), profile("N4", List.of(vocabEvidence(1, "N4", 42L))));
        assertEquals(1, result.packageCount());
        var pkgCaptor = ArgumentCaptor.forClass(LearningPathPackage.class);
        verify(packages).save(pkgCaptor.capture());
        assertEquals(LearningPackageStatus.AVAILABLE, pkgCaptor.getValue().getStatus());
    }

    private WeaknessEvidence vocabEvidence(int wrongCount, String level, Long vocabularyEntryId) {
        return WeaknessEvidence.item(WeaknessEvidence.SKILL_VOCABULARY, "VOCABULARY:先生", 1, wrongCount, level, vocabularyEntryId);
    }

    private VocabularyEntry published() {
        var entry = VocabularyEntry.builder().id(42L).build();
        entry.setDictionaryStatus("PUBLISHED");
        return entry;
    }
}
