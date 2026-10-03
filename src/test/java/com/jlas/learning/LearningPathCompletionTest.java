package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.jlas.auth.entity.User;
import com.jlas.common.event.LearningEvent;
import com.jlas.common.event.LearningEventType;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningStage;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningPackageStatus;
import com.jlas.learning.repository.LearningPathPackageRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.repository.UserLearningStageRepository;
import com.jlas.learning.service.JlptQualificationPort;
import com.jlas.learning.service.impl.LearningFinalEligibilityServiceImpl;
import com.jlas.learning.service.impl.LearningPathCompletionServiceImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** [JLAS-W0108] Task 9 — completion only after server-side eligibility AND a verified Mock Test attempt, with
 * exactly-once effects. Path/stage/package rows are in-memory test fixtures. */
class LearningPathCompletionTest {
    private static final Long USER = 7L;
    private static final Long ATTEMPT = 500L;

    private UserLearningPathRepository paths;
    private LearningPathPackageRepository packages;
    private UserLearningStageRepository stages;
    private JlptQualificationPort qualification;
    private ApplicationEventPublisher events;
    private LearningPathCompletionServiceImpl service;

    private UserLearningPath path;
    private UserLearningStage n5;
    private UserLearningStage n4;

    @BeforeEach
    void setUp() {
        paths = mock(UserLearningPathRepository.class);
        packages = mock(LearningPathPackageRepository.class);
        stages = mock(UserLearningStageRepository.class);
        qualification = mock(JlptQualificationPort.class);
        events = mock(ApplicationEventPublisher.class);
        service = new LearningPathCompletionServiceImpl(paths,
                new LearningFinalEligibilityServiceImpl(paths, packages, stages), stages, qualification, events);

        path = UserLearningPath.builder().id(5L).user(User.builder().id(USER).build())
                .goal("JLPT").experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).status("ACTIVE").title("P").build();
        n5 = UserLearningStage.builder().id(10L).learningPath(path).level("N5").stageIndex(0).status("COMPLETED").build();
        n4 = UserLearningStage.builder().id(11L).learningPath(path).level("N4").stageIndex(1).status("ACTIVE").build();
        when(paths.lockActiveInteractive(USER)).thenReturn(Optional.of(path));
        when(stages.findByLearningPathIdOrderByStageIndexAsc(5L)).thenReturn(List.of(n5, n4));
        // the path is marked COMPLETED by save(), after which the locked lookup no longer matches it
        when(paths.save(any())).thenAnswer(i -> {
            UserLearningPath saved = i.getArgument(0);
            if ("COMPLETED".equals(saved.getStatus())) {
                when(paths.lockActiveInteractive(USER)).thenReturn(Optional.empty());
                when(paths.findTopByUserIdAndExperienceVersionOrderByIdDesc(USER, LearningExperienceVersion.INTERACTIVE_V2))
                        .thenReturn(Optional.of(saved));
            }
            return saved;
        });
    }

    private void packageCounts(int n5Done, int n5Total, int n4Done, int n4Total) {
        List<LearningPathPackage> all = new ArrayList<>();
        for (int i = 0; i < n5Total; i++) all.add(pkg(10L, i < n5Done));
        for (int i = 0; i < n4Total; i++) all.add(pkg(11L, i < n4Done));
        when(packages.findByLearningPathIdOrderByOrderIndex(5L)).thenReturn(all);
    }

    private LearningPathPackage pkg(Long stageId, boolean done) {
        return LearningPathPackage.builder().stageId(stageId)
                .status(done ? LearningPackageStatus.COMPLETED : LearningPackageStatus.AVAILABLE).build();
    }

    private void qualifies() {
        when(qualification.verify(USER, ATTEMPT, "N4")).thenReturn(new JlptQualificationPort.Result(true, null));
    }

    @Test void communicationPathCannotBeCompletedUsingAJlptAttempt() {
        path.setGoal("TRAVEL");
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(ApiException.class,
                () -> service.completeFromMock(USER, ATTEMPT)).getErrorCode());
        verifyNoInteractions(qualification, events, packages);
        verify(paths, never()).save(any());
    }

    @Test void completesOnceWhenEligibleAndAttemptQualifies() {
        packageCounts(25, 25, 13, 25);
        qualifies();

        var result = service.completeFromMock(USER, ATTEMPT);

        assertTrue(result.newlyCompleted());
        assertEquals("COMPLETED", result.status());
        assertEquals("COMPLETED", path.getStatus());
        assertNotNull(path.getCompletedAt());
        assertEquals("COMPLETED", n4.getStatus());
        ArgumentCaptor<LearningEvent> event = ArgumentCaptor.forClass(LearningEvent.class);
        verify(events, times(1)).publishEvent(event.capture());
        assertEquals(LearningEventType.PATH_COMPLETED, event.getValue().type());
        assertEquals(5L, event.getValue().referenceId());
    }

    @Test void retryAfterCompletionReturnsCompletedStateWithoutASecondEvent() {
        packageCounts(25, 25, 13, 25);
        qualifies();
        service.completeFromMock(USER, ATTEMPT);

        var retry = service.completeFromMock(USER, ATTEMPT);

        assertFalse(retry.newlyCompleted());
        assertEquals("COMPLETED", retry.status());
        assertEquals(ATTEMPT, path.getCompletionAttemptId());
        verify(events, times(1)).publishEvent(any(Object.class));
        verify(qualification, times(1)).verify(any(), any(), any());
    }

    @Test void belowFiftyPercentIsRejectedBeforeTheAttemptIsEvenConsulted() {
        packageCounts(25, 25, 12, 25);
        var ex = assertThrows(ApiException.class, () -> service.completeFromMock(USER, ATTEMPT));
        assertEquals(ErrorCode.PREREQUISITE_NOT_MET, ex.getErrorCode());
        verifyNoInteractions(qualification, events);
        assertEquals("ACTIVE", path.getStatus());
    }

    @Test void incompletePriorStageBlocksEvenWithAQualifyingAttempt() {
        packageCounts(24, 25, 25, 25);
        qualifies();
        var ex = assertThrows(ApiException.class, () -> service.completeFromMock(USER, ATTEMPT));
        assertEquals(ErrorCode.PREREQUISITE_NOT_MET, ex.getErrorCode());
        verifyNoInteractions(events);
    }

    @Test void failedQualificationLeavesThePathActiveAndPublishesNothing() {
        packageCounts(25, 25, 25, 25);
        when(qualification.verify(USER, ATTEMPT, "N4"))
                .thenReturn(new JlptQualificationPort.Result(false, "SECTION_BELOW_PASSING"));

        var ex = assertThrows(ApiException.class, () -> service.completeFromMock(USER, ATTEMPT));

        assertEquals(ErrorCode.PREREQUISITE_NOT_MET, ex.getErrorCode());
        assertEquals("ACTIVE", path.getStatus());
        assertNull(path.getCompletedAt());
        verifyNoInteractions(events);
        verify(paths, never()).save(any());
    }

    @Test void targetLevelPassedToTheQualificationCheckIsTheServerDerivedFinalStage() {
        packageCounts(25, 25, 25, 25);
        qualifies();
        service.completeFromMock(USER, ATTEMPT);
        verify(qualification).verify(USER, ATTEMPT, "N4");
    }

    @Test void n3PathCompletionVerifiesTheServerDerivedN3Target() {
        n4.setLevel("N3");
        packageCounts(25, 25, 13, 25);
        when(qualification.verify(USER, ATTEMPT, "N3"))
                .thenReturn(new JlptQualificationPort.Result(true, null));

        var result = service.completeFromMock(USER, ATTEMPT);

        assertTrue(result.newlyCompleted());
        assertEquals("N3", result.targetLevel());
        verify(qualification).verify(USER, ATTEMPT, "N3");
    }

    @Test void noActivePathAndNoCompletedPathIsNotFound() {
        when(paths.lockActiveInteractive(USER)).thenReturn(Optional.empty());
        when(paths.findTopByUserIdAndExperienceVersionOrderByIdDesc(USER, LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(Optional.empty());
        var ex = assertThrows(ApiException.class, () -> service.completeFromMock(USER, ATTEMPT));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test void supersededPathIsNotTreatedAsCompleted() {
        path.setStatus("SUPERSEDED");
        when(paths.lockActiveInteractive(USER)).thenReturn(Optional.empty());
        when(paths.findTopByUserIdAndExperienceVersionOrderByIdDesc(USER, LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(Optional.of(path));
        var ex = assertThrows(ApiException.class, () -> service.completeFromMock(USER, ATTEMPT));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        verifyNoInteractions(events);
    }

    @Test void retryWithADifferentAttemptIsRejectedEvenThoughThePathIsCompleted() {
        packageCounts(25, 25, 13, 25);
        qualifies();
        service.completeFromMock(USER, ATTEMPT);

        var ex = assertThrows(ApiException.class, () -> service.completeFromMock(USER, 999L));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        verify(events, times(1)).publishEvent(any(Object.class));
        verify(qualification, times(1)).verify(any(), any(), any());
    }

    @Test void aPathCompletedWithoutARecordedAttemptCannotBeReplayedByAnyAttempt() {
        path.setStatus("COMPLETED");
        path.setCompletionAttemptId(null);
        when(paths.lockActiveInteractive(USER)).thenReturn(Optional.empty());
        when(paths.findTopByUserIdAndExperienceVersionOrderByIdDesc(USER, LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(Optional.of(path));

        var ex = assertThrows(ApiException.class, () -> service.completeFromMock(USER, ATTEMPT));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }
}
