package com.jlas.learning.controller;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.repository.LessonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Bridges learning-path lesson links to the digitized-textbook lesson viewer.
 * The path schedules lessons by id only, so resolve the owning textbook here and hand off to the
 * existing content viewer ({@code /textbooks/{textbookId}/lessons/{lessonId}}) rather than
 * duplicating its rendering.
 */
@Controller
@RequiredArgsConstructor
public class LearningLessonPageController {
    private final LessonRepository lessons;

    @GetMapping("/learning/lesson/{lessonId}")
    public String lesson(@PathVariable Long lessonId) {
        Lesson lesson = lessons.findWithUnitById(lessonId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Lesson not found."));
        Long textbookId = lesson.getUnit().getTextbook().getId();
        return "redirect:/textbooks/" + textbookId + "/lessons/" + lessonId;
    }
}
