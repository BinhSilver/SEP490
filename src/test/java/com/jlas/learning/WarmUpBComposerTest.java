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
import com.jlas.learning.entity.UserLearningStage;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningMemberType;
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
        lenient().when(stages.findByLearningPathIdOrderByStageIndexAsc(anyLong())).thenReturn(List.of(
                UserLearningStage.builder().id(40L).level("FOUNDATION").stageIndex(0).build(),
                UserLearningStage.builder().id(41L).level("N3").stageIndex(1).build()));
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

    @Test void comprehensionWorkThatCannotFitDailyBudgetIsReportedBeforeAnyPackageIsSaved() {
        when(comprehensionCatalog.resolve("READING", "N4")).thenReturn(
                new ComprehensionExerciseCatalog.CatalogResult(List.of(exercise("READING", 1)), List.of()));
        when(vocabularyEntries.findById(42L)).thenReturn(Optional.of(published()));
        var profile = profile("N4", fiveVocabularyEvidence(42L, "N4"),
                List.of(new SkillScore("READING", 10, 4)), 5);

        var result = composer.composeWarmUp(path(), profile);

        assertFalse(result.published());
        assertTrue(result.gaps().stream().anyMatch(gap ->
                gap.reasonCode().equals("DAILY_BUDGET_TOO_SMALL_FOR_COMPREHENSION")));
        verify(packages, never()).save(any());
        verify(items, never()).save(any());
        verify(members, never()).saveAll(any());
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

    @Test void unmappedWrongReadingQuestionIsReportedAsCoverageGap() {
        var evidence = WeaknessEvidence.comprehension("READING", 5, 4, null, null,
                List.of(new WeaknessEvidence.OutcomeBreakdown("UNMAPPED_QUESTION:77", null, 1, 1)),
                null, null, "N4");
        when(comprehensionCatalog.resolve("READING", "N4")).thenReturn(
                new ComprehensionExerciseCatalog.CatalogResult(List.of(exercise("READING", 1)), List.of()));

        var result = composer.composeWarmUp(path(), profile("N4", List.of(evidence),
                List.of(new SkillScore("READING", 5, 4)), 30));

        assertEquals(1, result.packageCount());
        assertTrue(result.gaps().stream().anyMatch(g -> g.tagCode().equals("READING:UNMAPPED_QUESTION:77")
                && g.reasonCode().equals("CONTENT_MAPPING_PENDING")));
    }

    @Test void everyWrongReadingOutcomeIsScheduledEvenWhenOverallBandWouldSkipPractice() {
        var evidence = WeaknessEvidence.comprehension("READING", 10, 2, null, null,
                List.of(new WeaknessEvidence.OutcomeBreakdown("DETAIL", 21L, 1, 1),
                        new WeaknessEvidence.OutcomeBreakdown("INFERENCE", 22L, 1, 1)),
                null, null, "N4");
        var targeted = List.of(exerciseWithOutcome("READING", 1, "DETAIL"),
                exerciseWithOutcome("READING", 2, "INFERENCE"));
        when(comprehensionCatalog.resolve("READING", "N4")).thenReturn(
                new ComprehensionExerciseCatalog.CatalogResult(targeted, List.of()));

        var result = composer.composeWarmUp(path(), profile("N4", List.of(evidence),
                List.of(new SkillScore("READING", 10, 2)), 30));

        assertEquals(1, result.packageCount());
        var saved = ArgumentCaptor.forClass(List.class);
        verify(members).saveAll(saved.capture());
        List<LearningActivityMember> membersForQuiz = (List<LearningActivityMember>) saved.getValue();
        assertTrue(membersForQuiz.stream().anyMatch(member -> member.getCanonicalId().equals(11L)));
        assertTrue(membersForQuiz.stream().anyMatch(member -> member.getCanonicalId().equals(21L)));
        assertTrue(membersForQuiz.stream().anyMatch(member -> member.getCanonicalId().equals(22L)));
    }

    @Test void everyWrongListeningOutcomeIsScheduledEvenWhenOverallBandWouldSkipPractice() {
        var evidence = WeaknessEvidence.comprehension("LISTENING", 10, 2, null, null,
                List.of(new WeaknessEvidence.OutcomeBreakdown("AUDIO_DETAIL", 31L, 1, 1),
                        new WeaknessEvidence.OutcomeBreakdown("SPEAKER_INTENT", 32L, 1, 1)),
                null, null, "N4");
        var targeted = List.of(exerciseWithOutcome("LISTENING", 3, "AUDIO_DETAIL"),
                exerciseWithOutcome("LISTENING", 4, "SPEAKER_INTENT"));
        when(comprehensionCatalog.resolve("LISTENING", "N4")).thenReturn(
                new ComprehensionExerciseCatalog.CatalogResult(targeted, List.of()));

        var result = composer.composeWarmUp(path(), profile("N4", List.of(evidence),
                List.of(new SkillScore("LISTENING", 10, 2)), 30));

        assertEquals(1, result.packageCount());
        var saved = ArgumentCaptor.forClass(List.class);
        verify(members).saveAll(saved.capture());
        List<LearningActivityMember> membersForQuiz = (List<LearningActivityMember>) saved.getValue();
        assertTrue(membersForQuiz.stream().anyMatch(member -> member.getCanonicalId().equals(31L)));
        assertTrue(membersForQuiz.stream().anyMatch(member -> member.getCanonicalId().equals(32L)));
        assertEquals(4, membersForQuiz.stream().filter(member -> member.getMemberType() == LearningMemberType.COMPREHENSION_QUESTION).count());
    }

    @Test void comprehensionOutcomesBeyondDailyCapAreCarriedForwardWithoutLossOrDuplication() {
        var outcomes = List.of("DETAIL_A", "DETAIL_B", "INTENT_A", "INTENT_B", "CONTEXT");
        var evidence = WeaknessEvidence.comprehension("LISTENING", 10, 2, null, null,
                java.util.stream.IntStream.range(0, outcomes.size())
                        .mapToObj(index -> new WeaknessEvidence.OutcomeBreakdown(
                                outcomes.get(index), 41L + index, 1, 1)).toList(),
                null, null, "N4");
        var targeted = java.util.stream.IntStream.range(0, outcomes.size())
                .mapToObj(index -> exerciseWithOutcome("LISTENING", index + 5L, outcomes.get(index))).toList();
        when(comprehensionCatalog.resolve("LISTENING", "N4")).thenReturn(
                new ComprehensionExerciseCatalog.CatalogResult(targeted, List.of()));

        var result = composer.composeWarmUp(path(), profile("N4", List.of(evidence),
                List.of(new SkillScore("LISTENING", 10, 2)), 30));

        assertEquals(3, result.packageCount(), "Five exercises at a two-per-day cap must span three days");
        var packagesCaptor = ArgumentCaptor.forClass(LearningPathPackage.class);
        verify(packages, times(3)).save(packagesCaptor.capture());
        assertEquals(List.of(1, 2, 3), packagesCaptor.getAllValues().stream()
                .map(LearningPathPackage::getDayNumber).toList());

        var membersCaptor = ArgumentCaptor.forClass(List.class);
        verify(members, times(3)).saveAll(membersCaptor.capture());
        List<Long> questionIds = membersCaptor.getAllValues().stream()
                .flatMap(List::stream)
                .map(member -> ((LearningActivityMember) member).getCanonicalId())
                .toList();
        assertEquals(10, questionIds.size());
        assertEquals(10, questionIds.stream().distinct().count(), "Overflow must not duplicate question membership");
        assertEquals(List.of(91L, 92L, 51L, 52L, 61L, 62L, 71L, 72L, 81L, 82L), questionIds,
                "Outcome mapping order is deterministic and every selected exercise remains scheduled");
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

    private ComprehensionExercise exerciseWithOutcome(String skill, long index, String outcome) {
        long base = index * 10;
        return new ComprehensionExercise(skill + "-" + index, skill, "N4", "source-" + index,
                outcome, null, List.of(
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
        List<WeaknessEvidence> evidence = new java.util.ArrayList<>();
        evidence.add(vocabEvidence(1, "N4", 42L));
        for (long id = 43; id <= 46; id++) {
            when(vocabularyEntries.findById(id)).thenReturn(Optional.of(published(id)));
            evidence.add(WeaknessEvidence.item(WeaknessEvidence.SKILL_VOCABULARY,
                    "VOCABULARY:語" + id, 1, 1, "N4", id));
        }
        var result = composer.composeWarmUp(path(), profile("N4", evidence));
        assertTrue(result.published());
        assertEquals(1, result.packageCount());

        var pkgCaptor = ArgumentCaptor.forClass(LearningPathPackage.class);
        verify(packages).save(pkgCaptor.capture());
        assertEquals("WARM_UP", pkgCaptor.getValue().getPhase());
        assertNull(pkgCaptor.getValue().getSourceLesson());
        assertEquals(41L, pkgCaptor.getValue().getStageId());

        var itemCaptor = ArgumentCaptor.forClass(UserLearningPathItem.class);
        verify(items).save(itemCaptor.capture());
        assertEquals(LearningActivityType.VOCABULARY, itemCaptor.getValue().getActivityType());
        assertEquals("WEAK_VOCAB_ITEM", itemCaptor.getValue().getReasonCode());
        assertTrue(itemCaptor.getValue().getReason().contains("5"));

        var memberCaptor = ArgumentCaptor.forClass(List.class);
        verify(members).saveAll(memberCaptor.capture());
        List<LearningActivityMember> rows = memberCaptor.getValue();
        assertEquals(5, rows.size());
        assertEquals(List.of(42L, 43L, 44L, 45L, 46L), rows.stream()
                .map(LearningActivityMember::getCanonicalId).toList());
    }

    @Test void smartTestVocabularyBelowFiveMappedTargetsIsReviewOnly() {
        List<WeaknessEvidence> evidence = new java.util.ArrayList<>();
        for (long id = 1; id <= 4; id++) {
            var entry = published(id);
            when(vocabularyEntries.findById(id)).thenReturn(Optional.of(entry));
            evidence.add(WeaknessEvidence.item(WeaknessEvidence.SKILL_VOCABULARY,
                    "VOCABULARY:語" + id, 1, 1, "N4", id));
        }

        var result = composer.composeWarmUp(path(), profile("N4", evidence));

        assertEquals(0, result.packageCount());
        assertEquals(4, result.gaps().stream()
                .filter(gap -> gap.reasonCode().equals("SMART_TEST_REVIEW_ONLY_BELOW_THRESHOLD")).count());
        assertTrue(result.gaps().stream().allMatch(gap -> gap.tagCode().startsWith("VOCABULARY:語")));
        verify(packages, never()).save(any());
        verify(items, never()).save(any());
        verify(members, never()).saveAll(any());
    }

    @Test void fiveMappedVocabularyTargetsOpenFullSmartTestRemediation() {
        List<WeaknessEvidence> evidence = new java.util.ArrayList<>();
        for (long id = 1; id <= 5; id++) {
            when(vocabularyEntries.findById(id)).thenReturn(Optional.of(published(id)));
            evidence.add(WeaknessEvidence.item(WeaknessEvidence.SKILL_VOCABULARY,
                    "VOCABULARY:語" + id, 1, 1, "N4", id));
        }

        var result = composer.composeWarmUp(path(), profile("N4", evidence));

        assertEquals(1, result.packageCount());
        assertFalse(result.gaps().stream()
                .anyMatch(gap -> gap.reasonCode().equals("SMART_TEST_REVIEW_ONLY_BELOW_THRESHOLD")));
        var memberCaptor = ArgumentCaptor.forClass(List.class);
        verify(members).saveAll(memberCaptor.capture());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), ((List<LearningActivityMember>) memberCaptor.getValue())
                .stream().map(LearningActivityMember::getCanonicalId).toList());
    }

    @Test void moreThanFiveMappedKanjiTargetsStayInOneFullSkillActivity() {
        List<WeaknessEvidence> evidence = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String character = String.valueOf((char) ('日' + i));
            when(kanjiRepo.findByCharacter(character)).thenReturn(Optional.of(Kanji.builder()
                    .id(90L + i).character(character).build()));
            evidence.add(WeaknessEvidence.item(WeaknessEvidence.SKILL_KANJI,
                    "KANJI:" + character, 1, 1, "N4", null));
        }

        var result = composer.composeWarmUp(path(), profile("N4", evidence));

        assertEquals(1, result.packageCount());
        assertFalse(result.gaps().stream()
                .anyMatch(gap -> gap.reasonCode().equals("SMART_TEST_REVIEW_ONLY_BELOW_THRESHOLD")));
        var captured = ArgumentCaptor.forClass(List.class);
        verify(members).saveAll(captured.capture());
        assertEquals(6, ((List<LearningActivityMember>) captured.getValue()).size());
    }

    @Test void duplicateMappedVocabularyTargetsDoNotReachFiveAndUnmappedItemsDoNotCount() {
        List<WeaknessEvidence> evidence = new java.util.ArrayList<>();
        for (long id = 1; id <= 4; id++) {
            when(vocabularyEntries.findById(id)).thenReturn(Optional.of(published(id)));
            evidence.add(WeaknessEvidence.item(WeaknessEvidence.SKILL_VOCABULARY,
                    "VOCABULARY:語" + id, 1, 1, "N4", id));
        }
        evidence.add(WeaknessEvidence.item(WeaknessEvidence.SKILL_VOCABULARY,
                "VOCABULARY:別表記", 1, 1, "N4", 1L));
        evidence.add(WeaknessEvidence.item(WeaknessEvidence.SKILL_VOCABULARY,
                "VOCABULARY:未登録", 1, 1, "N4", null));

        var result = composer.composeWarmUp(path(), profile("N4", evidence));

        assertEquals(0, result.packageCount());
        assertEquals(4, result.gaps().stream()
                .filter(gap -> gap.reasonCode().equals("SMART_TEST_REVIEW_ONLY_BELOW_THRESHOLD")).count());
        assertTrue(result.gaps().stream().anyMatch(gap -> gap.tagCode().equals("VOCABULARY:未登録")
                && gap.reasonCode().equals("CONTENT_MAPPING_PENDING")));
        verify(packages, never()).save(any());
        verify(members, never()).saveAll(any());
    }

    @Test void vocabularyAndKanjiRemediationThresholdsAreIndependent() {
        List<WeaknessEvidence> evidence = new java.util.ArrayList<>();
        for (long id = 1; id <= 4; id++) {
            when(vocabularyEntries.findById(id)).thenReturn(Optional.of(published(id)));
            evidence.add(WeaknessEvidence.item(WeaknessEvidence.SKILL_VOCABULARY,
                    "VOCABULARY:語" + id, 1, 1, "N4", id));
        }
        for (int i = 0; i < 5; i++) {
            String kanji = String.valueOf((char) ('日' + i));
            when(kanjiRepo.findByCharacter(kanji)).thenReturn(Optional.of(Kanji.builder()
                    .id(80L + i).character(kanji).build()));
            evidence.add(WeaknessEvidence.item(WeaknessEvidence.SKILL_KANJI,
                    "KANJI:" + kanji, 1, 1, "N4", null));
        }

        var result = composer.composeWarmUp(path(), profile("N4", evidence));

        assertEquals(1, result.packageCount());
        assertEquals(4, result.gaps().stream().filter(gap -> gap.reasonCode()
                .equals("SMART_TEST_REVIEW_ONLY_BELOW_THRESHOLD")).count());
        var itemsCaptor = ArgumentCaptor.forClass(UserLearningPathItem.class);
        verify(items).save(itemsCaptor.capture());
        assertEquals(LearningActivityType.KANJI, itemsCaptor.getValue().getActivityType());
        var membersCaptor = ArgumentCaptor.forClass(List.class);
        verify(members).saveAll(membersCaptor.capture());
        assertEquals(5, ((List<LearningActivityMember>) membersCaptor.getValue()).size());
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
        List<WeaknessEvidence> evidence = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String character = String.valueOf((char) ('食' + i));
            when(kanjiRepo.findByCharacter(character)).thenReturn(Optional.of(Kanji.builder()
                    .id(88L + i).character(character).build()));
            evidence.add(WeaknessEvidence.item(WeaknessEvidence.SKILL_KANJI,
                    "KANJI:" + character, 2, 1, "N4", null));
        }
        var result = composer.composeWarmUp(path(), profile("N4", evidence));
        assertEquals(1, result.packageCount());
        var memberCaptor = ArgumentCaptor.forClass(List.class);
        verify(members).saveAll(memberCaptor.capture());
        List<LearningActivityMember> rows = (List<LearningActivityMember>) memberCaptor.getValue();
        assertEquals(List.of(88L, 89L, 90L, 91L, 92L), rows.stream()
                .map(LearningActivityMember::getCanonicalId).toList());
    }

    @Test void kanjiMultiCharacterLexemeCannotResolveAndIsReportedAsGap() {
        var evidence = WeaknessEvidence.item(WeaknessEvidence.SKILL_KANJI, "KANJI:食べる", 2, 1, "N4", 7L);
        when(kanjiRepo.findByCharacter("食べる")).thenReturn(Optional.empty());
        var result = composer.composeWarmUp(path(), profile("N4", List.of(evidence)));
        assertEquals(0, result.packageCount());
        assertEquals("CONTENT_MAPPING_PENDING", result.gaps().getFirst().reasonCode());
    }

    // ---- Grammar ----

    @Test void everyWrongGrammarPointIsIncludedEvenBelowTheMasteryThreshold() {
        var evidence = WeaknessEvidence.grammar(10L, "TAG", 1L, 4, 1, "N4");
        when(grammarPoints.findById(10L)).thenReturn(Optional.of(GrammarPoint.builder().id(10L).build()));
        var result = composer.composeWarmUp(path(), profile("N4", List.of(evidence)));
        assertEquals(1, result.packageCount());
        var captured = ArgumentCaptor.forClass(List.class);
        verify(members).saveAll(captured.capture());
        assertEquals(10L, ((LearningActivityMember) captured.getValue().getFirst()).getCanonicalId());
    }

    @Test void wrongGrammarWithoutAnAnchorReportsMappingGapInsteadOfDisappearing() {
        var evidence = WeaknessEvidence.item("GRAMMAR", "UNMAPPED:Q12", 1, 1, "N4");
        var result = composer.composeWarmUp(path(), profile("N4", List.of(evidence)));
        assertEquals(0, result.packageCount());
        assertEquals(1, result.gaps().size());
        assertEquals("CONTENT_MAPPING_PENDING", result.gaps().getFirst().reasonCode());
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

    @Test void grammarBeyondDailyCapIsCarriedForwardWithoutLossOrDuplication() {
        List<WeaknessEvidence> weaknesses = new java.util.ArrayList<>();
        for (long id = 1; id <= 8; id++) {
            weaknesses.add(WeaknessEvidence.grammar(id, "TAG", 1L, 2, 2, "N4"));
            when(grammarPoints.findById(id)).thenReturn(Optional.of(GrammarPoint.builder().id(id).build()));
        }
        var result = composer.composeWarmUp(path(), profile("N4", weaknesses));
        assertEquals(3, result.packageCount());

        var pkgCaptor = ArgumentCaptor.forClass(LearningPathPackage.class);
        verify(packages, times(3)).save(pkgCaptor.capture());
        assertEquals(List.of(1, 2, 3), pkgCaptor.getAllValues().stream().map(LearningPathPackage::getDayNumber).toList());

        var memberCaptor = ArgumentCaptor.forClass(List.class);
        verify(members, times(3)).saveAll(memberCaptor.capture());
        List<List> allBatches = memberCaptor.getAllValues();
        assertEquals(3, allBatches.get(0).size());
        assertEquals(3, allBatches.get(1).size());
        assertEquals(2, allBatches.get(2).size());
        List<Long> grammarIds = allBatches.stream().flatMap(List::stream)
                .map(member -> ((LearningActivityMember) member).getCanonicalId()).toList();
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), grammarIds);
        assertEquals(8, grammarIds.stream().distinct().count(), "Daily overflow must not duplicate grammar points");
    }

    // ---- combined day-1 packaging, ordering, dedupe ----

    @Test void combinedDayOnePackageOrdersVocabularyThenKanjiThenGrammar() {
        List<WeaknessEvidence> weaknesses = new java.util.ArrayList<>();
        for (long id = 42; id <= 46; id++) {
            when(vocabularyEntries.findById(id)).thenReturn(Optional.of(published(id)));
            weaknesses.add(WeaknessEvidence.item(WeaknessEvidence.SKILL_VOCABULARY,
                    "VOCABULARY:語" + id, 1, 1, "N4", id));
        }
        for (int i = 0; i < 5; i++) {
            String character = String.valueOf((char) ('食' + i));
            when(kanjiRepo.findByCharacter(character)).thenReturn(Optional.of(Kanji.builder()
                    .id(88L + i).character(character).build()));
            weaknesses.add(WeaknessEvidence.item(WeaknessEvidence.SKILL_KANJI,
                    "KANJI:" + character, 1, 1, "N4", null));
        }
        when(grammarPoints.findById(10L)).thenReturn(Optional.of(GrammarPoint.builder().id(10L).build()));
        weaknesses.add(WeaknessEvidence.grammar(10L, "TAG", 1L, 2, 2, "N4"));
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
        assertEquals(0, result.packageCount());
        assertEquals(1, result.gaps().stream().filter(gap -> gap.reasonCode()
                .equals("SMART_TEST_REVIEW_ONLY_BELOW_THRESHOLD")).count(),
                "Two wrong evidence rows mapping to one canonical item count once");
        verify(members, never()).saveAll(any());
    }

    @Test void resolvesWrongVocabularyByNormalizedCanonicalExpressionWhenQuestionHasNoForeignKey() {
        when(vocabularyEntries.findFirstByNormalizedExpressionAndDictionaryStatus("こんぴゅーたー", "PUBLISHED"))
                .thenReturn(Optional.of(published()));
        when(vocabularyEntries.findById(42L)).thenReturn(Optional.of(published()));
        var evidence = WeaknessEvidence.item(WeaknessEvidence.SKILL_VOCABULARY,
                "VOCABULARY:コンピューター", 1, 1, "N4");

        var result = composer.composeWarmUp(path(), profile("N4", List.of(evidence)));

        assertEquals(0, result.packageCount());
        assertFalse(result.gaps().stream().anyMatch(gap -> gap.reasonCode().equals("CONTENT_MAPPING_PENDING")),
                "Exact published dictionary match must not be reported as unmapped");
        assertEquals(List.of("VOCABULARY:コンピューター"), result.gaps().stream()
                .filter(gap -> gap.reasonCode().equals("SMART_TEST_REVIEW_ONLY_BELOW_THRESHOLD"))
                .map(com.jlas.learning.dto.CoverageGap::tagCode).toList());
        verify(members, never()).saveAll(any());
    }

    @Test void packageAndDayNumberingContinueAfterExistingPathContent() {
        var evidence = fiveVocabularyEvidence(42L, "N4");
        when(items.findMaxDayNumberByLearningPathId(5L)).thenReturn(3);
        when(items.findMaxOrderIndexByLearningPathId(5L)).thenReturn(7);
        when(packages.findByLearningPathIdOrderByOrderIndex(5L)).thenReturn(
                List.of(LearningPathPackage.builder().orderIndex(2).phase("GOAL_LEARNING").build()));
        when(packages.existsByLearningPathId(5L)).thenReturn(true);

        var result = composer.composeWarmUp(path(), profile("N4", evidence));
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
        var result = composer.composeWarmUp(path(), profile("N4", fiveVocabularyEvidence(42L, "N4")));
        assertEquals(1, result.packageCount());
        var pkgCaptor = ArgumentCaptor.forClass(LearningPathPackage.class);
        verify(packages).save(pkgCaptor.capture());
        assertEquals(LearningPackageStatus.AVAILABLE, pkgCaptor.getValue().getStatus());
    }

    private WeaknessEvidence vocabEvidence(int wrongCount, String level, Long vocabularyEntryId) {
        return WeaknessEvidence.item(WeaknessEvidence.SKILL_VOCABULARY, "VOCABULARY:先生", 1, wrongCount, level, vocabularyEntryId);
    }

    @Test void longSmartTestRemediationSplitsEachSkillWithoutDroppingMappedTargets() {
        List<WeaknessEvidence> weaknesses = new java.util.ArrayList<>();
        for (long id = 100; id < 132; id++) {
            when(vocabularyEntries.findById(id)).thenReturn(Optional.of(published(id)));
            weaknesses.add(WeaknessEvidence.item(WeaknessEvidence.SKILL_VOCABULARY,
                    "VOCABULARY:語" + id, 1, 1, "N4", id));
        }
        for (int i = 0; i < 17; i++) {
            String character = String.valueOf((char) ('一' + i));
            when(kanjiRepo.findByCharacter(character)).thenReturn(Optional.of(Kanji.builder()
                    .id(300L + i).character(character).build()));
            weaknesses.add(WeaknessEvidence.item(WeaknessEvidence.SKILL_KANJI,
                    "KANJI:" + character, 1, 1, "N4", null));
        }

        assertTrue(composer.composeWarmUp(path(), profile("N4", weaknesses)).published());
        var itemCaptor = ArgumentCaptor.forClass(UserLearningPathItem.class);
        verify(items, times(5)).save(itemCaptor.capture());
        assertEquals(List.of(LearningActivityType.VOCABULARY, LearningActivityType.VOCABULARY,
                        LearningActivityType.VOCABULARY, LearningActivityType.KANJI, LearningActivityType.KANJI),
                itemCaptor.getAllValues().stream().map(UserLearningPathItem::getActivityType).toList());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<LearningActivityMember>> memberCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(members, times(5)).saveAll(memberCaptor.capture());
        var groups = memberCaptor.getAllValues().stream().map(group ->
                java.util.stream.StreamSupport.stream(group.spliterator(), false).toList()).toList();
        assertEquals(List.of(11, 11, 10, 9, 8), groups.stream().map(List::size).toList());
        assertEquals(49, groups.stream().mapToInt(List::size).sum());
        // UAT 2026-10-06: same-type parts in one package need distinct part_index (MySQL unique key).
        var parts = itemCaptor.getAllValues().stream().collect(java.util.stream.Collectors.groupingBy(
                i -> i.getLearningPackage().getOrderIndex() + ":" + i.getActivityType(), java.util.LinkedHashMap::new,
                java.util.stream.Collectors.mapping(UserLearningPathItem::getPartIndex, java.util.stream.Collectors.toList())));
        parts.values().forEach(p -> assertEquals(
                java.util.stream.IntStream.rangeClosed(1, p.size()).boxed().toList(), p, parts.toString()));
    }

    @Test void warmUpVocabularyAndKanjiWorkSpillsToLaterDaysWithinTheUserBudget(){
        List<WeaknessEvidence> weaknesses = new java.util.ArrayList<>();
        for (long id = 100; id < 110; id++) {
            when(vocabularyEntries.findById(id)).thenReturn(Optional.of(published(id)));
            weaknesses.add(WeaknessEvidence.item(WeaknessEvidence.SKILL_VOCABULARY,
                    "VOCABULARY:語" + id, 1, 1, "N4", id));
        }
        for (int i = 0; i < 10; i++) {
            String character = String.valueOf((char) ('一' + i));
            when(kanjiRepo.findByCharacter(character)).thenReturn(Optional.of(Kanji.builder()
                    .id(300L + i).character(character).build()));
            weaknesses.add(WeaknessEvidence.item(WeaknessEvidence.SKILL_KANJI,
                    "KANJI:" + character, 1, 1, "N4", null));
        }

        var result = composer.composeWarmUp(path(), profile("N4", weaknesses, List.of(), 15));

        assertEquals(2, result.packageCount(), "The two mandatory skill blocks cannot share a 15-minute day");
        var packageCaptor = ArgumentCaptor.forClass(LearningPathPackage.class);
        verify(packages, times(2)).save(packageCaptor.capture());
        assertTrue(packageCaptor.getAllValues().stream().allMatch(pkg -> pkg.getEstimatedMinutes() <= 15));
        assertEquals(List.of(1, 2), packageCaptor.getAllValues().stream()
                .map(LearningPathPackage::getDayNumber).toList());

        var activityCaptor = ArgumentCaptor.forClass(UserLearningPathItem.class);
        verify(items, times(2)).save(activityCaptor.capture());
        assertEquals(List.of(LearningActivityType.VOCABULARY, LearningActivityType.KANJI),
                activityCaptor.getAllValues().stream().map(UserLearningPathItem::getActivityType).toList());
        assertTrue(activityCaptor.getAllValues().stream().allMatch(activity -> activity.getEstimatedMinutes() <= 15));

        var memberCaptor = ArgumentCaptor.forClass(List.class);
        verify(members, times(2)).saveAll(memberCaptor.capture());
        assertEquals(20, memberCaptor.getAllValues().stream().mapToInt(List::size).sum());
    }

    private List<WeaknessEvidence> fiveVocabularyEvidence(long firstId, String level) {
        List<WeaknessEvidence> evidence = new java.util.ArrayList<>();
        for (long id = firstId; id < firstId + 5; id++) {
            when(vocabularyEntries.findById(id)).thenReturn(Optional.of(published(id)));
            evidence.add(WeaknessEvidence.item(WeaknessEvidence.SKILL_VOCABULARY,
                    "VOCABULARY:語" + id, 1, 1, level, id));
        }
        return evidence;
    }

    private VocabularyEntry published() {
        return published(42L);
    }

    private VocabularyEntry published(Long id) {
        var entry = VocabularyEntry.builder().id(id).build();
        entry.setDictionaryStatus("PUBLISHED");
        return entry;
    }
}
