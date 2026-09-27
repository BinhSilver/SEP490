package com.jlas.learning;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.*;
import com.jlas.learning.enums.LearningPhase;
import com.jlas.learning.service.LearningCatalogSource;
import com.jlas.learning.service.impl.LearningResourceSelectionServiceImpl;
import com.jlas.learning.service.impl.WeakTagPolicy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class LearningResourceSelectionServiceTest {
    private final List<LearningResourceCandidate> lessons = new ArrayList<>(List.of(
            lesson(11, 1, "N5", 15, 1, "PARTICLE"),
            lesson(12, 1, "N5", 45, 2, "VERB"),
            lesson(21, 2, "N4", 20, 1, "TARGET")));
    private final LearningCatalogSource catalog = books -> lessons.stream()
            .filter(lesson -> books.contains(lesson.textbookId())).toList();
    private final LearningResourceSelectionServiceImpl selector = selector(3, "0.5");
    private final LearningCatalogScope scope = new LearningCatalogScope(List.of(1L), List.of(2L));

    @Test void selectsWeakFoundationsBeforeGoalLessonsEvenBelowAssessedLevel() {
        var result = select(tag("PARTICLE", 5, 4),
                tag("VERB", 5, 2), tag("UNKNOWN", 1, 1));
        assertEquals(List.of(11L, 21L), ids(result));
        assertEquals(List.of(LearningPhase.WARM_UP, LearningPhase.GOAL_LEARNING),
                result.resources().stream().map(SelectedLearningResource::phase).toList());
        assertEquals("WEAK_TAG", result.resources().getFirst().reasonCode());
        assertEquals(Set.of("PARTICLE"), result.resources().getFirst().matchedTagCodes());
        assertFalse(result.resources().getFirst().reasonText().isBlank());
        assertEquals(List.of(new CoverageGap("UNKNOWN", "INSUFFICIENT_ASSESSMENT")), result.coverageGaps());
    }

    @Test void prioritizesErrorRatioThenWrongCountThenStableTagCode() {
        lessons.add(lesson(13, 1, "N5", 10, 3, "ADJECTIVE"));
        var result = select(tag("PARTICLE", 10, 6),
                tag("VERB", 4, 3), tag("ADJECTIVE", 4, 3));
        assertEquals(List.of(13L, 12L, 11L, 21L), ids(result));
        assertEquals(45, result.resources().get(1).resource().estimatedMinutes());
    }

    @ParameterizedTest
    @CsvSource({"2,2,false", "3,1,false", "4,2,true", "3,2,true", "0,0,false"})
    void respectsSampleAndRatioBoundaries(int assessed, int wrong, boolean weak) {
        var result = select(tag("PARTICLE", assessed, wrong));
        assertEquals(weak ? List.of(11L, 21L) : List.of(21L), ids(result));
        assertEquals(assessed < 3 ? 1 : 0, result.coverageGaps().size());
    }

    @Test void thresholdsAreConfigurableAndIncludedInPolicyIdentity() {
        var profile = profile(List.of(tag("PARTICLE", 2, 1)));
        var relaxed = selector(2, "0.5").select(7L, profile, scope);
        var stricter = selector(2, "0.75").select(7L, profile, scope);
        assertEquals(List.of(11L, 21L), ids(relaxed));
        assertEquals(List.of(21L), ids(stricter));
        assertNotEquals(relaxed.policyVersion(), stricter.policyVersion());
    }

    @ParameterizedTest
    @CsvSource({"0,0.5", "3,0", "3,-0.1", "3,1.1"})
    void rejectsInvalidPolicyConfiguration(int minimum, String threshold) {
        assertThrows(IllegalArgumentException.class, () -> selector(minimum, threshold));
    }

    @Test void fullyAssessedProfileWithoutWeakTagsStartsAtGoalLearning() {
        var result = select(tag("PARTICLE", 5, 0));
        assertEquals(List.of(21L), ids(result));
        assertEquals(LearningPhase.GOAL_LEARNING, result.resources().getFirst().phase());
        assertTrue(result.coverageGaps().isEmpty());
    }

    @Test void n5ToN4GoalPhaseExcludesAssessedLevelLessons() {
        // [JLAS-W0108] GOAL chỉ lấy bài Ở TRÊN trình độ đã kết luận: N5→N4 không được trộn lại
        // 25 bài N5 đã biết, chỉ lấy bài N4 mới. Bài N5 trong giáo trình GOAL phải bị loại.
        var n5toN4Scope = new LearningCatalogScope(List.of(1L), List.of(1L, 2L));
        var n5toN4 = new LearningProfileSnapshot(7L, 91L, 10L, "1", true, true,
                "N5", "N4", "JLPT", 30, validTags());
        var result = selector.select(7L, n5toN4, n5toN4Scope);
        assertEquals(List.of(11L, 21L), ids(result));
        assertEquals(List.of(LearningPhase.WARM_UP, LearningPhase.GOAL_LEARNING),
                result.resources().stream().map(SelectedLearningResource::phase).toList());
    }

    @Test void missingWarmUpResourcesAreReportedWithoutInventingLessons() {
        var result = select(tag("MISSING", 5, 3));
        assertEquals(List.of(21L), ids(result));
        assertEquals(List.of(new CoverageGap("MISSING", "MISSING_WARM_UP_RESOURCE")), result.coverageGaps());
    }

    @Test void grammarWeaknessWarmUpResolvesTheSpecificGrammarPoint() {
        var grammarCatalog = new LearningCatalogSource() {
            @Override
            public List<LearningResourceCandidate> findPublishedLessons(List<Long> textbookIds) {
                return lessons.stream().filter(lesson -> textbookIds.contains(lesson.textbookId())).toList();
            }

            @Override
            public Optional<LearningResourceCandidate> findGrammarPoint(Long grammarPointId) {
                if (grammarPointId == null) return Optional.empty();
                return Optional.of(new LearningResourceCandidate(11L, "～てください", 1L, 10L,
                        Set.of("PARTICLE"), "N5", 10, 1, 1, grammarPointId, null));
            }
        };
        var grammarSelector = new LearningResourceSelectionServiceImpl(
                grammarCatalog, new WeakTagPolicy(3, new BigDecimal("0.5")));
        var result = grammarSelector.select(7L, profile(List.of(WeaknessEvidence.grammar(77L, "PARTICLE", 1L, 5, 3))), scope);
        assertEquals(List.of(11L, 21L), ids(result));
        assertEquals(LearningPhase.WARM_UP, result.resources().getFirst().phase());
        assertEquals("WEAK_GRAMMAR_POINT", result.resources().getFirst().reasonCode());
        assertEquals(77L, result.resources().getFirst().resource().grammarPointId());
        assertEquals("～てください", result.resources().getFirst().resource().title());
        assertTrue(result.coverageGaps().isEmpty());
    }

    @Test void aboveLevelWeaknessIsNotWarmedUpNorReportedAsGap() {
        var above = WeaknessEvidence.grammar(1L, "PARTICLE", 1L, 5, 3, "N3");
        var result = select(above);
        assertEquals(List.of(21L), ids(result));
        assertEquals(LearningPhase.GOAL_LEARNING, result.resources().getFirst().phase());
        assertTrue(result.coverageGaps().isEmpty());
    }

    @Test void goalCurriculumAddsFullLessonsIgnoringGrammarDecomposition() {
        var grammarCatalog = new LearningCatalogSource() {
            @Override
            public List<LearningResourceCandidate> findPublishedLessons(List<Long> textbookIds) {
                return lessons.stream().filter(lesson -> textbookIds.contains(lesson.textbookId())).toList();
            }

            @Override
            public List<LearningResourceCandidate> findGrammarPointsByLessons(List<Long> lessonIds) {
                // [JLAS-W0108] Đã bỏ phân rã: kết quả này phải bị bỏ qua, GOAL giữ trọn bài.
                return List.of(new LearningResourceCandidate(21L, "～たい", 2L, 20L,
                        Set.of("TARGET"), "N4", 10, 1, 1, 201L, null));
            }
        };
        var grammarSelector = new LearningResourceSelectionServiceImpl(
                grammarCatalog, new WeakTagPolicy(3, new BigDecimal("0.5")));
        var result = grammarSelector.select(7L, profile(List.of(tag("PARTICLE", 5, 0))), scope);
        assertEquals(List.of(21L), ids(result));
        assertEquals("GOAL_TARGET", result.resources().getFirst().reasonCode());
        assertNull(result.resources().getFirst().resource().grammarPointId());
        assertEquals("Lesson 21", result.resources().getFirst().resource().title());
    }

    @Test void warmedUpGrammarPointStillYieldsFullGoalLesson() {
        var grammarCatalog = new LearningCatalogSource() {
            @Override
            public List<LearningResourceCandidate> findPublishedLessons(List<Long> textbookIds) {
                return lessons.stream().filter(lesson -> textbookIds.contains(lesson.textbookId())).toList();
            }

            @Override
            public Optional<LearningResourceCandidate> findGrammarPoint(Long grammarPointId) {
                if (grammarPointId == null) return Optional.empty();
                return Optional.of(new LearningResourceCandidate(21L, "～たい", 2L, 20L,
                        Set.of("TARGET"), "N4", 10, 1, 1, grammarPointId, null));
            }
        };
        var grammarSelector = new LearningResourceSelectionServiceImpl(
                grammarCatalog, new WeakTagPolicy(3, new BigDecimal("0.5")));
        var result = grammarSelector.select(7L,
                profile(List.of(WeaknessEvidence.grammar(201L, "TARGET", 201L, 5, 3, "N4"))), scope);
        assertEquals(List.of(21L, 21L), ids(result));
        assertEquals(List.of(LearningPhase.WARM_UP, LearningPhase.GOAL_LEARNING),
                result.resources().stream().map(SelectedLearningResource::phase).toList());
        assertEquals(List.of("WEAK_GRAMMAR_POINT", "GOAL_TARGET"),
                result.resources().stream().map(SelectedLearningResource::reasonCode).toList());
        assertEquals(Arrays.asList(201L, null),
                result.resources().stream().map(item -> item.resource().grammarPointId()).toList());
    }

    @Test void vocabWeaknessWarmUpResolvesTheSpecificVocabularyEntry() {
        var vocabCatalog = new LearningCatalogSource() {
            @Override
            public List<LearningResourceCandidate> findPublishedLessons(List<Long> textbookIds) {
                return lessons.stream().filter(lesson -> textbookIds.contains(lesson.textbookId())).toList();
            }

            @Override
            public Optional<LearningResourceCandidate> findVocabularyItem(Long vocabularyEntryId) {
                if (vocabularyEntryId == null) return Optional.empty();
                return Optional.of(new LearningResourceCandidate(11L, "学校", 1L, 10L,
                        Set.of("PARTICLE"), "N5", 5, 1, 1, null, vocabularyEntryId));
            }
        };
        var vocabSelector = new LearningResourceSelectionServiceImpl(
                vocabCatalog, new WeakTagPolicy(3, new BigDecimal("0.5")));
        var result = vocabSelector.select(7L, profile(List.of(WeaknessEvidence.item(
                WeaknessEvidence.SKILL_KANJI, "KANJI:学校", 5, 3, "N5", 77L))), scope);
        assertEquals(List.of(11L, 21L), ids(result));
        assertEquals(LearningPhase.WARM_UP, result.resources().getFirst().phase());
        assertEquals("WEAK_KANJI_ITEM", result.resources().getFirst().reasonCode());
        assertEquals(77L, result.resources().getFirst().resource().vocabularyEntryId());
        assertEquals("学校", result.resources().getFirst().resource().title());
        assertTrue(result.coverageGaps().isEmpty());
    }

    @Test void vocabWeaknessWithoutLessonReportsGap() {
        var result = select(WeaknessEvidence.item(
                WeaknessEvidence.SKILL_VOCABULARY, "VOCAB:ときどき", 5, 3, "N5", 99L));
        assertEquals(List.of(21L), ids(result));
        assertEquals(List.of(new CoverageGap("VOCAB:ときどき", "MISSING_WARM_UP_RESOURCE")), result.coverageGaps());
    }

    @Test void atOrBelowLevelWeaknessIsStillWarmedUp() {
        var below = WeaknessEvidence.grammar(1L, "PARTICLE", 1L, 5, 3, "N5");
        var result = select(below);
        assertEquals(List.of(11L, 21L), ids(result));
        assertEquals(LearningPhase.WARM_UP, result.resources().getFirst().phase());
    }

    @Test void severelyWeakGrammarReviewsAllGrammarLessonsOfCurrentLevel() {
        // [JLAS-W0108] Kỹ năng rất yếu (<40% đúng) → ôn TOÀN BỘ bài ngữ pháp cấp hiện tại
        // (FULL_SKILL_REVIEW), thay vì chỉ vài item sai.
        var profile = new LearningProfileSnapshot(7L, 91L, 10L, "1", true, true,
                "N4", "N4", "JLPT", 30,
                List.of(tag("PARTICLE", 5, 4)),
                List.of(new SkillScore("GRAMMAR", 5, 4)));
        var result = selector.select(7L, profile, scope);
        assertEquals(List.of(11L, 12L, 21L), ids(result));
        assertEquals(List.of(LearningPhase.WARM_UP, LearningPhase.WARM_UP, LearningPhase.GOAL_LEARNING),
                result.resources().stream().map(SelectedLearningResource::phase).toList());
        assertEquals(List.of("FULL_SKILL_REVIEW", "FULL_SKILL_REVIEW", "GOAL_TARGET"),
                result.resources().stream().map(SelectedLearningResource::reasonCode).toList());
        assertTrue(result.coverageGaps().isEmpty());
    }

    @Test void overallAboveSixtyPercentCorrectSkipsWarmUp() {
        // [JLAS-W0108] Tổng % đúng > 60% → pass cấp hiện tại (cấp tự chọn survey), vào thẳng GOAL.
        var profile = new LearningProfileSnapshot(7L, 91L, 10L, "1", true, true,
                "N4", "N4", "JLPT", 30,
                List.of(tag("PARTICLE", 5, 1)),
                List.of(new SkillScore("KANJI", 5, 0),
                        new SkillScore("VOCABULARY", 5, 1),
                        new SkillScore("GRAMMAR", 5, 1),
                        new SkillScore("READING", 5, 2),
                        new SkillScore("LISTENING", 5, 1)));
        var result = selector.select(7L, profile, scope);
        assertEquals(List.of(21L), ids(result));
        assertEquals(LearningPhase.GOAL_LEARNING, result.resources().getFirst().phase());
        assertTrue(result.coverageGaps().isEmpty());
    }

    @Test void passedSkillIsSkippedEvenWithWrongItems() {
        // [JLAS-W0108] VOCAB 80% đúng → đạt; dù có item sai vẫn KHÔNG warm-up (flashcard) và
        // không báo gap, vì kỹ năng đã pass (>60% đúng).
        var profile = new LearningProfileSnapshot(7L, 91L, 10L, "1", true, true,
                "N4", "N4", "JLPT", 30,
                List.of(WeaknessEvidence.item("VOCABULARY", "VOCAB:学校", 10, 2, "N5", 77L)),
                List.of(new SkillScore("VOCABULARY", 10, 2)));
        var result = selector.select(7L, profile, scope);
        assertEquals(List.of(21L), ids(result));
        assertEquals(LearningPhase.GOAL_LEARNING, result.resources().getFirst().phase());
        assertTrue(result.coverageGaps().isEmpty());
    }

    @Test void oneLessonCanCoverMultipleWeakTagsWithoutDuplicatingIt() {
        lessons.set(0, lesson(11, 1, "N5", 15, 1, "PARTICLE", "VERB"));
        var result = select(tag("PARTICLE", 5, 3), tag("VERB", 5, 3));
        assertEquals(List.of(11L, 21L), ids(result));
        assertEquals(Set.of("PARTICLE", "VERB"), result.resources().getFirst().matchedTagCodes());
    }

    @Test void lessonSharedByBothPhasesAppearsOnlyInWarmUp() {
        var sharedScope = new LearningCatalogScope(List.of(2L), List.of(2L));
        var result = selector.select(7L, profile(List.of(tag("TARGET", 5, 4))), sharedScope);
        assertEquals(List.of(21L), ids(result));
        assertEquals(LearningPhase.WARM_UP, result.resources().getFirst().phase());
        assertTrue(result.coverageGaps().isEmpty());
    }

    @Test void ignoresUnknownLevelsAndLessonsAboveTarget() {
        lessons.add(lesson(22, 2, "N3", 20, 0, "ADVANCED"));
        lessons.add(lesson(23, 2, "UNKNOWN", 20, 0, "UNKNOWN"));
        lessons.add(lesson(24, 2, null, 20, 0, "UNCLASSIFIED"));
        assertEquals(List.of(21L), ids(select(tag("PARTICLE", 5, 0))));
    }

    @Test void fromZeroWarmsUpKanaFoundationThenStudiesGoalLessons() {
        // [JLAS-W0108] Người chưa từng học (BELOW_N5, không có attempt): warm-up = bài bảng chữ cái
        // (level null), rồi học N5..target. Không báo gap "NO_ASSESSMENT_EVIDENCE".
        var kana = lesson(51, 3, null, 20, 1, "KANA-H-01");
        var kanaCatalog = new LearningCatalogSource() {
            @Override
            public List<LearningResourceCandidate> findPublishedLessons(List<Long> textbookIds) {
                var all = new ArrayList<>(lessons);
                all.add(kana);
                return all.stream().filter(lesson -> textbookIds.contains(lesson.textbookId())).toList();
            }
        };
        var kanaSelector = new LearningResourceSelectionServiceImpl(kanaCatalog,
                new WeakTagPolicy(3, new BigDecimal("0.5")));
        var fromZero = new LearningProfileSnapshot(7L, null, 10L, "from-zero-v1", true, true,
                "BELOW_N5", "N5", "JLPT", 30, List.of());
        var fromZeroScope = new LearningCatalogScope(List.of(3L), List.of(1L));

        var result = kanaSelector.select(7L, fromZero, fromZeroScope);

        assertEquals(List.of(51L, 11L, 12L), ids(result));
        assertEquals(List.of(LearningPhase.WARM_UP, LearningPhase.GOAL_LEARNING, LearningPhase.GOAL_LEARNING),
                result.resources().stream().map(SelectedLearningResource::phase).toList());
        assertEquals("KANA_FOUNDATION", result.resources().getFirst().reasonCode());
        assertTrue(result.coverageGaps().isEmpty());
    }

    @Test void noGoalResourcesIsNotFoundEvenWhenWarmUpExists() {
        lessons.removeIf(lesson -> lesson.textbookId() == 2L);
        assertError(ErrorCode.NOT_FOUND, () -> select(tag("PARTICLE", 5, 4)));
    }

    @Test void catalogOrderIsDeterministicRegardlessOfSourceOrder() {
        lessons.add(lesson(22, 2, "N4", 20, 2, "SECOND"));
        lessons.add(lesson(23, 2, "N4", 20, 2, "THIRD"));
        java.util.Collections.reverse(lessons);
        assertEquals(List.of(21L, 22L, 23L), ids(select(tag("PARTICLE", 5, 0))));
    }

    @Test void selectedLessonWithInvalidDurationFailsInsteadOfMakingAnImpossibleSchedule() {
        lessons.set(2, lesson(21, 2, "N4", 0, 1, "TARGET"));
        assertError(ErrorCode.CONFLICT, () -> select(tag("PARTICLE", 5, 0)));
    }

    @Test void rejectsForeignProfile() {
        assertError(ErrorCode.FORBIDDEN, () -> selector.select(8L, profile(validTags()), scope));
    }

    @Test void rejectsAnonymousCaller() {
        assertError(ErrorCode.UNAUTHORIZED, () -> selector.select(null, profile(validTags()), scope));
    }

    @Test void requiresCompletedSurveyAndFinalResults() {
        for (boolean survey : List.of(true, false)) {
            var profile = new LearningProfileSnapshot(7L, 91L, 10L, "1", survey, !survey,
                    "N4", "N4", "JLPT", 30, validTags());
            assertError(ErrorCode.CONFLICT, () -> selector.select(7L, profile, scope));
        }
    }

    @Test void missingTagEvidenceStillYieldsGoalLearningWithCoverageGap() {
        for (var tags : Arrays.asList(List.<WeaknessEvidence>of(), null)) {
            var result = selector.select(7L, profile(tags), scope);
            assertEquals(List.of(21L), ids(result));
            assertEquals(List.of(new CoverageGap("ASSESSMENT", "NO_ASSESSMENT_EVIDENCE")), result.coverageGaps());
        }
    }

    @Test void rejectsInvalidAndDuplicateTagEvidence() {
        for (var tags : List.of(
                List.of(WeaknessEvidence.grammar(1L, "PARTICLE", 1L, 2, 3)),
                List.of(WeaknessEvidence.grammar(1L, "PARTICLE", 1L, -1, 0)),
                List.of(WeaknessEvidence.grammar(1L, "PARTICLE", 1L, 3, -1)),
                List.of(new WeaknessEvidence("", "GP:1", 3, 2, null, null, null, null, null, null, null, null)),
                List.of(WeaknessEvidence.grammar(1L, "PARTICLE", 1L, 3, 2),
                        WeaknessEvidence.grammar(1L, "PARTICLE", 1L, 3, 3)))) {
            assertError(ErrorCode.VALIDATION_FAILED, () -> selector.select(7L, profile(tags), scope));
        }
    }

    @ParameterizedTest
    @CsvSource({"0,N4,N4,1", "181,N4,N4,1", "30,N3,N4,1", "30,UNKNOWN,N4,1", "30,N4,N4,''"})
    void validatesBudgetLevelsAndRevision(int minutes, String assessed, String target, String revision) {
        var profile = new LearningProfileSnapshot(7L, 91L, 10L, revision, true, true,
                assessed, target, "JLPT", minutes, validTags());
        assertError(ErrorCode.VALIDATION_FAILED, () -> selector.select(7L, profile, scope));
    }

    @Test void unsupportedGoalsNeverFallBackToJlpt() {
        for (String goal : List.of("TRAVEL", "IT", "BA")) {
            var profile = new LearningProfileSnapshot(7L, 91L, 10L, "1", true, true,
                    "N4", "N4", goal, 30, validTags());
            assertError(ErrorCode.SERVICE_UNAVAILABLE, () -> selector.select(7L, profile, scope));
        }
    }

    @Test void requiresExplicitGoalCurriculumScope() {
        for (var books : List.of(List.<Long>of(), List.of(0L), List.of(2L, 2L))) {
            assertError(ErrorCode.VALIDATION_FAILED, () -> selector.select(7L, profile(validTags()),
                    new LearningCatalogScope(List.of(1L), books)));
        }
    }

    @Test void resultDoesNotExposeMutableSelectionLists() {
        var result = select(tag("PARTICLE", 5, 4));
        assertThrows(UnsupportedOperationException.class, () -> result.resources().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.coverageGaps().clear());
    }

    private LearningResourceSelectionServiceImpl selector(int minimum, String threshold) {
        return new LearningResourceSelectionServiceImpl(catalog, new WeakTagPolicy(minimum, new BigDecimal(threshold)));
    }

    private LearningSelectionResult select(WeaknessEvidence... tags) {
        return selector.select(7L, profile(Arrays.asList(tags)), scope);
    }

    /** Grammar evidence với grammarPointId duy nhất; tagCode dùng để khớp lesson warm-up. */
    private static final AtomicLong GP_SEQ = new AtomicLong();

    private static WeaknessEvidence tag(String code, int assessed, int wrong) {
        long id = GP_SEQ.incrementAndGet();
        return WeaknessEvidence.grammar(id, code, id, assessed, wrong);
    }

    private static List<WeaknessEvidence> validTags() {
        return List.of(tag("PARTICLE", 5, 3));
    }

    private static LearningProfileSnapshot profile(List<WeaknessEvidence> tags) {
        return new LearningProfileSnapshot(7L, 91L, 10L, "1", true, true, "N4", "N4", "JLPT", 30, tags);
    }

    private static LearningResourceCandidate lesson(long id, long book, String level, int minutes, int order, String... tags) {
        return new LearningResourceCandidate(id, "Lesson " + id, book, book * 10,
                Set.of(tags), level, minutes, 1, order, null, null, "GRAMMAR");
    }

    private static List<Long> ids(LearningSelectionResult result) {
        return result.resources().stream().map(item -> item.resource().lessonId()).toList();
    }

    private static void assertError(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ApiException.class, action).getErrorCode());
    }
}
