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
}
