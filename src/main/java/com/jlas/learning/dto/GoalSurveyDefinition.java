package com.jlas.learning.dto;

import java.util.List;

public record GoalSurveyDefinition(
        String goalCode,
        String displayNameVi,
        String entryPolicy,
        boolean requiresAssessment,
        List<Question> questions
) {
    public record Question(String questionCode, int order, String textVi, String selectionType,
                           boolean required, List<Option> options) {}

    public record Option(String optionCode, int order, String labelVi, String valueCode,
                         String topicCode, String competencyCode, String learningMode) {}
}
