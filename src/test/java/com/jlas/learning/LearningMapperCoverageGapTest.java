package com.jlas.learning;

import com.jlas.auth.entity.User;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.mapper.LearningMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningMapperCoverageGapTest {
    @Test void reviewOnlyTargetsAreNotExposedAsInternalCoverageWarnings() {
        var path = UserLearningPath.builder().id(1L).user(User.builder().id(7L).build())
                .goal("JLPT").coverageGapsJson("[{\"tagCode\":\"VOCABULARY:学生\","
                        + "\"reasonCode\":\"SMART_TEST_REVIEW_ONLY_BELOW_THRESHOLD\"}]")
                .build();

        var response = new LearningMapper().toResponse(path, List.of(), false);

        assertTrue(response.coverageGaps().isEmpty());
    }
}
