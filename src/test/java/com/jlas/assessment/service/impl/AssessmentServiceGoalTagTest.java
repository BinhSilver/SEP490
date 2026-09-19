package com.jlas.assessment.service.impl;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AssessmentServiceGoalTagTest {
    @Test
    void onlyFourGoalsHavePriorityTags() {
        assertThat(AssessmentServiceImpl.preferredTags("JLPT")).containsExactly("JLPT");
        assertThat(AssessmentServiceImpl.preferredTags("BA")).containsExactly("BA");
        assertThat(AssessmentServiceImpl.preferredTags("TRAVEL")).containsExactly("TRAVEL", "DAILY_LIFE");
        assertThat(AssessmentServiceImpl.preferredTags("DAILY_LIFE")).containsExactly("DAILY_LIFE");
        assertThat(AssessmentServiceImpl.preferredTags("GENERAL")).isEmpty();
        assertThat(AssessmentServiceImpl.preferredTags("WORK")).isEmpty();
    }
}
