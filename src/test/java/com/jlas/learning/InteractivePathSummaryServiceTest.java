package com.jlas.learning;

import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.SkillScore;
import com.jlas.learning.dto.WeaknessEvidence;
import com.jlas.learning.entity.LearningMicroCheckState;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningPackageStatus;
import com.jlas.learning.dto.MockTestDestination;
import com.jlas.learning.repository.LearningMicroCheckStateRepository;
import com.jlas.learning.repository.LearningPathPackageRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.service.LearningProfileSource;
import com.jlas.learning.service.MockTestDestinationProvider;
import com.jlas.learning.service.impl.InteractivePathSummaryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** [JLAS-W0193] Task 10 — {@link InteractivePathSummaryServiceImpl}: first/latest/best aggregation across
 * the whole path (never one package), explicit unresolved-batch surfacing (no cross-skill compensation),
 * Dialogue completion-only reporting, Smart-Test-sourced Reading/Listening evidence, readiness confidence
 * capped at MEDIUM, and certificate eligibility mirroring the certificate page's own gate. */
@ExtendWith(MockitoExtension.class)
class InteractivePathSummaryServiceTest {
    @Mock UserLearningPathRepository paths;
    @Mock UserLearningPathItemRepository items;
    @Mock LearningPathPackageRepository packages;
    @Mock LearningMicroCheckStateRepository microCheckStates;
    @Mock LearningProfileSource profileSource;
    @Mock MockTestDestinationProvider mockTestDestinationProvider;
    InteractivePathSummaryServiceImpl service;

    @BeforeEach void setUp() {
        service = new InteractivePathSummaryServiceImpl(paths, items, packages, microCheckStates,
                mockTestDestinationProvider);
        setField("profileSource", profileSource);
    }

    private void setField(String name, Object value) {
        try {
            var field = InteractivePathSummaryServiceImpl.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(service, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test void throwsNotFoundWhenUserHasNoInteractivePath() {
        when(paths.findTopByUserIdAndExperienceVersionOrderByIdDesc(7L, LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(Optional.empty());
        ApiException error = assertThrows(ApiException.class, () -> service.summary(7L));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test void skillWithNoValidAttemptHasNoEvidenceAndIsNeverTreatedAsZero() {
        var path = interactivePath();
        stubPath(path);
        var vocab = activity(1L, LearningActivityType.VOCABULARY, LearningActivityState.STUDYING);
        when(items.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(vocab));
        when(microCheckStates.findByActivityIdInOrderByActivityIdAscBatchIndexAsc(List.of(1L)))
                .thenReturn(List.of());
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());

        var result = service.summary(7L);

        var vocabProgress = skill(result, "VOCABULARY");
        assertFalse(vocabProgress.hasEvidence());
        assertNull(vocabProgress.firstScore());
        assertFalse(vocabProgress.resolved());
    }

    @Test void firstLatestBestAggregateAcrossMultipleActivitiesOfTheSameSkillInThePath() {
        var path = interactivePath();
        stubPath(path);
        var vocab1 = activity(1L, LearningActivityType.VOCABULARY, LearningActivityState.PASSED);
        var vocab2 = activity(2L, LearningActivityType.VOCABULARY, LearningActivityState.PASSED);
        when(items.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(vocab1, vocab2));
        var earlyState = state(vocab1, 0, 1, true, 60, 60, 70,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 0), false, null);
        var lateState = state(vocab2, 0, 1, true, 90, 95, 95,
                LocalDateTime.of(2026, 1, 5, 0, 0), LocalDateTime.of(2026, 1, 5, 0, 0), false, null);
        when(microCheckStates.findByActivityIdInOrderByActivityIdAscBatchIndexAsc(List.of(1L, 2L)))
                .thenReturn(List.of(earlyState, lateState));
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());

        var result = service.summary(7L);

        var vocabProgress = skill(result, "VOCABULARY");
        assertTrue(vocabProgress.hasEvidence());
        assertEquals(60, vocabProgress.firstScore());
        assertEquals(95, vocabProgress.latestScore());
        assertEquals(95, vocabProgress.bestScore());
        assertTrue(vocabProgress.resolved());
        assertTrue(vocabProgress.unresolved().isEmpty());
    }

    @Test void attemptedButNotPassedBatchIsSurfacedAsUnresolvedNotAveragedAway() {
        var path = interactivePath();
        stubPath(path);
        var grammar = activity(3L, LearningActivityType.GRAMMAR, LearningActivityState.COOLDOWN);
        when(items.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(grammar));
        var cooldownAt = LocalDateTime.of(2026, 1, 10, 0, 0);
        var failingState = state(grammar, 0, 3, false, 40, 40, 55,
                LocalDateTime.of(2026, 1, 9, 0, 0), LocalDateTime.of(2026, 1, 9, 1, 0), true, cooldownAt);
        when(microCheckStates.findByActivityIdInOrderByActivityIdAscBatchIndexAsc(List.of(3L)))
                .thenReturn(List.of(failingState));
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());

        var result = service.summary(7L);

        var grammarProgress = skill(result, "GRAMMAR");
        assertTrue(grammarProgress.hasEvidence());
        assertFalse(grammarProgress.resolved());
        assertEquals(1, grammarProgress.unresolved().size());
        var unresolved = grammarProgress.unresolved().get(0);
        assertEquals(3L, unresolved.activityId());
        assertTrue(unresolved.reviewRequired());
        assertEquals(cooldownAt, unresolved.cooldownUntil());
    }

    @Test void issuedButNeverAttemptedStateDoesNotCountAsEvidence() {
        var path = interactivePath();
        stubPath(path);
        var vocab = activity(1L, LearningActivityType.VOCABULARY, LearningActivityState.STUDYING);
        when(items.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(vocab));
        var neverAttempted = state(vocab, 0, 0, false, null, null, null,
                LocalDateTime.now(), LocalDateTime.now(), false, null);
        when(microCheckStates.findByActivityIdInOrderByActivityIdAscBatchIndexAsc(List.of(1L)))
                .thenReturn(List.of(neverAttempted));
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());

        var result = service.summary(7L);

        assertFalse(skill(result, "VOCABULARY").hasEvidence());
    }

    @Test void dialogueSummaryCountsCompletionAcrossThePathAndNeverProducesAScore() {
        var path = interactivePath();
        stubPath(path);
        var completed = activity(4L, LearningActivityType.DIALOGUE, LearningActivityState.COMPLETED);
        var inProgress = activity(5L, LearningActivityType.DIALOGUE, LearningActivityState.STUDYING);
        when(items.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(completed, inProgress));
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());

        var result = service.summary(7L);

        assertEquals(2, result.dialogue().totalCount());
        assertEquals(1, result.dialogue().completedCount());
        assertFalse(result.dialogue().allCompleted());
    }

    @Test void comprehensionHasNoEvidenceWhenProfileSourceIsUnavailable() {
        var path = interactivePath();
        path.setAssessmentAttemptId(null);
        stubPath(path);
        when(items.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());

        var result = service.summary(7L);

        assertTrue(result.comprehension().stream().noneMatch(c -> c.hasEvidence()));
        assertTrue(result.comprehension().stream().allMatch(c -> c.note().contains("chưa được đánh giá lại")));
    }

    @Test void comprehensionMarksWeakBelowTheSeventyPercentBoundaryFromSmartTest() {
        var path = interactivePath();
        stubPath(path);
        when(items.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());
        var snapshot = new LearningProfileSnapshot(7L, 99L, null, "rev", true, true, "N5", "N4", "JLPT", 30,
                List.of(), List.of(new SkillScore(WeaknessEvidence.SKILL_READING, 10, 6),
                        new SkillScore(WeaknessEvidence.SKILL_LISTENING, 10, 1)));
        when(profileSource.loadForUser(7L, 99L)).thenReturn(snapshot);

        var result = service.summary(7L);

        var reading = result.comprehension().stream().filter(c -> c.skill().equals("READING")).findFirst().orElseThrow();
        var listening = result.comprehension().stream().filter(c -> c.skill().equals("LISTENING")).findFirst().orElseThrow();
        assertTrue(reading.hasEvidence());
        assertTrue(reading.weak()); // 40% correct -> weak
        assertTrue(listening.hasEvidence());
        assertFalse(listening.weak()); // 90% correct -> not weak
    }

    @Test void readinessIsLowWhenAnyScoredSkillHasNoEvidenceYet() {
        var path = interactivePath();
        path.setAssessmentAttemptId(null);
        stubPath(path);
        when(items.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());

        var result = service.summary(7L);

        assertEquals("LOW", result.readiness().level());
        assertTrue(result.readiness().explanation().contains("VOCABULARY"));
    }

    @Test void readinessIsLowWhenASkillHasUnresolvedEvidenceEvenIfOthersAreStrong() {
        var path = interactivePath();
        stubPath(path);
        var vocab = activity(1L, LearningActivityType.VOCABULARY, LearningActivityState.PASSED);
        var kanji = activity(2L, LearningActivityType.KANJI, LearningActivityState.PASSED);
        var grammar = activity(3L, LearningActivityType.GRAMMAR, LearningActivityState.COOLDOWN);
        when(items.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(vocab, kanji, grammar));
        when(microCheckStates.findByActivityIdInOrderByActivityIdAscBatchIndexAsc(List.of(1L)))
                .thenReturn(List.of(state(vocab, 0, 1, true, 95, 95, 95,
                        LocalDateTime.now(), LocalDateTime.now(), false, null)));
        when(microCheckStates.findByActivityIdInOrderByActivityIdAscBatchIndexAsc(List.of(2L)))
                .thenReturn(List.of(state(kanji, 0, 1, true, 95, 95, 95,
                        LocalDateTime.now(), LocalDateTime.now(), false, null)));
        when(microCheckStates.findByActivityIdInOrderByActivityIdAscBatchIndexAsc(List.of(3L)))
                .thenReturn(List.of(state(grammar, 0, 3, false, 40, 40, 55,
                        LocalDateTime.now(), LocalDateTime.now(), true, LocalDateTime.now().plusHours(1))));
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());
        var snapshot = new LearningProfileSnapshot(7L, 99L, null, "rev", true, true, "N5", "N4", "JLPT", 30,
                List.of(), List.of(new SkillScore(WeaknessEvidence.SKILL_READING, 10, 0),
                        new SkillScore(WeaknessEvidence.SKILL_LISTENING, 10, 0)));
        when(profileSource.loadForUser(7L, 99L)).thenReturn(snapshot);

        var result = service.summary(7L);

        // A persistently weak Grammar batch must not be averaged away by strong Vocabulary/Kanji scores.
        assertEquals("LOW", result.readiness().level());
        assertTrue(result.readiness().explanation().contains("GRAMMAR"));
    }

    @Test void readinessNeverReachesHighEvenWhenEverythingIsResolvedAndStrong() {
        var path = interactivePath();
        stubPath(path);
        var vocab = activity(1L, LearningActivityType.VOCABULARY, LearningActivityState.PASSED);
        var kanji = activity(2L, LearningActivityType.KANJI, LearningActivityState.PASSED);
        var grammar = activity(3L, LearningActivityType.GRAMMAR, LearningActivityState.PASSED);
        var dialogue = activity(4L, LearningActivityType.DIALOGUE, LearningActivityState.COMPLETED);
        when(items.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(vocab, kanji, grammar, dialogue));
        for (var a : List.of(vocab, kanji, grammar)) {
            when(microCheckStates.findByActivityIdInOrderByActivityIdAscBatchIndexAsc(List.of(a.getId())))
                    .thenReturn(List.of(state(a, 0, 1, true, 95, 95, 95,
                            LocalDateTime.now(), LocalDateTime.now(), false, null)));
        }
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());
        var snapshot = new LearningProfileSnapshot(7L, 99L, null, "rev", true, true, "N5", "N4", "JLPT", 30,
                List.of(), List.of(new SkillScore(WeaknessEvidence.SKILL_READING, 10, 0),
                        new SkillScore(WeaknessEvidence.SKILL_LISTENING, 10, 0)));
        when(profileSource.loadForUser(7L, 99L)).thenReturn(snapshot);

        var result = service.summary(7L);

        assertEquals("MEDIUM", result.readiness().level());
        assertNotEquals("HIGH", result.readiness().level());
    }

    // ---- RC Task 6: in-path Reading/Listening evidence distinct from the Smart Test baseline ----

    @Test void inPathReadingEvidenceIsReportedSeparatelyFromAndNeverOverwritesTheSmartTestBaseline() {
        var path = interactivePath();
        stubPath(path);
        var reading = activity(6L, LearningActivityType.READING, LearningActivityState.PASSED);
        when(items.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(reading));
        when(microCheckStates.findByActivityIdInOrderByActivityIdAscBatchIndexAsc(List.of(6L)))
                .thenReturn(List.of(state(reading, 0, 1, true, 85, 85, 85,
                        LocalDateTime.now(), LocalDateTime.now(), false, null)));
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());
        // Smart Test baseline says READING was weak (40% correct) — the in-path evidence (85%, passed) must
        // be shown alongside it, never silently replacing or averaging into that baseline number.
        var snapshot = new LearningProfileSnapshot(7L, 99L, null, "rev", true, true, "N5", "N4", "JLPT", 30,
                List.of(), List.of(new SkillScore(WeaknessEvidence.SKILL_READING, 10, 6),
                        new SkillScore(WeaknessEvidence.SKILL_LISTENING, 10, 0)));
        when(profileSource.loadForUser(7L, 99L)).thenReturn(snapshot);

        var result = service.summary(7L);

        var baselineReading = result.comprehension().stream()
                .filter(c -> c.skill().equals("READING")).findFirst().orElseThrow();
        assertTrue(baselineReading.hasEvidence());
        assertEquals(40, baselineReading.correctPercent()); // untouched Smart Test baseline
        assertTrue(baselineReading.weak());

        var inPathReading = result.comprehensionInPath().stream()
                .filter(c -> c.skill().equals("READING")).findFirst().orElseThrow();
        assertTrue(inPathReading.hasEvidence());
        assertEquals(85, inPathReading.latestScore());
        assertTrue(inPathReading.resolved());
    }

    @Test void comprehensionInPathHasNoEvidenceWhenNeverAttemptedEvenIfBaselineExists() {
        var path = interactivePath();
        stubPath(path);
        when(items.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());
        var snapshot = new LearningProfileSnapshot(7L, 99L, null, "rev", true, true, "N5", "N4", "JLPT", 30,
                List.of(), List.of(new SkillScore(WeaknessEvidence.SKILL_READING, 10, 2),
                        new SkillScore(WeaknessEvidence.SKILL_LISTENING, 10, 2)));
        when(profileSource.loadForUser(7L, 99L)).thenReturn(snapshot);

        var result = service.summary(7L);

        assertTrue(result.comprehensionInPath().stream().noneMatch(c -> c.hasEvidence()));
    }

    @Test void unresolvedInPathComprehensionBatchForcesLowReadinessEvenWhenEverythingElseIsStrong() {
        var path = interactivePath();
        stubPath(path);
        var vocab = activity(1L, LearningActivityType.VOCABULARY, LearningActivityState.PASSED);
        var kanji = activity(2L, LearningActivityType.KANJI, LearningActivityState.PASSED);
        var grammar = activity(3L, LearningActivityType.GRAMMAR, LearningActivityState.PASSED);
        var listening = activity(7L, LearningActivityType.LISTENING, LearningActivityState.COOLDOWN);
        when(items.findByLearningPathIdOrderByOrderIndex(2L))
                .thenReturn(List.of(vocab, kanji, grammar, listening));
        for (var a : List.of(vocab, kanji, grammar)) {
            when(microCheckStates.findByActivityIdInOrderByActivityIdAscBatchIndexAsc(List.of(a.getId())))
                    .thenReturn(List.of(state(a, 0, 1, true, 95, 95, 95,
                            LocalDateTime.now(), LocalDateTime.now(), false, null)));
        }
        var cooldownAt = LocalDateTime.now().plusHours(1);
        when(microCheckStates.findByActivityIdInOrderByActivityIdAscBatchIndexAsc(List.of(7L)))
                .thenReturn(List.of(state(listening, 0, 3, false, 40, 40, 55,
                        LocalDateTime.now(), LocalDateTime.now(), true, cooldownAt)));
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());
        // Smart Test baseline for Listening looks perfectly strong (0 wrong) — must not hide the fact that
        // the learner just failed a real, in-path Listening micro-check batch.
        var snapshot = new LearningProfileSnapshot(7L, 99L, null, "rev", true, true, "N5", "N4", "JLPT", 30,
                List.of(), List.of(new SkillScore(WeaknessEvidence.SKILL_READING, 10, 0),
                        new SkillScore(WeaknessEvidence.SKILL_LISTENING, 10, 0)));
        when(profileSource.loadForUser(7L, 99L)).thenReturn(snapshot);

        var result = service.summary(7L);

        assertEquals("LOW", result.readiness().level());
        assertTrue(result.readiness().explanation().contains("LISTENING"));
        var inPathListening = result.comprehensionInPath().stream()
                .filter(c -> c.skill().equals("LISTENING")).findFirst().orElseThrow();
        assertFalse(inPathListening.resolved());
        assertEquals(1, inPathListening.unresolved().size());
    }

    @Test void certificateEligibilityIsUnaffectedByComprehensionActivitiesInsideAWarmUpPackage() {
        var path = interactivePath();
        stubPath(path);
        var reading = activity(6L, LearningActivityType.READING, LearningActivityState.PASSED);
        when(items.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(reading));
        when(microCheckStates.findByActivityIdInOrderByActivityIdAscBatchIndexAsc(List.of(6L)))
                .thenReturn(List.of(state(reading, 0, 1, true, 90, 90, 90,
                        LocalDateTime.now(), LocalDateTime.now(), false, null)));
        var warmUp = LearningPathPackage.builder().id(10L).learningPath(path).phase("WARM_UP")
                .status(LearningPackageStatus.COMPLETED).orderIndex(1).dayNumber(1)
                .title("Ôn tập đọc/nghe").estimatedMinutes(15).build();
        var target = LearningPathPackage.builder().id(11L).learningPath(path).phase("GOAL_LEARNING")
                .status(LearningPackageStatus.COMPLETED).orderIndex(2).dayNumber(2)
                .title("Ngày 1").estimatedMinutes(30).build();
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(warmUp, target));

        var result = service.summary(7L);

        // Certificate eligibility depends only on GOAL_LEARNING package completion (unchanged rule); a
        // completed comprehension warm-up package must not be required nor able to unlock it by itself.
        assertTrue(result.certificateEligible());
    }

    @Test void certificateEligibleOnlyWhenEveryGoalLearningPackageIsCompleted() {
        var path = interactivePath();
        stubPath(path);
        when(items.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());
        var warmUp = LearningPathPackage.builder().id(10L).learningPath(path).phase("WARM_UP")
                .status(LearningPackageStatus.IN_PROGRESS).orderIndex(1).dayNumber(1)
                .title("Ôn tập").estimatedMinutes(5).build();
        var target1 = LearningPathPackage.builder().id(11L).learningPath(path).phase("GOAL_LEARNING")
                .status(LearningPackageStatus.COMPLETED).orderIndex(2).dayNumber(2)
                .title("Ngày 1").estimatedMinutes(30).build();
        var target2 = LearningPathPackage.builder().id(12L).learningPath(path).phase("GOAL_LEARNING")
                .status(LearningPackageStatus.AVAILABLE).orderIndex(3).dayNumber(3)
                .title("Ngày 2").estimatedMinutes(30).build();
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(warmUp, target1, target2));

        var result = service.summary(7L);

        assertFalse(result.certificateEligible());
    }

    @Test void certificateNotEligibleWhenNoGoalLearningPackageExistsAtAll() {
        var path = interactivePath();
        stubPath(path);
        when(items.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());
        var warmUp = LearningPathPackage.builder().id(10L).learningPath(path).phase("WARM_UP")
                .status(LearningPackageStatus.COMPLETED).orderIndex(1).dayNumber(1)
                .title("Ôn tập").estimatedMinutes(5).build();
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(warmUp));

        var result = service.summary(7L);

        assertFalse(result.certificateEligible());
    }

    @Test void certificateEligibleWhenEveryGoalLearningPackageIsCompleted() {
        var path = interactivePath();
        stubPath(path);
        when(items.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());
        var target1 = LearningPathPackage.builder().id(11L).learningPath(path).phase("GOAL_LEARNING")
                .status(LearningPackageStatus.COMPLETED).orderIndex(1).dayNumber(1)
                .title("Ngày 1").estimatedMinutes(30).build();
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(target1));

        var result = service.summary(7L);

        assertTrue(result.certificateEligible());
    }

    // ---- RC Task 7: Mock Test handoff seam — server-resolved, never fabricated by the summary service ----

    @Test void summaryEchoesWhateverDestinationTheProviderResolvedWithoutAlteringIt() {
        var path = interactivePath();
        stubPath(path);
        when(items.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());
        var destination = new MockTestDestination(true, MockTestDestination.STATUS_AVAILABLE,
                "/mock-test/start", "N4");
        when(mockTestDestinationProvider.resolveDestination("N4")).thenReturn(destination);

        var result = service.summary(7L);

        assertSame(destination, result.mockTest());
    }

    @Test void summaryReportsTheProvidersBlockedIntegrationStatusRatherThanFabricatingADestination() {
        var path = interactivePath();
        stubPath(path);
        when(items.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());
        when(mockTestDestinationProvider.resolveDestination("N4"))
                .thenReturn(MockTestDestination.blocked("N4"));

        var result = service.summary(7L);

        assertFalse(result.mockTest().available());
        assertEquals(MockTestDestination.STATUS_BLOCKED_INTEGRATION, result.mockTest().status());
        assertNull(result.mockTest().url());
    }

    @Test void summaryPassesThePathsOwnTargetLevelToTheProviderNeverAnythingElse() {
        var path = interactivePath(); // targetLevel = "N4"
        stubPath(path);
        when(items.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of());
        when(mockTestDestinationProvider.resolveDestination(anyString()))
                .thenReturn(MockTestDestination.blocked("N4"));

        service.summary(7L);

        verify(mockTestDestinationProvider).resolveDestination("N4");
    }

    // ---- helpers ----

    private void stubPath(UserLearningPath path) {
        when(paths.findTopByUserIdAndExperienceVersionOrderByIdDesc(7L, LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(Optional.of(path));
    }

    private com.jlas.learning.dto.InteractiveSkillProgress skill(
            com.jlas.learning.dto.InteractivePathSummaryResponse result, String skill) {
        return result.skills().stream().filter(s -> s.skill().equals(skill)).findFirst().orElseThrow();
    }

    private UserLearningPath interactivePath() {
        return UserLearningPath.builder().id(2L).user(User.builder().id(7L).build()).title("N5 → N4")
                .goal("JLPT").currentLevel("N5").targetLevel("N4").minutesPerDay(30)
                .assessmentAttemptId(99L)
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).build();
    }

    private UserLearningPathItem activity(Long id, LearningActivityType type, LearningActivityState state) {
        return UserLearningPathItem.builder().id(id).activityType(type).activityState(state).orderIndex(1).build();
    }

    private LearningMicroCheckState state(UserLearningPathItem activity, int batchIndex, int attemptsUsed,
            boolean passed, Integer firstScore, Integer latestScore, Integer bestScore,
            LocalDateTime createdAt, LocalDateTime updatedAt, boolean reviewRequired, LocalDateTime cooldownUntil) {
        return LearningMicroCheckState.builder().activity(activity).batchIndex(batchIndex)
                .attemptsUsed(attemptsUsed).passed(passed).firstScore(firstScore).latestScore(latestScore)
                .bestScore(bestScore).createdAt(createdAt).updatedAt(updatedAt).reviewRequired(reviewRequired)
                .cooldownUntil(cooldownUntil).build();
    }
}
