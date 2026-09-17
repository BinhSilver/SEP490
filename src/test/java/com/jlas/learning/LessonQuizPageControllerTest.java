package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jlas.learning.controller.LessonQuizPageController;
import com.jlas.learning.dto.LessonDetailResponse;
import com.jlas.learning.service.LessonDetailService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;

class LessonQuizPageControllerTest {

    private static final LessonDetailResponse DETAIL = new LessonDetailResponse(
            1L, "Trợ từ cơ bản", "Mô tả", "GRAMMAR", "N4", 10, "Trợ từ", "Minna", "Bài 1");

    @Test
    void publishedLessonRendersTheQuizView() {
        LessonDetailService service = id -> Optional.of(DETAIL);
        var model = new ConcurrentModel();

        var view = new LessonQuizPageController(service).quiz(1L, model);

        assertEquals("learning/lesson-quiz", view);
        assertEquals(DETAIL, model.getAttribute("lesson"));
        assertEquals("Trợ từ cơ bản - Quiz - JLAS", model.getAttribute("pageTitle"));
        assertEquals("app", model.getAttribute("layoutMode"));
    }

    @Test
    void missingLessonRedirectsBackToThePath() {
        LessonDetailService service = id -> Optional.empty();
        var model = new ConcurrentModel();

        var view = new LessonQuizPageController(service).quiz(999L, model);

        assertEquals("redirect:/learning/path", view);
    }
}
