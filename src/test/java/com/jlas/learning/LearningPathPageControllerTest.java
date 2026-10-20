package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jlas.learning.controller.LearningPathPageController;
import com.jlas.learning.config.InteractiveLearningV2Properties;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;

class LearningPathPageControllerTest {

    @Test
    void learningPathUsesTheAppShell() {
        var controller = controller(false);
        var model = new ConcurrentModel();

        var view = controller.learningPath(model, 91L, null, null);

        assertEquals("learning/path", view);
        assertEquals("app", model.getAttribute("layoutMode"));
    }

    @Test
    void learningPathRedirectsToInteractiveExperienceAndKeepsAssessmentAttempt() {
        var controller = controller(true);

        var view = controller.learningPath(new ConcurrentModel(), 91L, null, null);

        assertEquals("redirect:/learning/path/v2?assessmentAttemptId=91", view);
    }

    /** A confirmed review start below a higher Smart Test result survives the V2 redirect; anything else is dropped. */
    @Test
    void learningPathRedirectKeepsOnlyASupportedReviewStartLevel() {
        var controller = controller(true);

        assertEquals("redirect:/learning/path/v2?assessmentAttemptId=91&reviewStartLevel=N4",
                controller.learningPath(new ConcurrentModel(), 91L, "N4", null));
        assertEquals("redirect:/learning/path/v2?assessmentAttemptId=91",
                controller.learningPath(new ConcurrentModel(), 91L, "N4&x=//evil", null));
        assertEquals("redirect:/learning/path/v2?assessmentAttemptId=91",
                controller.learningPath(new ConcurrentModel(), 91L, "N1", null));
        assertEquals("redirect:/learning/path/v2", controller.learningPath(new ConcurrentModel(), null, "N4", null));
    }

    /** Owner 2026-10-19: the learner's answer to a lower Smart Test result survives the V2 redirect; junk is dropped. */
    @Test
    void learningPathRedirectKeepsOnlyASupportedLowerResultChoice() {
        var controller = controller(true);

        assertEquals("redirect:/learning/path/v2?assessmentAttemptId=91&discrepancyChoice=ACCEPT_RECOMMENDATION",
                controller.learningPath(new ConcurrentModel(), 91L, null, "ACCEPT_RECOMMENDATION"));
        assertEquals("redirect:/learning/path/v2?assessmentAttemptId=91&discrepancyChoice=DECLINE_RECOMMENDATION",
                controller.learningPath(new ConcurrentModel(), 91L, null, "DECLINE_RECOMMENDATION"));
        assertEquals("redirect:/learning/path/v2?assessmentAttemptId=91",
                controller.learningPath(new ConcurrentModel(), 91L, null, "ACCEPT&next=//evil"));
    }

    /** [JLAS-W0193] Task 8 — the completion popup's last-package "Xem tổng kết chặng" action routes here. */
    @Test
    void interactivePathSummaryRouteServesThePlaceholderPage() {
        var controller = controller(true);
        var model = new ConcurrentModel();

        var view = controller.interactiveLearningPathSummary(model);

        assertEquals("learning/interactive-path-summary", view);
        assertEquals("app", model.getAttribute("layoutMode"));
    }

    /** [JLAS-W0193] RC Task 5 — the roadmap links an unlocked Reading activity here; the route only picks the
     * template/static loading copy, the page itself renders from the API's own {@code activityType}. */
    @Test
    void interactiveReadingRouteServesTheComprehensionPlayer() {
        var controller = controller(true);
        var model = new ConcurrentModel();

        var view = controller.interactiveReading(model);

        assertEquals("learning/interactive-comprehension-study", view);
        assertEquals("app", model.getAttribute("layoutMode"));
        assertEquals("reading", model.getAttribute("comprehensionKind"));
    }

    @Test
    void interactiveListeningRouteServesTheComprehensionPlayer() {
        var controller = controller(true);
        var model = new ConcurrentModel();

        var view = controller.interactiveListening(model);

        assertEquals("learning/interactive-comprehension-study", view);
        assertEquals("app", model.getAttribute("layoutMode"));
        assertEquals("listening", model.getAttribute("comprehensionKind"));
    }

    private LearningPathPageController controller(boolean enabled) {
        var properties = new InteractiveLearningV2Properties();
        properties.setEnabled(enabled);
        return new LearningPathPageController(properties);
    }
}
