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
}
