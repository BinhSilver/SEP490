package com.jlas.learning.controller;

import com.jlas.learning.dto.LessonDetailResponse;
import com.jlas.learning.service.LessonDetailService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Serves the authenticated catalog-lesson detail page opened from a learning path. */
@Controller
@RequiredArgsConstructor
public class LessonPageController {
    private final LessonDetailService lessonDetailService;

    @GetMapping("/learning/lesson/{id}")
    public String lesson(@PathVariable("id") Long id, Model model) {
        LessonDetailResponse detail = lessonDetailService.findPublished(id).orElse(null);
        if (detail == null) {
            return "redirect:/learning/path";
        }
        model.addAttribute("lesson", detail);
        model.addAttribute("pageTitle", detail.title() + " - JLAS");
        model.addAttribute("layoutMode", "app");
        return "learning/lesson-detail";
    }
}
