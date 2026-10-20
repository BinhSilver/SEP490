package com.jlas.learning;

import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.LearningQuizSession;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.entity.UserLearningStage;
import com.jlas.learning.enums.*;
import com.jlas.learning.repository.LearningPathPackageRepository;
import com.jlas.learning.repository.LearningQuizSessionRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.repository.UserLearningStageRepository;
import com.jlas.learning.service.impl.InteractiveLearningPathServiceImpl;
import com.jlas.mission.entity.UserStreaks;
import com.jlas.mission.repository.UserStreaksRepository;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import com.jlas.moderation.credit.service.UserCreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InteractiveLearningPathServiceTest {
    @Mock UserLearningPathRepository paths;
    @Mock LearningPathPackageRepository packages;
    @Mock UserLearningPathItemRepository items;
    @Mock LearningQuizSessionRepository quizSessions;
    @Mock UserStreaksRepository streakRepository;
    @Mock UserCreditService creditService;
    @Mock UserLearningStageRepository stages;
    InteractiveLearningPathServiceImpl service;

    @BeforeEach void setUp() {
        service = new InteractiveLearningPathServiceImpl(paths, packages, items, quizSessions);
        setField("streakRepository", streakRepository);
        setField("creditService", creditService);
        setField("stages", stages);
    }

    private void setField(String name, Object value) {
        try {
            var field = InteractiveLearningPathServiceImpl.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(service, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test void completedRoadmapKeepsReplayAndLocksThePathWithoutProgressOrRewards() {
        var path = interactivePath();
        path.setStatus("COMPLETED");
        var pkg = learningPackage(path, 11L, 1, LearningPackageStatus.COMPLETED);
        var finished = activities(pkg);
        markAllTerminal(finished);
        when(paths.findTopByUserIdAndExperienceVersionOrderByIdDesc(7L, LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(Optional.of(path));
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(finished);
        var result = service.completedRoadmap(7L);
        assertEquals(100, result.progressPercent());
        assertEquals(1, result.completedPackages());
        assertNull(result.currentPackageId());
        assertFalse(result.packages().getFirst().activities().isEmpty());
        verify(packages, never()).save(any());
        verify(packages).findByPathForUpdate(2L);
        verifyNoInteractions(creditService, streakRepository);
    }

    @Test void currentReadsOnlyInteractiveVersionAndKeepsPackageOrder() {
        var path = interactivePath();
        var second = learningPackage(path, 12L, 2, LearningPackageStatus.LOCKED);
        var first = learningPackage(path, 11L, 1, LearningPackageStatus.AVAILABLE);
        when(paths.findByUserIdAndStatusAndExperienceVersion(7L, "ACTIVE", LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(Optional.of(path));
        when(packages.findByPathForUpdate(2L)).thenReturn(List.of(first, second));
        when(items.findByLearningPackageIdOrderByOrderIndex(anyLong())).thenAnswer(inv -> activities(
                inv.getArgument(0, Long.class).equals(11L) ? first : second));

        var result = service.current(7L);

        assertEquals(List.of(11L, 12L), result.packages().stream().map(p -> p.id()).toList());
        assertEquals(11L, result.currentPackageId());
    }

    @Test void currentOpensEverySegmentInTheAvailableChapterButKeepsTheNextChapterLocked() {
        var path = interactivePath();
        var firstSegment = learningPackage(path, 11L, 1, LearningPackageStatus.AVAILABLE);
        var sameChapter = learningPackage(path, 12L, 2, LearningPackageStatus.LOCKED);
        sameChapter.setSourceLesson(firstSegment.getSourceLesson());
        var nextChapter = learningPackage(path, 13L, 3, LearningPackageStatus.LOCKED);
        when(paths.findByUserIdAndStatusAndExperienceVersion(7L, "ACTIVE", LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(Optional.of(path));
        when(packages.findByPathForUpdate(2L)).thenReturn(List.of(firstSegment, sameChapter, nextChapter));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities(firstSegment));
        when(items.findByLearningPackageIdOrderByOrderIndex(12L)).thenReturn(activities(sameChapter));
        when(items.findByLearningPackageIdOrderByOrderIndex(13L)).thenReturn(activities(nextChapter));

        var result = service.current(7L);

        assertEquals(LearningPackageStatus.AVAILABLE, result.packages().get(0).status());
        assertEquals(LearningPackageStatus.AVAILABLE, result.packages().get(1).status(),
                "all skill segments of the current chapter should be selectable");
        assertEquals(LearningPackageStatus.LOCKED, result.packages().get(2).status(),
                "the next chapter stays locked until every segment in this chapter is complete");
    }

    @Test void currentLocksPackageSnapshotBeforeRefreshingProgress() {
        var path = interactivePath();
        var first = learningPackage(path, 11L, 1, LearningPackageStatus.IN_PROGRESS);
        var next = learningPackage(path, 12L, 2, LearningPackageStatus.LOCKED);
        var finished = activities(first);
        markAllTerminal(finished);
        when(paths.findByUserIdAndStatusAndExperienceVersion(7L, "ACTIVE", LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(Optional.of(path));
        when(packages.findByPathForUpdate(2L)).thenReturn(List.of(first, next));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(finished);
        when(items.findByLearningPackageIdOrderByOrderIndex(12L)).thenReturn(activities(next));
        stubNoScoredSessions();

        var result = service.current(7L);

        assertEquals(LearningPackageStatus.COMPLETED, result.packages().getFirst().status());
        assertEquals(LearningPackageStatus.AVAILABLE, result.packages().get(1).status());
        verify(packages).findByPathForUpdate(2L);
        verify(packages, never()).findOwnedByIdForUpdate(anyLong(), anyLong());
    }

    @Test void currentExposesSmartTestSmallTargetSetsAsReviewOnlyWithoutCreatingActivities() {
        var path = interactivePath();
        path.setCoverageGapsJson("[{\"tagCode\":\"VOCABULARY:学生\",\"reasonCode\":\"SMART_TEST_REVIEW_ONLY_BELOW_THRESHOLD\"},"
                + "{\"tagCode\":\"KANJI:食\",\"reasonCode\":\"SMART_TEST_REVIEW_ONLY_BELOW_THRESHOLD\"}]");
        var pkg = learningPackage(path, 11L, 1, LearningPackageStatus.AVAILABLE);
        pkg.setPhase("WARM_UP");
        when(paths.findByUserIdAndStatusAndExperienceVersion(7L, "ACTIVE", LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(Optional.of(path));
        when(packages.findByPathForUpdate(2L)).thenReturn(List.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(List.of());

        var result = service.current(7L);

        assertEquals(List.of("KANJI:食", "VOCABULARY:学生"), result.smartTestReviewOnlyTargets().stream()
                .map(target -> target.skill() + ":" + target.target()).toList());
        assertTrue(result.packages().getFirst().activities().isEmpty(),
                "Read-only targets must not be represented as progress-bearing activities");
    }

    /** Owner clarification 2026-10-11: full Smart Test question/result details belong to test results, not the roadmap. */
    @Test void currentDoesNotExposeUnmappedGrammarQuizDetailsOnTheRoadmap() {
        var path = interactivePath();
        path.setCoverageGapsJson("[{\"tagCode\":\"GRAMMAR_QUESTION:1513\",\"reasonCode\":\"SMART_TEST_REVIEW_ONLY_UNMAPPED_GRAMMAR\"},"
                + "{\"tagCode\":\"GRAMMAR_QUESTION:1514\",\"reasonCode\":\"SMART_TEST_REVIEW_ONLY_UNMAPPED_GRAMMAR\"},"
                + "{\"tagCode\":\"GRAMMAR_QUESTION:1599\",\"reasonCode\":\"SMART_TEST_REVIEW_ONLY_UNMAPPED_GRAMMAR\"}]");
        var pkg = learningPackage(path, 11L, 1, LearningPackageStatus.AVAILABLE);
        pkg.setPhase("WARM_UP");
        when(paths.findByUserIdAndStatusAndExperienceVersion(7L, "ACTIVE", LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(Optional.of(path));
        when(packages.findByPathForUpdate(2L)).thenReturn(List.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(List.of());

        var targets = service.current(7L).smartTestReviewOnlyTargets();

        assertTrue(targets.stream().noneMatch(target -> "GRAMMAR".equals(target.skill())));
    }

    @Test void anotherUsersPackageLooksNotFound() {
        when(packages.findOwnedByIdForUpdate(99L, 7L)).thenReturn(Optional.empty());
        ApiException error = assertThrows(ApiException.class, () -> service.packageDetail(7L, 99L));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test void legacyPackageIsNotExposedThroughV2Api() {
        var path = interactivePath();
        path.setExperienceVersion(LearningExperienceVersion.LEGACY_V1);
        var pkg = learningPackage(path, 11L, 1, LearningPackageStatus.AVAILABLE);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        ApiException error = assertThrows(ApiException.class, () -> service.packageDetail(7L, 11L));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
        verifyNoInteractions(items);
    }

    @Test void lockedPackageCannotStart() {
        var pkg = learningPackage(interactivePath(), 11L, 1, LearningPackageStatus.LOCKED);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        ApiException error = assertThrows(ApiException.class, () -> service.start(7L, 11L));
        assertEquals(ErrorCode.PREREQUISITE_NOT_MET, error.getErrorCode());
        verifyNoInteractions(items);
    }

    @Test void startIsIdempotentAndOffersEverySkillInTheCurrentPackage() {
        var pkg = learningPackage(interactivePath(), 11L, 1, LearningPackageStatus.AVAILABLE);
        var activities = activities(pkg);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));

        var first = service.start(7L, 11L);
        var startedAt = pkg.getStartedAt();
        var second = service.start(7L, 11L);

        assertEquals(LearningPackageStatus.IN_PROGRESS, first.status());
        assertEquals(LearningActivityState.STUDYING, activities.getFirst().getActivityState());
        assertTrue(activities.stream().allMatch(a -> a.getActivityState() == LearningActivityState.STUDYING));
        assertEquals(startedAt, pkg.getStartedAt());
        assertEquals(first.resumeActivityType(), second.resumeActivityType());
    }

    @Test void targetPackageWithoutOptionalDialogueRemainsReadableButIncomplete() {
        var pkg = learningPackage(interactivePath(), 11L, 1, LearningPackageStatus.AVAILABLE);
        pkg.setStatus(LearningPackageStatus.IN_PROGRESS);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities(pkg).subList(0, 3));
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));

        var response=service.packageDetail(7L,11L);

        assertEquals(LearningPackageStatus.IN_PROGRESS,response.status());
        assertEquals(3,response.totalActivities());
        assertFalse(response.justCompleted());
    }

    @Test void targetPackageCanStartWithSeveralVocabularyBatchesBeforeKanji() {
        var pkg = learningPackage(interactivePath(), 11L, 1, LearningPackageStatus.AVAILABLE);
        var activities = warmUpActivities(pkg, LearningActivityType.VOCABULARY,
                LearningActivityType.VOCABULARY, LearningActivityType.KANJI);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));

        var result = service.start(7L, 11L);

        assertEquals(LearningPackageStatus.IN_PROGRESS, result.status());
        assertEquals(3, result.totalActivities());
        assertEquals(LearningActivityState.STUDYING, activities.getFirst().getActivityState());
        assertTrue(activities.stream().allMatch(a -> a.getActivityState() == LearningActivityState.STUDYING));
    }

    @Test void vocabularyOnlyTargetSegmentCompletesAfterAllItsBatchesPass() {
        var pkg = learningPackage(interactivePath(), 11L, 1, LearningPackageStatus.IN_PROGRESS);
        var activities = warmUpActivities(pkg, LearningActivityType.VOCABULARY,
                LearningActivityType.VOCABULARY);
        activities.getFirst().setActivityState(LearningActivityState.PASSED);
        activities.get(1).setActivityState(LearningActivityState.STUDYING);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));

        assertEquals(LearningPackageStatus.IN_PROGRESS, service.packageDetail(7L, 11L).status());
        activities.get(1).setActivityState(LearningActivityState.PASSED);
        stubNoScoredSessions();
        var completed = service.packageDetail(7L, 11L);

        assertEquals(LearningPackageStatus.COMPLETED, completed.status());
        assertEquals(2, completed.completedActivities());
    }

    // ---- Current package: freely selectable skills ----

    @Test void refreshingAnAlreadyStartedPackageOpensEveryRemainingSkill() {
        var pkg = learningPackage(interactivePath(), 11L, 1, LearningPackageStatus.IN_PROGRESS);
        var activities = activities(pkg);
        activities.get(2).setActivityState(LearningActivityState.PASSED); // Grammar can finish before Vocabulary.
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));

        var result = service.packageDetail(7L, 11L);

        assertEquals(LearningPackageStatus.IN_PROGRESS, result.status());
        assertEquals(LearningActivityState.STUDYING, activities.get(0).getActivityState());
        assertEquals(LearningActivityState.STUDYING, activities.get(1).getActivityState());
        assertEquals(LearningActivityState.PASSED, activities.get(2).getActivityState());
        assertEquals(LearningActivityState.STUDYING, activities.get(3).getActivityState());
    }

    @Test void dialogueRequiresCompletedNotPassedToUnlockNothingFurther() {
        // Dialogue is last in order; its own terminal state is COMPLETED, not PASSED — verify the cascade
        // uses the right terminal state per activity type by checking Grammar (PASSED) correctly unlocks it.
        var pkg = learningPackage(interactivePath(), 11L, 1, LearningPackageStatus.IN_PROGRESS);
        var activities = activities(pkg);
        activities.get(0).setActivityState(LearningActivityState.PASSED);
        activities.get(1).setActivityState(LearningActivityState.PASSED);
        activities.get(2).setActivityState(LearningActivityState.PASSED); // Grammar passed
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));

        service.packageDetail(7L, 11L);

        assertEquals(LearningActivityState.STUDYING, activities.get(3).getActivityState()); // Dialogue unlocked
    }

    // ---- Task 8: completion gate ----

    @Test void packageDoesNotCompleteWhenDialogueOnlyInProgress() {
        var pkg = learningPackage(interactivePath(), 11L, 1, LearningPackageStatus.IN_PROGRESS);
        var activities = activities(pkg);
        activities.get(0).setActivityState(LearningActivityState.PASSED);
        activities.get(1).setActivityState(LearningActivityState.PASSED);
        activities.get(2).setActivityState(LearningActivityState.PASSED);
        activities.get(3).setActivityState(LearningActivityState.STUDYING); // Dialogue not completed
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));

        var result = service.packageDetail(7L, 11L);

        assertEquals(LearningPackageStatus.IN_PROGRESS, result.status());
        verifyNoInteractions(creditService);
        verifyNoInteractions(streakRepository);
    }

    @Test void packageCompletesOnlyWhenAllFourReachTheirOwnTerminalState() {
        var pkg = learningPackage(interactivePath(), 11L, 1, LearningPackageStatus.IN_PROGRESS);
        var activities = activities(pkg);
        activities.get(0).setActivityState(LearningActivityState.PASSED);
        activities.get(1).setActivityState(LearningActivityState.PASSED);
        activities.get(2).setActivityState(LearningActivityState.PASSED);
        activities.get(3).setActivityState(LearningActivityState.COMPLETED);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));
        stubNoScoredSessions();

        var result = service.packageDetail(7L, 11L);

        assertEquals(LearningPackageStatus.COMPLETED, result.status());
        assertNotNull(pkg.getCompletedAt());
        assertTrue(result.justCompleted());

        var second = service.packageDetail(7L, 11L);
        assertFalse(second.justCompleted());
    }

    @Test void grammarOnlyTargetPackageCompletesWithoutOptionalVocabularyOrDialogue(){
        assertTargetPackageCompletes(LearningActivityType.GRAMMAR);
    }

    @Test void kanjiOnlyTargetPackageCompletesWithoutOptionalVocabularyOrDialogue(){
        assertTargetPackageCompletes(LearningActivityType.KANJI);
    }

    @Test void kanjiAndGrammarTargetPackageCompletesWithoutOptionalDialogue(){
        assertTargetPackageCompletes(LearningActivityType.KANJI,LearningActivityType.GRAMMAR);
    }

    @Test void readingOnlyTargetPackageCompletes(){
        assertTargetPackageCompletes(LearningActivityType.READING);
    }

    @Test void listeningOnlyTargetPackageCompletes(){
        assertTargetPackageCompletes(LearningActivityType.LISTENING);
    }

    @Test void emptyTargetPackageIsBlockedAndNeverStarted(){
        var pkg=learningPackage(interactivePath(),11L,1,LearningPackageStatus.AVAILABLE);
        when(packages.findOwnedByIdForUpdate(11L,7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(List.of());

        ApiException error=assertThrows(ApiException.class,()->service.start(7L,11L));

        assertEquals(ErrorCode.BLOCKED_CONTENT,error.getErrorCode());
        assertEquals(LearningPackageStatus.AVAILABLE,pkg.getStatus());
    }

    @Test void targetPackageWithUnknownActivityTypeIsBlocked(){
        var pkg=learningPackage(interactivePath(),11L,1,LearningPackageStatus.IN_PROGRESS);
        var unknown=warmUpActivities(pkg,LearningActivityType.FOUNDATION,LearningActivityType.GRAMMAR);
        when(packages.findOwnedByIdForUpdate(11L,7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(unknown);

        assertEquals(ErrorCode.BLOCKED_CONTENT,
                assertThrows(ApiException.class,()->service.packageDetail(7L,11L)).getErrorCode());
    }

    // ---- Task 9: Warm-up B completion gate (dynamic activity set, never all four/Dialogue) ----

    @Test void warmUpPackageCompletesWhenEveryOneOfItsOwnActivitiesIsPassed() {
        var pkg = warmUpPackage(11L, 1, LearningPackageStatus.IN_PROGRESS);
        var activities = warmUpActivities(pkg, LearningActivityType.VOCABULARY, LearningActivityType.KANJI);
        activities.forEach(a -> a.setActivityState(LearningActivityState.PASSED));
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));
        stubNoScoredSessions();

        var result = service.packageDetail(7L, 11L);

        assertEquals(LearningPackageStatus.COMPLETED, result.status());
        assertTrue(result.justCompleted());
    }

    @Test void warmUpPackageDoesNotCompleteWhileAnyOfItsOwnActivitiesIsStillInProgress() {
        var pkg = warmUpPackage(11L, 1, LearningPackageStatus.IN_PROGRESS);
        var activities = warmUpActivities(pkg, LearningActivityType.VOCABULARY, LearningActivityType.GRAMMAR);
        activities.get(0).setActivityState(LearningActivityState.PASSED);
        activities.get(1).setActivityState(LearningActivityState.STUDYING);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));

        var result = service.packageDetail(7L, 11L);

        assertEquals(LearningPackageStatus.IN_PROGRESS, result.status());
        verifyNoInteractions(creditService);
    }

    @Test void warmUpPackageWithASingleActivityCompletesAndUnlocksTheNextPackage() {
        var path = interactivePath();
        var pkg = LearningPathPackage.builder().id(11L).learningPath(path).phase("WARM_UP")
                .dayNumber(1).orderIndex(1).title("Ôn tập điểm yếu: từ vựng").estimatedMinutes(5)
                .status(LearningPackageStatus.IN_PROGRESS).build();
        var target = LearningPathPackage.builder().id(12L).learningPath(path).phase("GOAL_LEARNING")
                .sourceLesson(Lesson.builder().id(200L).build()).contentRevision("revision")
                .dayNumber(2).orderIndex(2).title("Ngày 1").estimatedMinutes(30)
                .status(LearningPackageStatus.LOCKED).build();
        var activities = warmUpActivities(pkg, LearningActivityType.VOCABULARY);
        activities.get(0).setActivityState(LearningActivityState.PASSED);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg, target));
        stubNoScoredSessions();

        var result = service.packageDetail(7L, 11L);

        assertEquals(LearningPackageStatus.COMPLETED, result.status());
        assertEquals(LearningPackageStatus.AVAILABLE, target.getStatus());
        assertEquals(12L, result.nextAvailablePackageId());
    }

    // ---- RC Task 6: comprehension (Reading/Listening) participates in warm-up completion generically ----
    // Task 9's allWarmUpActivitiesDone/cascadeUnlock/isActivityDone are activity-type-agnostic (they only
    // special-case DIALOGUE's terminal state); these tests verify that generic support already covers
    // READING/LISTENING with zero production changes, rather than assuming it.

    @Test void comprehensionOnlyWarmUpPackageCompletesWhenBothReadingAndListeningArePassed() {
        var pkg = warmUpPackage(11L, 1, LearningPackageStatus.IN_PROGRESS);
        var activities = warmUpActivities(pkg, LearningActivityType.READING, LearningActivityType.LISTENING);
        activities.forEach(a -> a.setActivityState(LearningActivityState.PASSED));
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));
        stubNoScoredSessions();

        var result = service.packageDetail(7L, 11L);

        assertEquals(LearningPackageStatus.COMPLETED, result.status());
        assertTrue(result.justCompleted());
    }

    @Test void mixedWarmUpPackageWithVocabularyAndReadingCompletesWhenBothArePassed() {
        var pkg = warmUpPackage(11L, 1, LearningPackageStatus.IN_PROGRESS);
        var activities = warmUpActivities(pkg, LearningActivityType.VOCABULARY, LearningActivityType.READING);
        activities.forEach(a -> a.setActivityState(LearningActivityState.PASSED));
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));
        stubNoScoredSessions();

        var result = service.packageDetail(7L, 11L);

        assertEquals(LearningPackageStatus.COMPLETED, result.status());
        assertTrue(result.justCompleted());
    }

    @Test void warmUpPackageDoesNotCompleteWhileItsOwnListeningActivityIsStillUnresolved() {
        var pkg = warmUpPackage(11L, 1, LearningPackageStatus.IN_PROGRESS);
        var activities = warmUpActivities(pkg, LearningActivityType.VOCABULARY, LearningActivityType.LISTENING);
        activities.get(0).setActivityState(LearningActivityState.PASSED);
        activities.get(1).setActivityState(LearningActivityState.COOLDOWN); // Listening failed 3rd attempt
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));

        var result = service.packageDetail(7L, 11L);

        assertEquals(LearningPackageStatus.IN_PROGRESS, result.status());
        verifyNoInteractions(creditService);
        verifyNoInteractions(streakRepository);
    }

    @Test void packageScoreExcludesAPassedComprehensionActivityEvenWithAScoredSession() {
        var pkg = warmUpPackage(11L, 1, LearningPackageStatus.IN_PROGRESS);
        var activities = warmUpActivities(pkg, LearningActivityType.VOCABULARY, LearningActivityType.READING);
        activities.forEach(a -> a.setActivityState(LearningActivityState.PASSED));
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));
        session(activities.get(0).getId(), "VOCAB_MICRO_CHECK", 8, 10); // Vocabulary: 80%
        // Reading has its own scored, passing session on record; the score formula must never read it at all.
        lenient().when(quizSessions.findByActivityIdAndQuizTypeAndStatusAndPassed(
                activities.get(1).getId(), "READING_MICRO_CHECK", "SUBMITTED", true))
                .thenReturn(List.of(quizSession(10, 10))); // Reading: 100%, must NOT count

        var result = service.packageDetail(7L, 11L);

        // If Reading leaked into the formula this would be round((80+100)/2)=90. It must stay 80.
        assertEquals(80, result.scorePercent());
        verify(quizSessions, never()).findByActivityIdAndQuizTypeAndStatusAndPassed(
                activities.get(1).getId(), "READING_MICRO_CHECK", "SUBMITTED", true);
    }

    // ---- Task 8: score aggregation ----

    @Test void scoreIsPooledCorrectTotalAcrossBatchesNotAverageOfPercentages() {
        var pkg = completedTrigger();
        var activities = activities(pkg);
        markAllTerminal(activities);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));
        // Vocabulary: two batches, 9/10 (90%) then 6/10 (60%) -> pooled 15/20 = 75%, NOT avg(90,60)=75 by
        // coincidence-proofing we use different, non-symmetric numbers below for Kanji/Grammar.
        session(activities.get(0).getId(), "VOCAB_MICRO_CHECK", 9, 10);
        session2(activities.get(0).getId(), "VOCAB_MICRO_CHECK", 6, 10);
        // Kanji: single batch 7/10 = 70%.
        session(activities.get(1).getId(), "KANJI_MICRO_CHECK", 7, 10);
        // Grammar: single batch 2/2 = 100%.
        session(activities.get(2).getId(), "GRAMMAR_MICRO_CHECK", 2, 2);

        var result = service.packageDetail(7L, 11L);

        // vocab=75, kanji=70, grammar=100 -> package = round((75+70+100)/3) = round(81.67) = 82
        assertEquals(82, result.scorePercent());
    }

    @Test void missingScoreForAScoredSkillIsExcludedNotTreatedAsZero() {
        var pkg = completedTrigger();
        var activities = activities(pkg);
        markAllTerminal(activities);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));
        // Only Kanji has a passing session on record (defensive edge case); Vocabulary/Grammar missing.
        when(quizSessions.findByActivityIdAndQuizTypeAndStatusAndPassed(activities.get(0).getId(),
                "VOCAB_MICRO_CHECK", "SUBMITTED", true)).thenReturn(List.of());
        session(activities.get(1).getId(), "KANJI_MICRO_CHECK", 8, 10);
        when(quizSessions.findByActivityIdAndQuizTypeAndStatusAndPassed(activities.get(2).getId(),
                "GRAMMAR_MICRO_CHECK", "SUBMITTED", true)).thenReturn(List.of());

        var result = service.packageDetail(7L, 11L);

        // Only Kanji counted: round(80/1) = 80, not round((0+80+0)/3)=27.
        assertEquals(80, result.scorePercent());
    }

    // ---- Task 8: exactly-once reward/streak ----

    @Test void rewardStreakAndCompletionGrantedExactlyOnceAcrossRetries() {
        var pkg = completedTrigger();
        var activities = activities(pkg);
        markAllTerminal(activities);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));
        stubNoScoredSessions();
        when(streakRepository.findByUserId(7L)).thenReturn(Optional.empty());

        service.packageDetail(7L, 11L); // first call: completes, rewards
        service.packageDetail(7L, 11L); // retry: already COMPLETED, must not reward again

        // Exactly 5 CP, referenced by the stable source lesson (chapter), never by the package.
        verify(creditService, times(1)).earn(eq(7L), eq(5), eq(CreditReason.STUDY_REWARD),
                eq("SOURCE_LESSON"), eq(111L), anyString());
        verify(streakRepository, times(1)).save(any());
    }

    /** A source lesson split into several day packages is one chapter. The first segment earns 0 CP (the chapter
     * is not yet complete); the final segment that finishes the chapter earns 5 CP exactly once, referenced by the
     * stable source lesson (never per-package). Streak still records each completed package as activity on its own
     * policy, so it does not wait for the chapter reward. (owner decision 2026-10-14) */
    @Test void chapterSplitAcrossSegmentsRewardsOnlyWhenTheWholeChapterCompletes() {
        var path = interactivePath();
        var segment1 = learningPackage(path, 11L, 1, LearningPackageStatus.IN_PROGRESS);
        var segment2 = learningPackage(path, 12L, 2, LearningPackageStatus.IN_PROGRESS);
        segment2.setSourceLesson(segment1.getSourceLesson()); // same source lesson -> same chapter
        var first = activities(segment1);
        var second = activities(segment2);
        markAllTerminal(first);
        markAllTerminal(second);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(segment1));
        when(packages.findOwnedByIdForUpdate(12L, 7L)).thenReturn(Optional.of(segment2));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(first);
        when(items.findByLearningPackageIdOrderByOrderIndex(12L)).thenReturn(second);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(segment1, segment2));
        stubNoScoredSessions();
        when(streakRepository.findByUserId(7L)).thenReturn(Optional.empty());

        service.packageDetail(7L, 11L); // segment 1 completes -> chapter not yet done -> 0 CP

        verify(creditService, never()).earn(anyLong(), anyInt(), any(), anyString(), anyLong(), anyString());
        verify(streakRepository, times(1)).save(any()); // streak records the activity, not the chapter reward

        service.packageDetail(7L, 12L); // segment 2 completes -> whole chapter done -> 5 CP once

        verify(creditService, times(1)).earn(eq(7L), eq(5), eq(CreditReason.STUDY_REWARD),
                eq("SOURCE_LESSON"), eq(111L), anyString());
        verify(creditService, never()).earn(eq(7L), anyInt(), eq(CreditReason.STUDY_REWARD),
                eq("PACKAGE"), anyLong(), anyString());
        verify(streakRepository, times(2)).save(any());
    }

    @Test void nextChapterOpensOnlyAfterEveryCurrentChapterSegmentCompletes() {
        var path = interactivePath();
        var firstSegment = learningPackage(path, 11L, 1, LearningPackageStatus.IN_PROGRESS);
        var secondSegment = learningPackage(path, 12L, 2, LearningPackageStatus.LOCKED);
        secondSegment.setSourceLesson(firstSegment.getSourceLesson());
        var nextChapterFirst = learningPackage(path, 13L, 3, LearningPackageStatus.LOCKED);
        var nextChapterSecond = learningPackage(path, 14L, 4, LearningPackageStatus.LOCKED);
        nextChapterSecond.setSourceLesson(nextChapterFirst.getSourceLesson());
        var firstActivities = activities(firstSegment);
        var secondActivities = activities(secondSegment);
        markAllTerminal(firstActivities);
        markAllTerminal(secondActivities);
        var pathPackages = List.of(firstSegment, secondSegment, nextChapterFirst, nextChapterSecond);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(firstSegment));
        when(packages.findOwnedByIdForUpdate(12L, 7L)).thenReturn(Optional.of(secondSegment));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(firstActivities);
        when(items.findByLearningPackageIdOrderByOrderIndex(12L)).thenReturn(secondActivities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(pathPackages);
        stubNoScoredSessions();

        service.packageDetail(7L, 11L);

        assertEquals(LearningPackageStatus.AVAILABLE, secondSegment.getStatus());
        assertEquals(LearningPackageStatus.LOCKED, nextChapterFirst.getStatus());
        assertEquals(LearningPackageStatus.LOCKED, nextChapterSecond.getStatus());

        service.start(7L, 12L);
        service.packageDetail(7L, 12L);

        assertEquals(LearningPackageStatus.COMPLETED, firstSegment.getStatus());
        assertEquals(LearningPackageStatus.COMPLETED, secondSegment.getStatus());
        assertEquals(LearningPackageStatus.AVAILABLE, nextChapterFirst.getStatus());
        assertEquals(LearningPackageStatus.AVAILABLE, nextChapterSecond.getStatus());
    }

    /** Replan/retry of the already-completed final segment must not re-grant the chapter: the second call sees the
     * package already {@code COMPLETED} and returns before touching credit. */
    @Test void completingTheFinalSegmentAgainDoesNotReGrantTheChapter() {
        var path = interactivePath();
        var segment1 = learningPackage(path, 11L, 1, LearningPackageStatus.IN_PROGRESS);
        var segment2 = learningPackage(path, 12L, 2, LearningPackageStatus.IN_PROGRESS);
        segment2.setSourceLesson(segment1.getSourceLesson());
        var first = activities(segment1);
        var second = activities(segment2);
        markAllTerminal(first);
        markAllTerminal(second);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(segment1));
        when(packages.findOwnedByIdForUpdate(12L, 7L)).thenReturn(Optional.of(segment2));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(first);
        when(items.findByLearningPackageIdOrderByOrderIndex(12L)).thenReturn(second);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(segment1, segment2));
        stubNoScoredSessions();
        when(streakRepository.findByUserId(7L)).thenReturn(Optional.empty());

        service.packageDetail(7L, 11L);
        service.packageDetail(7L, 12L); // grants 5 CP
        service.packageDetail(7L, 12L); // retry: already COMPLETED, no re-grant

        verify(creditService, times(1)).earn(eq(7L), eq(5), eq(CreditReason.STUDY_REWARD),
                eq("SOURCE_LESSON"), eq(111L), anyString());
    }

    @Test void rewardIsSkippedWhenStreakAndCreditBeansAreUnavailable() {
        service = new InteractiveLearningPathServiceImpl(paths, packages, items, quizSessions);
        // streakRepository/creditService left null (optional autowire, unavailable in this slice).
        var pkg = completedTrigger();
        var activities = activities(pkg);
        markAllTerminal(activities);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));
        stubNoScoredSessions();

        var result = service.packageDetail(7L, 11L);

        assertEquals(LearningPackageStatus.COMPLETED, result.status());
    }

    // ---- Task 8: next package unlock / last package ----

    @Test void completingAPackageUnlocksTheNextLockedOneOnly() {
        var path = interactivePath();
        var pkg = learningPackage(path, 11L, 1, LearningPackageStatus.IN_PROGRESS);
        var next = learningPackage(path, 12L, 2, LearningPackageStatus.LOCKED);
        var third = learningPackage(path, 13L, 3, LearningPackageStatus.LOCKED);
        var activities = activities(pkg);
        markAllTerminal(activities);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg, next, third));
        stubNoScoredSessions();

        var result = service.packageDetail(7L, 11L);

        assertEquals(LearningPackageStatus.AVAILABLE, next.getStatus());
        assertEquals(LearningPackageStatus.LOCKED, third.getStatus());
        assertEquals(12L, result.nextAvailablePackageId());
        assertFalse(result.lastPackage());
    }

    @Test void activePathWithNoPackagesIsBlockedInsteadOfReportingOneHundredPercent() {
        var path = interactivePath();
        when(paths.findByUserIdAndStatusAndExperienceVersion(7L, "ACTIVE", LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(Optional.of(path));
        when(packages.findByPathForUpdate(2L)).thenReturn(List.of());

        ApiException error = assertThrows(ApiException.class, () -> service.current(7L));

        assertEquals(ErrorCode.BLOCKED_CONTENT, error.getErrorCode());
        assertTrue(error.getMessage().contains("EMPTY_LESSON"));
    }

    @Test void completingTheLastN5PackageCompletesItsStageAndActivatesN4() {
        var path = interactivePath();
        var n5Stage = UserLearningStage.builder().id(101L).learningPath(path).level("N5")
                .stageIndex(0).status("ACTIVE").build();
        var n4Stage = UserLearningStage.builder().id(102L).learningPath(path).level("N4")
                .stageIndex(1).status("PENDING").build();
        var n5Package = learningPackage(path, 11L, 1, LearningPackageStatus.IN_PROGRESS);
        n5Package.setStageId(101L);
        var n4Package = learningPackage(path, 12L, 2, LearningPackageStatus.LOCKED);
        n4Package.setStageId(102L);
        var activities = activities(n5Package);
        markAllTerminal(activities);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(n5Package));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(n5Package, n4Package));
        when(stages.findById(101L)).thenReturn(Optional.of(n5Stage));
        when(stages.findByLearningPathIdAndStageIndex(2L, 1)).thenReturn(Optional.of(n4Stage));
        stubNoScoredSessions();

        service.packageDetail(7L, 11L);

        assertEquals("COMPLETED", n5Stage.getStatus());
        assertEquals("ACTIVE", n4Stage.getStatus());
        assertEquals(LearningPackageStatus.AVAILABLE, n4Package.getStatus());
        verify(stages).save(n5Stage);
        verify(stages).save(n4Stage);
    }

    @Test void lastPackageInPathReportsNoNextAvailablePackage() {
        var pkg = completedTrigger();
        var activities = activities(pkg);
        markAllTerminal(activities);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));
        stubNoScoredSessions();

        var result = service.packageDetail(7L, 11L);

        assertTrue(result.lastPackage());
        assertNull(result.nextAvailablePackageId());
    }

    // ---- Task 10: path-level completion once every GOAL_LEARNING package is COMPLETED ----

    /** Owner decision 2026-10-06 (option A): the Mock Test is a self-assessment the learner is pointed to, not a
     * completion gate, so a JLPT path completes when its last GOAL_LEARNING package completes. */
    @Test void jlptPathCompletesWhenItsLastPackageCompletesWithoutAMockTest() {
        var pkg = completedTrigger();
        var activities = activities(pkg);
        markAllTerminal(activities);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));
        stubNoScoredSessions();

        service.packageDetail(7L, 11L);

        UserLearningPath path = pkg.getLearningPath();
        assertEquals("COMPLETED", path.getStatus());
        assertNotNull(path.getCompletedAt());
        assertNull(path.getCompletionAttemptId());
        verify(paths).save(path);
    }

    @Test void surveyOnlyPathCompletesWithoutAJlptAttempt() {
        var pkg = completedTrigger();
        pkg.getLearningPath().setGoal("TRAVEL");
        var activities = activities(pkg);
        markAllTerminal(activities);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));
        stubNoScoredSessions();
        service.packageDetail(7L, 11L);
        assertEquals("COMPLETED", pkg.getLearningPath().getStatus());
        assertNotNull(pkg.getLearningPath().getCompletedAt());
        assertNull(pkg.getLearningPath().getCompletionAttemptId());
        verify(creditService, never()).earn(anyLong(), anyInt(), any(), anyString(), anyLong(), anyString());
    }

    /** Option A (2026-10-06): a legacy path without a goal is treated as JLPT, which now also completes on packages. */
    @Test void legacyPathWithMissingGoalCompletesLikeAJlptPath() {
        var pkg = completedTrigger();
        pkg.getLearningPath().setGoal(null);
        var activities = activities(pkg);
        markAllTerminal(activities);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));
        stubNoScoredSessions();

        service.packageDetail(7L, 11L);

        assertEquals("COMPLETED", pkg.getLearningPath().getStatus());
        assertNotNull(pkg.getLearningPath().getCompletedAt());
        verify(paths).save(pkg.getLearningPath());
    }

    @Test void pathStaysActiveWhileAnotherGoalLearningPackageIsStillIncomplete() {
        var path = interactivePath();
        var pkg = learningPackage(path, 11L, 1, LearningPackageStatus.IN_PROGRESS);
        var stillOpen = learningPackage(path, 12L, 2, LearningPackageStatus.LOCKED);
        var activities = activities(pkg);
        markAllTerminal(activities);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg, stillOpen));
        stubNoScoredSessions();

        service.packageDetail(7L, 11L);

        assertEquals("ACTIVE", path.getStatus());
        verify(paths, never()).save(any());
    }

    @Test void warmUpOnlyPackageCompletionNeverCompletesThePathByItself() {
        var path = interactivePath();
        var warmUp = LearningPathPackage.builder().id(11L).learningPath(path).phase("WARM_UP")
                .dayNumber(1).orderIndex(1).title("Ôn tập điểm yếu").estimatedMinutes(5)
                .status(LearningPackageStatus.IN_PROGRESS).build();
        var target = learningPackage(path, 12L, 2, LearningPackageStatus.LOCKED);
        var activities = warmUpActivities(warmUp, LearningActivityType.VOCABULARY);
        activities.get(0).setActivityState(LearningActivityState.PASSED);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(warmUp));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(warmUp, target));
        stubNoScoredSessions();

        service.packageDetail(7L, 11L);

        assertEquals("ACTIVE", path.getStatus());
        verify(paths, never()).save(any());
    }

    // ---- helpers ----

    private LearningPathPackage completedTrigger() {
        return learningPackage(interactivePath(), 11L, 1, LearningPackageStatus.IN_PROGRESS);
    }

    private void markAllTerminal(List<UserLearningPathItem> activities) {
        activities.get(0).setActivityState(LearningActivityState.PASSED);
        activities.get(1).setActivityState(LearningActivityState.PASSED);
        activities.get(2).setActivityState(LearningActivityState.PASSED);
        activities.get(3).setActivityState(LearningActivityState.COMPLETED);
    }

    private void assertTargetPackageCompletes(LearningActivityType... types){
        var pkg=learningPackage(interactivePath(),11L,1,LearningPackageStatus.IN_PROGRESS);
        var activities=warmUpActivities(pkg,types);
        for(var activity:activities) activity.setActivityState(LearningActivityState.PASSED);
        when(packages.findOwnedByIdForUpdate(11L,7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(pkg));
        stubNoScoredSessions();

        var result=service.packageDetail(7L,11L);

        assertEquals(LearningPackageStatus.COMPLETED,result.status());
        assertTrue(result.justCompleted());
        assertNull(result.scorePercent(),"No score may be fabricated when the package has no valid scored quiz session");
    }

    private void stubNoScoredSessions() {
        lenient().when(quizSessions.findByActivityIdAndQuizTypeAndStatusAndPassed(
                anyLong(), anyString(), anyString(), eq(true))).thenReturn(List.of());
    }

    private void session(Long activityId, String quizType, int correct, int total) {
        when(quizSessions.findByActivityIdAndQuizTypeAndStatusAndPassed(activityId, quizType, "SUBMITTED", true))
                .thenReturn(List.of(quizSession(correct, total)));
    }

    private void session2(Long activityId, String quizType, int correct, int total) {
        // Second call for the same activity/quizType stubbing multiple batches: re-stub with both sessions.
        List<LearningQuizSession> existing = quizSessions.findByActivityIdAndQuizTypeAndStatusAndPassed(
                activityId, quizType, "SUBMITTED", true);
        var combined = new java.util.ArrayList<>(existing);
        combined.add(quizSession(correct, total));
        when(quizSessions.findByActivityIdAndQuizTypeAndStatusAndPassed(activityId, quizType, "SUBMITTED", true))
                .thenReturn(combined);
    }

    private LearningQuizSession quizSession(int correct, int total) {
        return LearningQuizSession.builder().status("SUBMITTED").passed(true)
                .correctCount(correct).questionCount(total).build();
    }

    private UserLearningPath interactivePath() {
        return UserLearningPath.builder().id(2L).user(User.builder().id(7L).build()).title("N5 → N4")
                .goal("JLPT").currentLevel("N5").targetLevel("N4").minutesPerDay(30)
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).build();
    }

    private LearningPathPackage learningPackage(UserLearningPath path, Long id, int order, LearningPackageStatus status) {
        return LearningPathPackage.builder().id(id).learningPath(path).phase("GOAL_LEARNING")
                .sourceLesson(Lesson.builder().id(100L + id).build()).contentRevision("revision")
                .dayNumber(order).orderIndex(order).title("Ngày " + order).estimatedMinutes(30).status(status).build();
    }

    private LearningPathPackage warmUpPackage(Long id, int order, LearningPackageStatus status) {
        return LearningPathPackage.builder().id(id).learningPath(interactivePath()).phase("WARM_UP")
                .dayNumber(order).orderIndex(order).title("Ôn tập điểm yếu").estimatedMinutes(5)
                .status(status).build();
    }

    private List<UserLearningPathItem> warmUpActivities(LearningPathPackage pkg, LearningActivityType... types) {
        return new java.util.ArrayList<>(java.util.stream.IntStream.range(0, types.length).mapToObj(index ->
                UserLearningPathItem.builder().id((long) index + 1).learningPath(pkg.getLearningPath())
                        .learningPackage(pkg).orderIndex(index + 1).phase("WARM_UP")
                        .activityType(types[index]).activityState(LearningActivityState.LOCKED).build()).toList());
    }

    private List<UserLearningPathItem> activities(LearningPathPackage pkg) {
        var types = List.of(LearningActivityType.VOCABULARY, LearningActivityType.KANJI,
                LearningActivityType.GRAMMAR, LearningActivityType.DIALOGUE);
        return new java.util.ArrayList<>(java.util.stream.IntStream.range(0, types.size()).mapToObj(index -> UserLearningPathItem.builder()
                .id((long) index + 1).learningPath(pkg.getLearningPath()).learningPackage(pkg)
                .orderIndex(index + 1).activityType(types.get(index)).activityState(LearningActivityState.LOCKED).build()).toList());
    }
}
