package com.jlas.learning.controller;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.repository.LessonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * [JLAS-W0108] Trang học riêng cho một bài trong lộ trình. Không dẫn thẳng về viewer giáo trình
 * ({@code /textbooks/...}) mà render một trang học riêng: kéo nội dung bài (từ vựng, ngữ pháp, ví dụ,
 * hội thoại) qua {@code /api/dictionary/lessons/{id}/content} và thêm nút "Hoàn thành bài" ghi streak/credit.
 */
@Controller
@RequiredArgsConstructor
public class LearningLessonPageController {
    private final LessonRepository lessons;

    @GetMapping("/learning/lesson/{lessonId}")
    public String lesson(@PathVariable Long lessonId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Long item, Model model) {
        Lesson lesson = lessons.findWithUnitById(lessonId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Lesson not found."));
        model.addAttribute("lessonId", lessonId);
        model.addAttribute("itemId", item);
        model.addAttribute("textbookId", lesson.getUnit().getTextbook().getId());
        return "learning/path-lesson";
    }
}
