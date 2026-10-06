package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.LearningPacePreviewResponse;
import com.jlas.learning.dto.LearningPaceRequest;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningPackageStatus;
import com.jlas.learning.repository.LearningPathPackageRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.service.impl.InteractivePaceReplanServiceImpl;
import java.util.ArrayList;
import java.util.List;
import com.jlas.learning.enums.LearningActivityState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** [JLAS-W0108] Task 10 - pace replan changes only the day layout of unfinished packages. Fixtures are
 * synthetic (ids and minutes chosen for arithmetic clarity), not curriculum content. */
class InteractivePaceReplanServiceTest {
    private static final Long USER = 7L;

    UserLearningPathRepository paths = mock(UserLearningPathRepository.class);
    LearningPathPackageRepository packages = mock(LearningPathPackageRepository.class);
    UserLearningPathItemRepository items = mock(UserLearningPathItemRepository.class);
    com.jlas.learning.repository.LearningActivityMemberRepository members = mock(com.jlas.learning.repository.LearningActivityMemberRepository.class);
    InteractivePaceReplanServiceImpl service;
    UserLearningPath path;
    List<LearningPathPackage> pkgs;
    List<UserLearningPathItem> activities;

    @BeforeEach
    void setUp() {
        service = new InteractivePaceReplanServiceImpl(paths, packages, items, members);
        User user = new User();
        user.setId(USER);
        path = UserLearningPath.builder().id(1L).user(user).experienceVersion(LearningExperienceVersion.INTERACTIVE_V2)
                .status("ACTIVE").targetLevel("N4").minutesPerDay(30).version(3).build();
        pkgs = new ArrayList<>();
        // two completed (days 1,2), four unfinished of 30 minutes each on days 3..6
        for (int i = 1; i <= 6; i++) {
            pkgs.add(LearningPathPackage.builder().id((long) i).learningPath(path).orderIndex(i).dayNumber(i)
                    .estimatedMinutes(30)
                    .status(i <= 2 ? LearningPackageStatus.COMPLETED : LearningPackageStatus.AVAILABLE).build());
        }
        when(paths.lockActiveInteractive(USER)).thenReturn(java.util.Optional.of(path));
        when(paths.findByUserIdAndStatusAndExperienceVersion(USER, "ACTIVE", LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(java.util.Optional.of(path));
        when(packages.findByLearningPathIdOrderByOrderIndex(1L)).thenReturn(pkgs);
        when(packages.findByPathForUpdate(1L)).thenReturn(pkgs);
        activities = new ArrayList<>();
        for (var pkg : pkgs) {
            for (int part = 0; part < 2; part++) {
                activities.add(UserLearningPathItem.builder().id(pkg.getId() * 10 + part)
                        .learningPath(path).learningPackage(pkg).orderIndex(activities.size() + 1)
                        .dayNumber(pkg.getDayNumber()).estimatedMinutes(15)
                        .activityState(pkg.getStatus() == LearningPackageStatus.COMPLETED
                                ? LearningActivityState.COMPLETED : LearningActivityState.LOCKED).build());
            }
        }
        when(items.findByLearningPathIdOrderByOrderIndex(1L)).thenReturn(activities);
        when(items.findByPathForUpdate(1L)).thenReturn(activities);
    }

    @Test
    void presetsStepThroughFifteenTwentyThirtyFortyFiveSixty() {
        assertEquals(20, InteractivePaceReplanServiceImpl.nextPreset(30, "LIGHTER"));
        assertEquals(15, InteractivePaceReplanServiceImpl.nextPreset(20, "LIGHTER"));
        assertEquals(15, InteractivePaceReplanServiceImpl.nextPreset(15, "LIGHTER"));
        assertEquals(45, InteractivePaceReplanServiceImpl.nextPreset(30, "FASTER"));
        assertEquals(60, InteractivePaceReplanServiceImpl.nextPreset(45, "FASTER"));
        assertEquals(60, InteractivePaceReplanServiceImpl.nextPreset(60, "FASTER"));
        assertEquals(30, InteractivePaceReplanServiceImpl.nextPreset(30, "SAME"));
        assertEquals(20, InteractivePaceReplanServiceImpl.nextPreset(25, "LIGHTER"));
        assertEquals(30, InteractivePaceReplanServiceImpl.nextPreset(25, "FASTER"));
    }

    @Test
    void previewWritesNothingAndReportsRemainingDays() {
        path.setMinutesPerDay(45);
        LearningPacePreviewResponse preview = service.preview(USER, "FASTER");
        assertEquals(45, preview.currentMinutesPerDay());
        assertEquals(60, preview.newMinutesPerDay());
        assertTrue(preview.changed());
        assertEquals(4, preview.remainingPackages());
        assertEquals(4, preview.currentRemainingDays());
        // Logical packages retain distinct start days (schema has a unique path/day key).
        assertEquals(4, preview.newRemainingDays());
        assertNotNull(preview.previewToken());
        verify(paths, never()).save(any());
        verify(packages, never()).save(any());
    }

    @Test
    void applyChangesOnlyDaysOfUnfinishedPackagesAndKeepsIdentityTargetAndCompletion() {
        var preview = service.preview(USER, "LIGHTER");
        var before = pkgs.stream().map(LearningPathPackage::getId).toList();
        service.apply(USER, new LearningPaceRequest("LIGHTER", preview.newMinutesPerDay(), preview.previewToken()));

        assertEquals(20, path.getMinutesPerDay());
        assertEquals("N4", path.getTargetLevel());
        assertEquals(before, pkgs.stream().map(LearningPathPackage::getId).toList());
        assertEquals(1, pkgs.get(0).getDayNumber());
        assertEquals(2, pkgs.get(1).getDayNumber());
        assertEquals(LearningPackageStatus.COMPLETED, pkgs.get(0).getStatus());
        assertEquals(LearningPackageStatus.COMPLETED, pkgs.get(1).getStatus());
        // Each 15-minute activity remains intact; no day is assigned 30 minutes at a 20-minute pace.
        assertEquals(3, pkgs.get(2).getDayNumber());
        assertEquals(5, pkgs.get(3).getDayNumber());
        assertEquals(7, pkgs.get(4).getDayNumber());
        assertEquals(9, pkgs.get(5).getDayNumber());
        assertEquals(List.of(3, 4, 5, 6, 7, 8, 9, 10),
                activities.subList(4, 12).stream().map(UserLearningPathItem::getDayNumber).toList());
        assertEquals(8, preview.newRemainingDays());
    }

    @Test
    void staleTokenIsRejectedWithoutWriting() {
        var preview = service.preview(USER, "FASTER");
        pkgs.get(2).setStatus(LearningPackageStatus.COMPLETED); // progress moved after preview
        ApiException error = assertThrows(ApiException.class,
                () -> service.apply(USER, new LearningPaceRequest("FASTER", preview.newMinutesPerDay(), preview.previewToken())));
        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        assertEquals(30, path.getMinutesPerDay());
    }

    @Test
    void doubleClickAfterAppliedIsIdempotent() {
        var preview = service.preview(USER, "FASTER");
        service.apply(USER, new LearningPaceRequest("FASTER", preview.newMinutesPerDay(), preview.previewToken()));
        int daysAfterFirst = pkgs.get(5).getDayNumber();
        var again = service.apply(USER, new LearningPaceRequest("FASTER", preview.newMinutesPerDay(), preview.previewToken()));
        assertEquals(45, path.getMinutesPerDay());
        assertEquals(daysAfterFirst, pkgs.get(5).getDayNumber());
        assertFalse(again.changed());
    }

    @Test
    void unknownDirectionAndMissingPathAreRejected() {
        assertThrows(ApiException.class, () -> service.preview(USER, "TURBO"));
        when(paths.findByUserIdAndStatusAndExperienceVersion(USER, "ACTIVE", LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(java.util.Optional.empty());
        ApiException error = assertThrows(ApiException.class, () -> service.preview(USER, "SAME"));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test
    void itemDaysFollowTheirPackage() {
        UserLearningPathItem item = activities.getLast();
        var preview = service.preview(USER, "FASTER");
        service.apply(USER, new LearningPaceRequest("FASTER", preview.newMinutesPerDay(), preview.previewToken()));
        assertEquals(pkgs.get(5).getDayNumber(), item.getDayNumber());
    }

    @Test
    void loweringPaceRejectsUnsplittableActivityBeforeChangingProgress() {
        activities.get(4).setEstimatedMinutes(25);
        var error = assertThrows(ApiException.class, () -> service.preview(USER, "LIGHTER"));
        assertEquals(ErrorCode.BLOCKED_CONTENT, error.getErrorCode());
        assertEquals(30, path.getMinutesPerDay());
        assertEquals(3, activities.get(4).getDayNumber());
    }

    @Test
    void rejectsPreviewWhenActivityWorkloadChangedEvenIfPackageStatusDidNot() {
        var preview = service.preview(USER, "LIGHTER");
        activities.get(4).setEstimatedMinutes(16);
        var error = assertThrows(ApiException.class, () -> service.apply(USER,
                new LearningPaceRequest("LIGHTER", 20, preview.previewToken())));
        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        assertEquals(30, path.getMinutesPerDay());
    }

    @Test
    void allPresetsConserveActivitiesAndRespectDailyBudgetWithoutChangingStageUnits() {
        for (int current : List.of(20, 30, 45, 60)) {
            setUp();
            path.setMinutesPerDay(current);
            var ids = activities.stream().map(UserLearningPathItem::getId).toList();
            var preview = service.preview(USER, "LIGHTER");
            service.apply(USER, new LearningPaceRequest("LIGHTER", preview.newMinutesPerDay(), preview.previewToken()));
            var minutes = new java.util.HashMap<Integer, Integer>();
            activities.subList(4, 12).forEach(a -> minutes.merge(a.getDayNumber(), a.getEstimatedMinutes(), Integer::sum));
            assertTrue(minutes.values().stream().allMatch(m -> m <= preview.newMinutesPerDay()));
            assertEquals(ids, activities.stream().map(UserLearningPathItem::getId).toList());
            assertEquals(6, pkgs.size());
            assertEquals(2, pkgs.stream().filter(p -> p.getStatus() == LearningPackageStatus.COMPLETED).count());
        }
    }

    @Test
    void currentPackageAndItsActivityHistoryStayOnTheirOriginalDay() {
        pkgs.get(2).setStatus(LearningPackageStatus.IN_PROGRESS);
        activities.get(4).setActivityState(LearningActivityState.PASSED);
        activities.get(4).setAssessmentStatus("PASSED");
        activities.get(5).setActivityState(LearningActivityState.STUDYING);
        var preview = service.preview(USER, "LIGHTER");
        service.apply(USER, new LearningPaceRequest("LIGHTER", 20, preview.previewToken()));
        assertEquals(3, activities.get(4).getDayNumber());
        assertEquals(3, activities.get(5).getDayNumber());
        assertEquals("PASSED", activities.get(4).getAssessmentStatus());
        assertEquals(LearningActivityState.STUDYING, activities.get(5).getActivityState());
        assertEquals(List.of(4,5,6,7,8,9), activities.subList(6,12).stream()
                .map(UserLearningPathItem::getDayNumber).toList());
    }

    @Test
    void samePaceDoesNotRescheduleOrRejectLegacyOversizedContent() {
        activities.get(4).setEstimatedMinutes(75);
        var preview = service.preview(USER, "SAME");
        assertFalse(preview.changed());
        assertEquals(preview.currentRemainingDays(), preview.newRemainingDays());
        service.apply(USER, new LearningPaceRequest("SAME", 30, preview.previewToken()));
        assertEquals(3, activities.get(4).getDayNumber());
        verify(packages, never()).save(any());
    }

    @Test
    void stalePreviewIsConflictEvenWhenChangedContentNoLongerFits() {
        var preview = service.preview(USER, "LIGHTER");
        activities.get(4).setEstimatedMinutes(25);
        var error = assertThrows(ApiException.class, () -> service.apply(USER,
                new LearningPaceRequest("LIGHTER", 20, preview.previewToken())));
        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        assertEquals(30, path.getMinutesPerDay());
    }

    @Test void lockedSummaryCannotOverrideExistingStudyOrQuizHistory() {
        activities.get(4).setEstimatedMinutes(45);
        when(items.findActivityIdsWithHistory(1L)).thenReturn(List.of(activities.get(4).getId()));
        var preview = service.preview(USER, "LIGHTER");
        service.apply(USER, new LearningPaceRequest("LIGHTER", 20, preview.previewToken()));
        assertEquals(45, activities.get(4).getEstimatedMinutes());
        assertEquals(3, activities.get(4).getDayNumber());
        assertEquals(3, activities.get(5).getDayNumber());
        assertEquals(4, activities.get(6).getDayNumber());
    }

    @Test void membershipRevisionChangeRequiresNewPreview() {
        var member = com.jlas.learning.entity.LearningActivityMember.builder().id(90L).activity(activities.get(4))
                .memberType(com.jlas.learning.enums.LearningMemberType.VOCABULARY_ENTRY)
                .canonicalId(81L).orderIndex(1).sourceRevision("v1").build();
        when(members.findByPathInStudyOrder(1L)).thenReturn(List.of(member));
        var preview = service.preview(USER, "LIGHTER");
        member.setSourceRevision("v2");
        var error = assertThrows(ApiException.class, () -> service.apply(USER,
                new LearningPaceRequest("LIGHTER", 20, preview.previewToken())));
        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        assertEquals(30, path.getMinutesPerDay());
        assertEquals(3, activities.get(4).getDayNumber());
    }

    @Test void openingStudyAfterPreviewInvalidatesConfirmation() {
        var preview = service.preview(USER, "LIGHTER");
        when(items.findActivityIdsWithHistory(1L)).thenReturn(List.of(activities.get(4).getId()));
        var error = assertThrows(ApiException.class, () -> service.apply(USER,
                new LearningPaceRequest("LIGHTER", 20, preview.previewToken())));
        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        assertEquals(30, path.getMinutesPerDay());
    }
}
