package com.jlas.learning.controller;

import com.jlas.learning.dto.LessonDetailResponse;
import com.jlas.learning.service.LessonDetailService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Serves the authenticated lesson-quiz page opened from a lesson detail page. */
@Controller
@RequiredArgsConstructor
public class LessonQuizPageController {
    private static final String ONYOMI = "ONYOMI";

    private final LessonDetailService lessonDetailService;

    @GetMapping("/learning/lesson/{id}/quiz")
    public String quiz(@PathVariable("id") Long id, Model model) {
        LessonDetailResponse detail = lessonDetailService.findPublished(id).orElse(null);
        if (detail == null) {
            return "redirect:/learning/path";
        }
        // the Onyomi lesson page runs its own practice quiz
        if (ONYOMI.equals(detail.skill())) {
            return "redirect:/learning/onyomi/lessons/" + detail.id() + "#s-quiz";
        }
        model.addAttribute("lesson", detail);
        model.addAttribute("pageTitle", detail.title() + " - Quiz - JLAS");
        model.addAttribute("layoutMode", "app");
        return "learning/lesson-quiz";
    }
}
