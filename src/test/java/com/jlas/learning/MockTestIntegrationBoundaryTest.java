package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.*;

import com.jlas.learning.controller.InteractiveLearningPathController;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

/** [JLAS-W0108] Contract guards for the Mock Test hand-off: the Learning side may only know the port, never the
 * Assessment implementation, and the browser only ever posts an attempt id to one documented route. */
class MockTestIntegrationBoundaryTest {
    private static final List<String> LEARNING_SIDE = List.of(
            "src/main/java/com/jlas/learning/service/JlptQualificationPort.java",
            "src/main/java/com/jlas/learning/service/LearningPathCompletionService.java",
            "src/main/java/com/jlas/learning/service/impl/LearningPathCompletionServiceImpl.java",
            "src/main/java/com/jlas/learning/service/LearningFinalEligibilityService.java",
            "src/main/java/com/jlas/learning/service/impl/LearningFinalEligibilityServiceImpl.java",
            "src/main/java/com/jlas/learning/controller/InteractiveLearningPathController.java");

    @Test void learningSideNeverImportsAssessmentInternals() throws IOException {
        for (String file : LEARNING_SIDE) {
            String source = Files.readString(Path.of(file), StandardCharsets.UTF_8);
            assertFalse(source.contains("import com.jlas.assessment"), file + " must only depend on the port");
        }
    }

    @Test void theBrowserCompletesAPathThroughOneRouteThatTakesOnlyAnAttemptId() throws Exception {
        var method = InteractiveLearningPathController.class.getMethod("completeFromMock",
                com.jlas.learning.dto.CompleteFromMockRequest.class, com.jlas.auth.security.UserPrincipal.class);
        assertArrayEquals(new String[] {"/paths/current/complete"}, method.getAnnotation(PostMapping.class).value());
        var fields = com.jlas.learning.dto.CompleteFromMockRequest.class.getRecordComponents();
        assertEquals(1, fields.length);
        assertEquals("attemptId", fields[0].getName());
    }
}
