package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jlas.learning.controller.LearningPathPageController;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;

class LearningPathPageControllerTest {

    @Test
    void learningPathUsesTheAppShell() {
        var controller = new LearningPathPageController();
        var model = new ConcurrentModel();

        var view = controller.learningPath(model);

        assertEquals("learning/path", view);
        assertEquals("app", model.getAttribute("layoutMode"));
    }

    /** [JLAS-W0193] Task 8 — the completion popup's last-package "Xem tổng kết chặng" action routes here. */
    @Test
    void interactivePathSummaryRouteServesThePlaceholderPage() {
        var controller = new LearningPathPageController();
        var model = new ConcurrentModel();

        var view = controller.interactiveLearningPathSummary(model);

        assertEquals("learning/interactive-path-summary", view);
        assertEquals("app", model.getAttribute("layoutMode"));
    }

    /** [JLAS-W0193] RC Task 5 — the roadmap links an unlocked Reading activity here; the route only picks the
     * template/static loading copy, the page itself renders from the API's own {@code activityType}. */
    @Test
    void interactiveReadingRouteServesTheComprehensionPlayer() {
        var controller = new LearningPathPageController();
        var model = new ConcurrentModel();

        var view = controller.interactiveReading(model);

        assertEquals("learning/interactive-comprehension-study", view);
        assertEquals("app", model.getAttribute("layoutMode"));
        assertEquals("reading", model.getAttribute("comprehensionKind"));
    }

    @Test
    void interactiveListeningRouteServesTheComprehensionPlayer() {
        var controller = new LearningPathPageController();
        var model = new ConcurrentModel();

        var view = controller.interactiveListening(model);

        assertEquals("learning/interactive-comprehension-study", view);
        assertEquals("app", model.getAttribute("layoutMode"));
        assertEquals("listening", model.getAttribute("comprehensionKind"));
    }
}
