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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoalLessonSelectionServiceTest {
    @Mock UserGoalSurveySubmissionRepository submissions;
    @Mock LessonLearningScopeRepository scopes;
    @Mock LessonRepository lessons;
    @Mock CompetencyLevelRepository competencyLevels;
    @Mock com.jlas.learning.repository.LearningTopicRepository topics;
    @Mock com.jlas.learning.repository.UserGoalSurveySelectionRepository selections;
    @Mock com.jlas.learning.repository.LearningSurveyOptionRepository options;
    GoalLessonSelectionService service;

    @BeforeEach void setUp() {
        service = new GoalLessonSelectionService(submissions, scopes, lessons, competencyLevels, topics, selections, options);
    }

    /** Owner 2026-10-19: topics chosen in the survey come first, each followed by its own dialogues; nothing is removed. */
    @Test
    void surveyFocusTopicsComeFirstTogetherWithTheirDialogueSituations() {
        UserGoalSurveySubmission submission = UserGoalSurveySubmission.builder().id(60L).userId(7L).goalId(3L)
                .learningMode("BALANCED").status("SUBMITTED").version(1).build();
        when(submissions.findByIdAndUserId(60L, 7L)).thenReturn(Optional.of(submission));
        when(scopes.findByGoalIdAndReviewStatusOrderByScopeOrderAsc(3L, "APPROVED")).thenReturn(List.of(
                scope(1L, 100L, 1), scope(2L, 101L, 2), scope(3L, 200L, 3), scope(4L, 201L, 4)));
        when(lessons.findAllById(any())).thenReturn(List.of(
                published(1L), published(2L), published(3L), published(4L)));
        when(topics.findAllById(any())).thenReturn(List.of(topic(100L, null), topic(101L, 100L),
                topic(200L, null), topic(201L, 200L)));
        when(selections.findBySubmissionId(60L)).thenReturn(List.of(
                com.jlas.learning.entity.UserGoalSurveySelection.builder().submissionId(60L).questionId(9L).optionId(77L).build()));
        when(options.findAllById(any())).thenReturn(List.of(
                com.jlas.learning.entity.LearningSurveyOption.builder().id(77L).questionId(9L).topicId(200L).build()));

        var result = service.select(7L, 60L);

        assertEquals(List.of(3L, 4L, 1L, 2L), result.lessons().stream().map(Lesson::getId).toList());
    }

    private static LessonLearningScope scope(Long lessonId, Long topicId, int order) {
        return LessonLearningScope.builder().lessonId(lessonId).goalId(3L).topicId(topicId).required(true)
                .scopeOrder(order).reviewStatus("APPROVED").build();
    }

    private static Lesson published(Long id) {
        return Lesson.builder().id(id).status("PUBLISHED").build();
    }

    private static com.jlas.learning.entity.LearningTopic topic(Long id, Long parentId) {
        return com.jlas.learning.entity.LearningTopic.builder().id(id).goalId(3L).parentTopicId(parentId)
                .topicCode("T" + id).titleVi("Topic " + id).topicOrder(id.intValue()).build();
    }

    @Test
    void surveyPreferencesKeepTheFullApprovedSituationSetInScopeOrder() {
        UserGoalSurveySubmission submission = UserGoalSurveySubmission.builder().id(50L).userId(7L).goalId(3L)
                .learningMode("BALANCED").status("SUBMITTED").version(1).build();
        when(submissions.findByIdAndUserId(50L, 7L)).thenReturn(Optional.of(submission));
        when(scopes.findByGoalIdAndReviewStatusOrderByScopeOrderAsc(3L, "APPROVED")).thenReturn(List.of(
                LessonLearningScope.builder().lessonId(1L).goalId(3L).topicId(999L).required(true)
                        .scopeOrder(1).learningMode("BALANCED").reviewStatus("APPROVED").build(),
                LessonLearningScope.builder().lessonId(2L).goalId(3L).topicId(100L).required(false)
                        .scopeOrder(2).learningMode("BALANCED").reviewStatus("APPROVED").build(),
                LessonLearningScope.builder().lessonId(3L).goalId(3L).topicId(200L).required(false)
                        .scopeOrder(3).learningMode("BALANCED").reviewStatus("APPROVED").build()));
        Lesson foundation = Lesson.builder().id(1L).status("PUBLISHED").build();
        Lesson selected = Lesson.builder().id(2L).status("PUBLISHED").build();
        Lesson anotherSituation = Lesson.builder().id(3L).status("PUBLISHED").build();
        when(lessons.findAllById(List.of(1L, 2L, 3L))).thenReturn(List.of(selected, anotherSituation, foundation));

        var result = service.select(7L, 50L);

        assertEquals(List.of(1L, 2L, 3L), result.lessons().stream().map(Lesson::getId).toList());
        assertEquals("BALANCED", result.learningMode());
    }

    @Test
    void unapprovedOrMissingPublishedContentFailsClosed() {
        UserGoalSurveySubmission submission = UserGoalSurveySubmission.builder().id(51L).userId(7L).goalId(4L)
                .learningMode("COMMUNICATION_FIRST").status("SUBMITTED").version(1).build();
        when(submissions.findByIdAndUserId(51L, 7L)).thenReturn(Optional.of(submission));
        when(scopes.findByGoalIdAndReviewStatusOrderByScopeOrderAsc(4L, "APPROVED")).thenReturn(List.of());

        assertThrows(ApiException.class, () -> service.select(7L, 51L));
    }
}
