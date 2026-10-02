package com.jlas.learning.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalEntryPolicyTest {

    @Test
    void jlptUsesPlacementAssessmentWhileBrseUsesJapaneseFoundationAssessment() {
        assertEquals(GoalEntryPolicy.AssessmentRequirement.JLPT_PLACEMENT,
                GoalEntryPolicy.forGoal("JLPT").assessmentRequirement());
        assertEquals(GoalEntryPolicy.AssessmentRequirement.BRSE_FOUNDATION,
                GoalEntryPolicy.forGoal("BA").assessmentRequirement());
    }

    @Test
    void travelAndDailyLifeAreSurveyOnly() {
        for (String goal : java.util.List.of("TRAVEL", "DAILY_LIFE")) {
            GoalEntryPolicy policy = GoalEntryPolicy.forGoal(goal);
            assertTrue(policy.surveyRequired());
            assertEquals(GoalEntryPolicy.AssessmentRequirement.NONE, policy.assessmentRequirement());
            assertFalse(policy.competencySelfReportIsEvidence());
        }
    }

    @Test
    void brseSurveyExpressesPreferenceAndNeverProvesCompetency() {
        GoalEntryPolicy policy = GoalEntryPolicy.forGoal("BA");
        assertTrue(policy.surveyRequired());
        assertFalse(policy.competencySelfReportIsEvidence());
        assertEquals(java.util.Set.of("FOUNDATION_FIRST", "COMMUNICATION_FIRST", "BALANCED"),
                policy.allowedLearningModes());
    }

    @Test
    void unsupportedGoalFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> GoalEntryPolicy.forGoal("BRSE"));
    }
}
