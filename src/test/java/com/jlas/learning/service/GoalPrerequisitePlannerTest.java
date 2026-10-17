package com.jlas.learning.service;

import com.jlas.common.exception.ApiException;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.repository.LessonRepository;
import com.jlas.learning.entity.LessonPrerequisite;
import com.jlas.learning.repository.LessonPrerequisiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoalPrerequisitePlannerTest {
    @Mock LessonPrerequisiteRepository prerequisites;
    @Mock LessonRepository lessons;
    GoalPrerequisitePlanner planner;

    @BeforeEach void setUp() { planner = new GoalPrerequisitePlanner(prerequisites, lessons); }

    @Test void hardSequenceOrdersRequiredLessonBeforeTarget() {
        Lesson required = Lesson.builder().id(1L).status("PUBLISHED").build();
        Lesson target = Lesson.builder().id(2L).status("PUBLISHED").build();
        when(prerequisites.findByLessonIdInAndReviewStatus(List.of(2L, 1L), "APPROVED")).thenReturn(List.of(
                LessonPrerequisite.builder().lessonId(2L).prerequisiteType("HARD_SEQUENCE")
                        .requiredLessonId(1L).blockingAccess(true).reviewStatus("APPROVED").build()));

        assertEquals(List.of(1L, 2L), planner.plan(List.of(target, required)).stream().map(Lesson::getId).toList());
    }

    @Test void missingHardSequenceFailsClosed() {
        Lesson target = Lesson.builder().id(2L).status("PUBLISHED").build();
        when(prerequisites.findByLessonIdInAndReviewStatus(List.of(2L), "APPROVED")).thenReturn(List.of(
                LessonPrerequisite.builder().lessonId(2L).prerequisiteType("HARD_SEQUENCE")
                        .requiredLessonId(1L).blockingAccess(true).reviewStatus("APPROVED").build()));

        assertThrows(ApiException.class, () -> planner.plan(List.of(target)));
    }

    @Test void justInTimeWarmupIsInsertedButRecommendedAndOptionalNeverBlock() {
        Lesson target = Lesson.builder().id(2L).status("PUBLISHED").build();
        Lesson warmup = Lesson.builder().id(3L).status("PUBLISHED").build();
        when(prerequisites.findByLessonIdInAndReviewStatus(List.of(2L), "APPROVED")).thenReturn(List.of(
                LessonPrerequisite.builder().lessonId(2L).prerequisiteType("JIT_WARMUP")
                        .warmupLessonId(3L).reviewStatus("APPROVED").build(),
                LessonPrerequisite.builder().lessonId(2L).prerequisiteType("RECOMMENDED_FOUNDATION")
                        .requiredLessonId(99L).reviewStatus("APPROVED").build(),
                LessonPrerequisite.builder().lessonId(2L).prerequisiteType("OPTIONAL_ENRICHMENT")
                        .requiredLessonId(98L).reviewStatus("APPROVED").build()));
        when(lessons.findAllById(List.of(3L))).thenReturn(List.of(warmup));

        assertEquals(List.of(3L, 2L), planner.plan(List.of(target)).stream().map(Lesson::getId).toList());
    }
}
