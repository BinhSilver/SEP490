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
import java.util.Set;
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
        var result = select(new TagAssessmentResult("PARTICLE", 5, 4),
                new TagAssessmentResult("VERB", 5, 2), new TagAssessmentResult("UNKNOWN", 1, 1));
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
        var result = select(new TagAssessmentResult("PARTICLE", 10, 6),
                new TagAssessmentResult("VERB", 4, 3), new TagAssessmentResult("ADJECTIVE", 4, 3));
        assertEquals(List.of(13L, 12L, 11L, 21L), ids(result));
        assertEquals(45, result.resources().get(1).resource().estimatedMinutes());
    }

    @ParameterizedTest
    @CsvSource({"2,2,false", "3,1,false", "4,2,true", "3,2,true", "0,0,false"})
    void respectsSampleAndRatioBoundaries(int assessed, int wrong, boolean weak) {
        var result = select(new TagAssessmentResult("PARTICLE", assessed, wrong));
        assertEquals(weak ? List.of(11L, 21L) : List.of(21L), ids(result));
        assertEquals(assessed < 3 ? 1 : 0, result.coverageGaps().size());
    }

    @Test void thresholdsAreConfigurableAndIncludedInPolicyIdentity() {
        var profile = profile(List.of(new TagAssessmentResult("PARTICLE", 2, 1)));
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
        var result = select(new TagAssessmentResult("PARTICLE", 5, 0));
        assertEquals(List.of(21L), ids(result));
        assertEquals(LearningPhase.GOAL_LEARNING, result.resources().getFirst().phase());
        assertTrue(result.coverageGaps().isEmpty());
    }

    @Test void missingWarmUpResourcesAreReportedWithoutInventingLessons() {
        var result = select(new TagAssessmentResult("MISSING", 5, 4));
        assertEquals(List.of(21L), ids(result));
        assertEquals(List.of(new CoverageGap("MISSING", "MISSING_WARM_UP_RESOURCE")), result.coverageGaps());
    }

    @Test void oneLessonCanCoverMultipleWeakTagsWithoutDuplicatingIt() {
        lessons.set(0, lesson(11, 1, "N5", 15, 1, "PARTICLE", "VERB"));
        var result = select(new TagAssessmentResult("PARTICLE", 5, 5), new TagAssessmentResult("VERB", 5, 4));
        assertEquals(List.of(11L, 21L), ids(result));
        assertEquals(Set.of("PARTICLE", "VERB"), result.resources().getFirst().matchedTagCodes());
    }

    @Test void lessonSharedByBothPhasesAppearsOnlyInWarmUp() {
        var sharedScope = new LearningCatalogScope(List.of(2L), List.of(2L));
        var result = selector.select(7L, profile(List.of(new TagAssessmentResult("TARGET", 5, 4))), sharedScope);
        assertEquals(List.of(21L), ids(result));
        assertEquals(LearningPhase.WARM_UP, result.resources().getFirst().phase());
        assertTrue(result.coverageGaps().isEmpty());
    }

    @Test void ignoresUnknownLevelsAndLessonsAboveTarget() {
        lessons.add(lesson(22, 2, "N3", 20, 0, "ADVANCED"));
        lessons.add(lesson(23, 2, "UNKNOWN", 20, 0, "UNKNOWN"));
        lessons.add(lesson(24, 2, null, 20, 0, "UNCLASSIFIED"));
        assertEquals(List.of(21L), ids(select(new TagAssessmentResult("PARTICLE", 5, 0))));
    }

    @Test void noGoalResourcesIsNotFoundEvenWhenWarmUpExists() {
        lessons.removeIf(lesson -> lesson.textbookId() == 2L);
        assertError(ErrorCode.NOT_FOUND, () -> select(new TagAssessmentResult("PARTICLE", 5, 4)));
    }

    @Test void catalogOrderIsDeterministicRegardlessOfSourceOrder() {
        lessons.add(lesson(22, 2, "N4", 20, 2, "SECOND"));
        lessons.add(lesson(23, 2, "N4", 20, 2, "THIRD"));
        java.util.Collections.reverse(lessons);
        assertEquals(List.of(21L, 22L, 23L), ids(select(new TagAssessmentResult("PARTICLE", 5, 0))));
    }

    @Test void selectedLessonWithInvalidDurationFailsInsteadOfMakingAnImpossibleSchedule() {
        lessons.set(2, lesson(21, 2, "N4", 0, 1, "TARGET"));
        assertError(ErrorCode.CONFLICT, () -> select(new TagAssessmentResult("PARTICLE", 5, 0)));
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

    @Test void rejectsMissingTagResultsInsteadOfTreatingThemAsMastery() {
        assertError(ErrorCode.CONFLICT, () -> selector.select(7L, profile(List.of()), scope));
        assertError(ErrorCode.CONFLICT, () -> selector.select(7L, profile(null), scope));
    }

    @Test void rejectsInvalidAndDuplicateTagEvidence() {
        for (var tags : List.of(List.of(new TagAssessmentResult("PARTICLE", 2, 3)),
                List.of(new TagAssessmentResult("PARTICLE", -1, 0)),
                List.of(new TagAssessmentResult("PARTICLE", 3, -1)),
                List.of(new TagAssessmentResult(" ", 3, 2)),
                List.of(new TagAssessmentResult("PARTICLE", 3, 2), new TagAssessmentResult("PARTICLE", 3, 3)))) {
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
        var result = select(new TagAssessmentResult("PARTICLE", 5, 4));
        assertThrows(UnsupportedOperationException.class, () -> result.resources().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.coverageGaps().clear());
    }

    private LearningResourceSelectionServiceImpl selector(int minimum, String threshold) {
        return new LearningResourceSelectionServiceImpl(catalog, new WeakTagPolicy(minimum, new BigDecimal(threshold)));
    }

    private LearningSelectionResult select(TagAssessmentResult... tags) {
        return selector.select(7L, profile(Arrays.asList(tags)), scope);
    }

    private static List<TagAssessmentResult> validTags() {
        return List.of(new TagAssessmentResult("PARTICLE", 5, 4));
    }

    private static LearningProfileSnapshot profile(List<TagAssessmentResult> tags) {
        return new LearningProfileSnapshot(7L, 91L, 10L, "1", true, true, "N4", "N4", "JLPT", 30, tags);
    }

    private static LearningResourceCandidate lesson(long id, long book, String level, int minutes, int order, String... tags) {
        return new LearningResourceCandidate(id, "Lesson " + id, book, book * 10,
                Set.of(tags), level, minutes, 1, order);
    }

    private static List<Long> ids(LearningSelectionResult result) {
        return result.resources().stream().map(item -> item.resource().lessonId()).toList();
    }

    private static void assertError(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ApiException.class, action).getErrorCode());
    }
}
