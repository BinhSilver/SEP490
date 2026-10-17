package com.jlas.learning;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.controller.InteractiveComprehensionController;
import com.jlas.learning.controller.InteractiveFoundationController;
import com.jlas.learning.controller.InteractiveGrammarController;
import com.jlas.learning.controller.InteractiveKanjiController;
import com.jlas.learning.service.InteractiveComprehensionService;
import com.jlas.learning.service.InteractiveFoundationService;
import com.jlas.learning.service.InteractiveGrammarService;
import com.jlas.learning.service.InteractiveKanjiService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Each study skill exposes the same owned, read-only quiz-history contract as Vocabulary. */
class InteractiveAttemptHistoryContractTest {
    @Test
    void everySkillProvidesASeparateQuizResultHistoryRoute() throws Exception {
        Class<?>[] controllers = { InteractiveFoundationController.class, InteractiveKanjiController.class,
                InteractiveGrammarController.class, InteractiveComprehensionController.class };
        Class<?>[] services = { InteractiveFoundationService.class, InteractiveKanjiService.class,
                InteractiveGrammarService.class, InteractiveComprehensionService.class };
        for (int i = 0; i < controllers.length; i++) {
            var route = controllers[i].getDeclaredMethod("attemptHistory", Long.class, UserPrincipal.class);
            GetMapping mapping = route.getAnnotation(GetMapping.class);
            assertNotNull(mapping, controllers[i].getSimpleName() + " must have a GET route");
            assertArrayEquals(new String[] { "/attempt-history" }, mapping.value());
            assertNotNull(services[i].getMethod("quizAttemptHistory", Long.class, Long.class));
        }
    }
}
