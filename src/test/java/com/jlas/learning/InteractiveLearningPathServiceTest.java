package com.jlas.learning;

import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.*;
import com.jlas.learning.repository.LearningPathPackageRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.service.impl.InteractiveLearningPathServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InteractiveLearningPathServiceTest {
    @Mock UserLearningPathRepository paths;
    @Mock LearningPathPackageRepository packages;
    @Mock UserLearningPathItemRepository items;
    InteractiveLearningPathServiceImpl service;

    @BeforeEach void setUp() { service = new InteractiveLearningPathServiceImpl(paths, packages, items); }

    @Test void currentReadsOnlyInteractiveVersionAndKeepsPackageOrder() {
        var path = interactivePath();
        var second = learningPackage(path, 12L, 2, LearningPackageStatus.LOCKED);
        var first = learningPackage(path, 11L, 1, LearningPackageStatus.AVAILABLE);
        when(paths.findByUserIdAndStatusAndExperienceVersion(7L, "ACTIVE", LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(Optional.of(path));
        when(packages.findByLearningPathIdOrderByOrderIndex(2L)).thenReturn(List.of(first, second));
        when(items.findByLearningPackageIdOrderByOrderIndex(anyLong())).thenAnswer(inv -> activities(
                inv.getArgument(0, Long.class).equals(11L) ? first : second));

        var result = service.current(7L);

        assertEquals(List.of(11L, 12L), result.packages().stream().map(p -> p.id()).toList());
        assertEquals(11L, result.currentPackageId());
    }

    @Test void anotherUsersPackageLooksNotFound() {
        when(packages.findOwnedById(99L, 7L)).thenReturn(Optional.empty());
        ApiException error = assertThrows(ApiException.class, () -> service.packageDetail(7L, 99L));
        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test void legacyPackageIsNotExposedThroughV2Api() {
        var path = interactivePath();
        path.setExperienceVersion(LearningExperienceVersion.LEGACY_V1);
        var pkg = learningPackage(path, 11L, 1, LearningPackageStatus.AVAILABLE);
        when(packages.findOwnedById(11L, 7L)).thenReturn(Optional.of(pkg));
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

    @Test void startIsIdempotentAndMovesOnlyFirstActivityToStudying() {
        var pkg = learningPackage(interactivePath(), 11L, 1, LearningPackageStatus.AVAILABLE);
        var activities = activities(pkg);
        when(packages.findOwnedByIdForUpdate(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities);

        var first = service.start(7L, 11L);
        var startedAt = pkg.getStartedAt();
        var second = service.start(7L, 11L);

        assertEquals(LearningPackageStatus.IN_PROGRESS, first.status());
        assertEquals(LearningActivityState.STUDYING, activities.getFirst().getActivityState());
        assertEquals(LearningActivityState.LOCKED, activities.get(1).getActivityState());
        assertEquals(startedAt, pkg.getStartedAt());
        assertEquals(first.resumeActivityType(), second.resumeActivityType());
    }

    @Test void incompleteTargetPackageIsBlockedContent() {
        var pkg = learningPackage(interactivePath(), 11L, 1, LearningPackageStatus.AVAILABLE);
        when(packages.findOwnedById(11L, 7L)).thenReturn(Optional.of(pkg));
        when(items.findByLearningPackageIdOrderByOrderIndex(11L)).thenReturn(activities(pkg).subList(0, 3));
        ApiException error = assertThrows(ApiException.class, () -> service.packageDetail(7L, 11L));
        assertEquals(ErrorCode.BLOCKED_CONTENT, error.getErrorCode());
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

    private List<UserLearningPathItem> activities(LearningPathPackage pkg) {
        var types = List.of(LearningActivityType.VOCABULARY, LearningActivityType.KANJI,
                LearningActivityType.GRAMMAR, LearningActivityType.DIALOGUE);
        return java.util.stream.IntStream.range(0, types.size()).mapToObj(index -> UserLearningPathItem.builder()
                .id((long) index + 1).learningPath(pkg.getLearningPath()).learningPackage(pkg)
                .orderIndex(index + 1).activityType(types.get(index)).activityState(LearningActivityState.LOCKED).build()).toList();
    }
}
