package com.jlas.learning.service;

import com.jlas.common.exception.ApiException;
import com.jlas.learning.dto.GoalSurveyAnswerRequest;
import com.jlas.learning.dto.GoalSurveyDefinition;
import com.jlas.learning.dto.GoalSurveySubmissionRequest;
import com.jlas.learning.entity.LearningGoal;
import com.jlas.learning.entity.LearningSurveyOption;
import com.jlas.learning.entity.LearningSurveyQuestion;
import com.jlas.learning.entity.UserGoalSurveySubmission;
import com.jlas.learning.repository.LearningGoalRepository;
import com.jlas.learning.repository.LearningSurveyOptionRepository;
import com.jlas.learning.repository.LearningSurveyQuestionRepository;
import com.jlas.learning.repository.UserGoalSurveySelectionRepository;
import com.jlas.learning.repository.UserGoalSurveySubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoalSurveyServiceTest {
    @Mock LearningGoalRepository goals;
    @Mock LearningSurveyQuestionRepository questions;
    @Mock LearningSurveyOptionRepository options;
    @Mock UserGoalSurveySubmissionRepository submissions;
    @Mock UserGoalSurveySelectionRepository selections;

    GoalSurveyService service;

    @BeforeEach
    void setUp() {
        service = new GoalSurveyService(goals, questions, options, submissions, selections);
    }

    @Test
    void definitionUsesOnlyApprovedActiveQuestionsAndOptions() {
        LearningGoal goal = LearningGoal.builder().id(2L).goalCode("TRAVEL")
                .displayNameVi("Du lịch").entryPolicy("SURVEY_ONLY")
                .requiresAssessment(false).active(true).reviewStatus("APPROVED").build();
        LearningSurveyQuestion question = LearningSurveyQuestion.builder().id(10L).goalId(2L)
                .surveyQuestionCode("SV-TRAVEL-TOPICS").questionOrder(1)
                .questionVi("Bạn muốn học tình huống nào?").selectionType("MULTIPLE")
                .required(true).active(true).reviewStatus("APPROVED").build();
        LearningSurveyOption option = LearningSurveyOption.builder().id(20L).questionId(10L)
                .surveyOptionCode("SV-TRAVEL-AIRPORT").optionOrder(1)
                .labelVi("Sân bay").valueCode("TRAVEL-AIRPORT")
                .reviewStatus("APPROVED").build();
        when(goals.findByGoalCodeAndActiveTrue("TRAVEL")).thenReturn(Optional.of(goal));
        when(questions.findByGoalIdAndActiveTrueAndReviewStatusOrderByQuestionOrder(2L, "APPROVED"))
                .thenReturn(List.of(question));
        when(options.findByQuestionIdInAndReviewStatusOrderByQuestionIdAscOptionOrderAsc(List.of(10L), "APPROVED"))
                .thenReturn(List.of(option));

        GoalSurveyDefinition result = service.definition("travel");

        assertEquals("TRAVEL", result.goalCode());
        assertFalse(result.requiresAssessment());
        assertEquals("SV-TRAVEL-AIRPORT", result.questions().getFirst().options().getFirst().optionCode());
    }

    @Test
    void submissionPersistsPreferenceWithoutClaimingCompetencyEvidence() {
        LearningGoal goal = LearningGoal.builder().id(3L).goalCode("BA")
                .displayNameVi("BrSE").entryPolicy("FOUNDATION_AND_SURVEY")
                .requiresAssessment(true).active(true).reviewStatus("APPROVED").build();
        LearningSurveyQuestion question = LearningSurveyQuestion.builder().id(11L).goalId(3L)
                .surveyQuestionCode("SV-BA-COMPETENCY").questionOrder(1).questionVi("Chọn kỹ năng")
                .selectionType("MULTIPLE").required(true).active(true).reviewStatus("APPROVED").build();
        LearningSurveyOption option = LearningSurveyOption.builder().id(21L).questionId(11L)
                .surveyOptionCode("SV-BA-MEETING").optionOrder(1).labelVi("Meeting")
                .valueCode("MEETING").reviewStatus("APPROVED").build();
        when(goals.findByGoalCodeAndActiveTrue("BA")).thenReturn(Optional.of(goal));
        when(questions.findByGoalIdAndActiveTrueAndReviewStatusOrderByQuestionOrder(3L, "APPROVED"))
                .thenReturn(List.of(question));
        when(options.findByQuestionIdInAndReviewStatusOrderByQuestionIdAscOptionOrderAsc(List.of(11L), "APPROVED"))
                .thenReturn(List.of(option));
        when(submissions.findMaxVersion(99L, 3L)).thenReturn(0);
        when(submissions.save(any())).thenAnswer(invocation -> {
            UserGoalSurveySubmission saved = invocation.getArgument(0);
            saved.setId(100L);
            return saved;
        });

        var result = service.submit(99L, "BA", new GoalSurveySubmissionRequest(
                "BALANCED", List.of(new GoalSurveyAnswerRequest("SV-BA-COMPETENCY", List.of("SV-BA-MEETING")))));

        assertEquals(100L, result.submissionId());
        assertEquals("BRSE_FOUNDATION", result.assessmentRequirement());
        assertFalse(result.competencyEvidence());
    }

    @Test
    void optionFromAnotherQuestionIsRejected() {
        LearningGoal goal = LearningGoal.builder().id(2L).goalCode("TRAVEL")
                .displayNameVi("Du lịch").entryPolicy("SURVEY_ONLY")
                .requiresAssessment(false).active(true).reviewStatus("APPROVED").build();
        LearningSurveyQuestion question = LearningSurveyQuestion.builder().id(10L).goalId(2L)
                .surveyQuestionCode("SV-TRAVEL-TOPICS").questionOrder(1).questionVi("Chọn")
                .selectionType("MULTIPLE").required(true).active(true).reviewStatus("APPROVED").build();
        LearningSurveyOption foreign = LearningSurveyOption.builder().id(30L).questionId(999L)
                .surveyOptionCode("FOREIGN").optionOrder(1).labelVi("Sai").valueCode("FOREIGN")
                .reviewStatus("APPROVED").build();
        when(goals.findByGoalCodeAndActiveTrue("TRAVEL")).thenReturn(Optional.of(goal));
        when(questions.findByGoalIdAndActiveTrueAndReviewStatusOrderByQuestionOrder(2L, "APPROVED"))
                .thenReturn(List.of(question));
        when(options.findByQuestionIdInAndReviewStatusOrderByQuestionIdAscOptionOrderAsc(List.of(10L), "APPROVED"))
                .thenReturn(List.of(foreign));

        assertThrows(ApiException.class, () -> service.submit(99L, "TRAVEL",
                new GoalSurveySubmissionRequest("BALANCED", List.of(
                        new GoalSurveyAnswerRequest("SV-TRAVEL-TOPICS", List.of("FOREIGN"))))));
    }
}
