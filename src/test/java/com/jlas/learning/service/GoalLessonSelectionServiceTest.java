package com.jlas.learning.service;

import com.jlas.common.exception.ApiException;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.repository.LessonRepository;
import com.jlas.learning.entity.*;
import com.jlas.learning.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoalLessonSelectionServiceTest {
    @Mock UserGoalSurveySubmissionRepository submissions;
    @Mock UserGoalSurveySelectionRepository selections;
    @Mock LearningSurveyOptionRepository options;
    @Mock LessonLearningScopeRepository scopes;
    @Mock LessonRepository lessons;
    @Mock CompetencyLevelRepository competencyLevels;
    GoalLessonSelectionService service;

    @BeforeEach void setUp() {
        service = new GoalLessonSelectionService(submissions, selections, options, scopes, lessons, competencyLevels);
    }

    @Test
    void selectedTopicPlusRequiredFoundationAreOrderedWithoutTreatingPreferenceAsMastery() {
        UserGoalSurveySubmission submission = UserGoalSurveySubmission.builder().id(50L).userId(7L).goalId(3L)
                .learningMode("BALANCED").status("SUBMITTED").version(1).build();
        when(submissions.findByIdAndUserId(50L, 7L)).thenReturn(Optional.of(submission));
        when(selections.findBySubmissionId(50L)).thenReturn(List.of(
                UserGoalSurveySelection.builder().optionId(20L).build()));
        when(options.findAllById(List.of(20L))).thenReturn(List.of(
                LearningSurveyOption.builder().id(20L).questionId(10L).topicId(100L).build()));
        when(scopes.findByGoalIdAndReviewStatusOrderByScopeOrderAsc(3L, "APPROVED")).thenReturn(List.of(
                LessonLearningScope.builder().lessonId(1L).goalId(3L).topicId(999L).required(true)
                        .scopeOrder(1).learningMode("BALANCED").reviewStatus("APPROVED").build(),
                LessonLearningScope.builder().lessonId(2L).goalId(3L).topicId(100L).required(false)
                        .scopeOrder(2).learningMode("BALANCED").reviewStatus("APPROVED").build(),
                LessonLearningScope.builder().lessonId(3L).goalId(3L).topicId(200L).required(false)
                        .scopeOrder(3).learningMode("BALANCED").reviewStatus("APPROVED").build()));
        Lesson foundation = Lesson.builder().id(1L).status("PUBLISHED").build();
        Lesson selected = Lesson.builder().id(2L).status("PUBLISHED").build();
        when(lessons.findAllById(List.of(1L, 2L))).thenReturn(List.of(selected, foundation));

        var result = service.select(7L, 50L);

        assertEquals(List.of(1L, 2L), result.lessons().stream().map(Lesson::getId).toList());
        assertEquals("BALANCED", result.learningMode());
    }

    @Test
    void unapprovedOrMissingPublishedContentFailsClosed() {
        UserGoalSurveySubmission submission = UserGoalSurveySubmission.builder().id(51L).userId(7L).goalId(4L)
                .learningMode("COMMUNICATION_FIRST").status("SUBMITTED").version(1).build();
        when(submissions.findByIdAndUserId(51L, 7L)).thenReturn(Optional.of(submission));
        when(selections.findBySubmissionId(51L)).thenReturn(List.of());
        when(scopes.findByGoalIdAndReviewStatusOrderByScopeOrderAsc(4L, "APPROVED")).thenReturn(List.of());

        assertThrows(ApiException.class, () -> service.select(7L, 51L));
    }
}
