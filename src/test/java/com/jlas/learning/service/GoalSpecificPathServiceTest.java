package com.jlas.learning.service;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.dictionary.entity.*;
import com.jlas.learning.dto.InteractiveCompositionResult;
import com.jlas.learning.entity.*;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoalSpecificPathServiceTest {
    @Mock UserRepository users;
    @Mock LearningGoalRepository goals;
    @Mock UserLearningPathRepository paths;
    @Mock UserLearningStageRepository stages;
    @Mock GoalLessonSelectionService selection;
    @Mock InteractivePackageComposer composer;
    GoalSpecificPathService service;

    @BeforeEach void setUp() {
        service = new GoalSpecificPathService(users, goals, paths, stages, selection, composer);
    }

    @Test void travelCreatesSurveyOnlyInteractivePathAndComposesApprovedLessons() {
        Lesson lesson = publishedLesson(11L);
        when(selection.select(7L, 50L)).thenReturn(new GoalLessonSelectionService.GoalLessonSelection(
                50L, 3L, "BALANCED", List.of(lesson), Map.of(11L, "BASIC")));
        when(goals.findById(3L)).thenReturn(Optional.of(LearningGoal.builder().id(3L).goalCode("TRAVEL")
                .displayNameVi("Du lịch").active(true).reviewStatus("APPROVED").build()));
        when(users.findById(7L)).thenReturn(Optional.of(User.builder().id(7L).build()));
        when(paths.findByUserIdAndSurveyIdAndExperienceVersion(7L, 50L, LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(Optional.empty());
        when(paths.findMaxVersionByUserId(7L)).thenReturn(2);
        when(paths.save(any())).thenAnswer(invocation -> { var path = invocation.getArgument(0, UserLearningPath.class); path.setId(80L); return path; });
        when(stages.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(composer.compose(any(), any())).thenReturn(new InteractiveCompositionResult(true, 1, List.of()));

        var result = service.create(7L, 50L, null);

        assertTrue(result.created());
        assertEquals(80L, result.pathId());
        verify(composer).compose(any(), argThat(schedule -> schedule.size() == 1
                && "BASIC".equals(schedule.getFirst().resource().level())));
    }

    /** Owner 2026-10-21 (OD-38): the survey's time answer is the path's pace. */
    @Test void pathTakesMinutesPerDayFromTheSurvey() {
        Lesson lesson = publishedLesson(13L);
        when(selection.select(7L, 52L)).thenReturn(new GoalLessonSelectionService.GoalLessonSelection(
                52L, 3L, "BALANCED", List.of(lesson), Map.of(13L, "BASIC"), 30));
        when(goals.findById(3L)).thenReturn(Optional.of(LearningGoal.builder().id(3L).goalCode("TRAVEL")
                .displayNameVi("Du lịch").active(true).reviewStatus("APPROVED").build()));
        when(users.findById(7L)).thenReturn(Optional.of(User.builder().id(7L).build()));
        when(paths.findByUserIdAndSurveyIdAndExperienceVersion(7L, 52L, LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(Optional.empty());
        when(paths.save(any())).thenAnswer(invocation -> invocation.getArgument(0, UserLearningPath.class));
        when(stages.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(composer.compose(any(), any())).thenReturn(new InteractiveCompositionResult(true, 1, List.of()));

        service.create(7L, 52L, null);

        verify(paths).save(argThat(path -> path.getMinutesPerDay() == 30));
    }

    @Test void brseIsSurveyOnlyAndRejectsAssessmentAttempt() {
        Lesson lesson = publishedLesson(12L);
        when(selection.select(7L, 51L)).thenReturn(new GoalLessonSelectionService.GoalLessonSelection(
                51L, 4L, "COMMUNICATION_FIRST", List.of(lesson), Map.of(12L, "BASIC")));
        when(goals.findById(4L)).thenReturn(Optional.of(LearningGoal.builder().id(4L).goalCode("BA")
                .displayNameVi("BrSE").active(true).reviewStatus("APPROVED").build()));

        assertThrows(ApiException.class, () -> service.create(7L, 51L, 9L));

        when(users.findById(7L)).thenReturn(Optional.of(User.builder().id(7L).build()));
        when(paths.findByUserIdAndSurveyIdAndExperienceVersion(7L, 51L, LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(Optional.empty());
        when(paths.findMaxVersionByUserId(7L)).thenReturn(2);
        when(paths.save(any())).thenAnswer(invocation -> { var path = invocation.getArgument(0, UserLearningPath.class); path.setId(81L); return path; });
        when(stages.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(composer.compose(any(), any())).thenReturn(new InteractiveCompositionResult(true, 1, List.of()));

        var result = service.create(7L, 51L, null);

        assertTrue(result.created());
        assertEquals(81L, result.pathId());
        verify(paths).save(argThat(path -> path.getAssessmentAttemptId() == null
                && path.getCurrentLevel() == null && path.getMinutesPerDay() == 20));
    }

    @Test void retryBySameSurveyIsIdempotent() {
        when(selection.select(7L, 50L)).thenReturn(new GoalLessonSelectionService.GoalLessonSelection(
                50L, 3L, "BALANCED", List.of(), Map.of()));
        when(goals.findById(3L)).thenReturn(Optional.of(LearningGoal.builder().id(3L).goalCode("DAILY_LIFE")
                .active(true).reviewStatus("APPROVED").build()));
        var existing = UserLearningPath.builder().id(99L).experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).build();
        when(paths.findByUserIdAndSurveyIdAndExperienceVersion(7L, 50L, LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(Optional.of(existing));

        var result = service.create(7L, 50L, null);

        assertFalse(result.created());
        assertEquals(99L, result.pathId());
        verifyNoInteractions(users, stages, composer);
    }

    private static Lesson publishedLesson(Long id) {
        Textbook textbook = Textbook.builder().id(20L).build();
        Unit unit = Unit.builder().id(21L).textbook(textbook).orderIndex(1).build();
        return Lesson.builder().id(id).unit(unit).title("Lesson " + id).orderIndex(1)
                .estimatedMinutes(20).skill("SPEAKING").status("PUBLISHED").build();
    }
}
