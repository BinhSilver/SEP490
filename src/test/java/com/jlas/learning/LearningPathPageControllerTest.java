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

        var view = controller.learningPath(model, 91L);

        assertEquals("learning/path", view);
        assertEquals("app", model.getAttribute("layoutMode"));
    }

    @Test
    void learningPathRedirectsToInteractiveExperienceAndKeepsAssessmentAttempt() {
        var controller = controller(true);

        var view = controller.learningPath(new ConcurrentModel(), 91L);

        assertEquals("redirect:/learning/path/v2?assessmentAttemptId=91", view);
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
