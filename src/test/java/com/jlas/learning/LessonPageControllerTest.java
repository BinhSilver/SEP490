package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jlas.learning.controller.LessonPageController;
import com.jlas.learning.dto.LessonDetailResponse;
import com.jlas.learning.service.LessonDetailService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;

class LessonPageControllerTest {

    private static final LessonDetailResponse DETAIL = new LessonDetailResponse(
            1L, "Trợ từ cơ bản", "Mô tả", "GRAMMAR", "N4", 10, "Trợ từ", "Minna", "Bài 1");

    @Test
    void publishedLessonRendersTheDetailView() {
        LessonDetailService service = id -> Optional.of(DETAIL);
        var model = new ConcurrentModel();

        var view = new LessonPageController(service).lesson(1L, model);

        assertEquals("learning/lesson-detail", view);
        assertEquals(DETAIL, model.getAttribute("lesson"));
        assertEquals("Trợ từ cơ bản - JLAS", model.getAttribute("pageTitle"));
        assertEquals("app", model.getAttribute("layoutMode"));
    }

    @Test
    void missingLessonRedirectsBackToThePath() {
        LessonDetailService service = id -> Optional.empty();
        var model = new ConcurrentModel();

        var view = new LessonPageController(service).lesson(999L, model);

        assertEquals("redirect:/learning/path", view);
    }
}
