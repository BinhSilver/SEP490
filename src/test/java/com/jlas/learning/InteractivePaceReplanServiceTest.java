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
import com.jlas.learning.service.impl.InteractivePaceReplanServiceImpl.Slot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** [JLAS-W0108] Task 10 - pace replan changes only the day layout of unfinished packages. Fixtures are
 * synthetic (ids and minutes chosen for arithmetic clarity), not curriculum content. */
class InteractivePaceReplanServiceTest {
    private static final Long USER = 7L;

    UserLearningPathRepository paths = mock(UserLearningPathRepository.class);
    LearningPathPackageRepository packages = mock(LearningPathPackageRepository.class);
    UserLearningPathItemRepository items = mock(UserLearningPathItemRepository.class);
    InteractivePaceReplanServiceImpl service;
    UserLearningPath path;
    List<LearningPathPackage> pkgs;

    @BeforeEach
    void setUp() {
        service = new InteractivePaceReplanServiceImpl(paths, packages, items);
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
        when(items.findByLearningPathIdOrderByOrderIndex(1L)).thenReturn(List.of());
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
    void layoutPacksSmallPackagesAndSpillsLargeOnes() {
        Map<Long, Integer> days = InteractivePaceReplanServiceImpl.layout(
                List.of(new Slot(10L, 30), new Slot(11L, 30), new Slot(12L, 30)), 60, 5);
        assertEquals(5, days.get(10L));
        assertEquals(5, days.get(11L));
        assertEquals(6, days.get(12L));
        Map<Long, Integer> lighter = InteractivePaceReplanServiceImpl.layout(
                List.of(new Slot(10L, 30), new Slot(11L, 30)), 15, 1);
        assertEquals(1, lighter.get(10L));
        assertEquals(3, lighter.get(11L));
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
        assertEquals(2, preview.newRemainingDays());
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
        // 30-minute packages at 20 min/day: 30 => 1.5 days, so the next package starts in the spill day
        assertEquals(3, pkgs.get(2).getDayNumber());
        assertEquals(4, pkgs.get(3).getDayNumber());
        assertEquals(6, pkgs.get(4).getDayNumber());
        assertEquals(7, pkgs.get(5).getDayNumber());
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
        UserLearningPathItem item = new UserLearningPathItem();
        item.setLearningPackage(pkgs.get(5));
        item.setDayNumber(6);
        when(items.findByLearningPathIdOrderByOrderIndex(1L)).thenReturn(List.of(item));
        var preview = service.preview(USER, "FASTER");
        service.apply(USER, new LearningPaceRequest("FASTER", preview.newMinutesPerDay(), preview.previewToken()));
        assertEquals(pkgs.get(5).getDayNumber(), item.getDayNumber());
    }
}
