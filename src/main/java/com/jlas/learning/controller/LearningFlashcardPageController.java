package com.jlas.learning.controller;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.service.WarmUpFlashcardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * [JLAS-W0108] Trang flashcard warm-up: sinh (get-or-create) bộ gộp Kanji + Từ vựng đã sai
 * rồi render màn ôn tập flashcard riêng (tái dùng UI flip-card của module flashcard),
 * thay vì dẫn về trang giáo trình chung.
 */
@Controller
@RequiredArgsConstructor
public class LearningFlashcardPageController {
    private final WarmUpFlashcardService warmUpFlashcardService;

    @GetMapping("/learning/warm-up/flashcard")
    public String warmUpFlashcard(@AuthenticationPrincipal UserPrincipal principal, Model model) {
        Long setId = warmUpFlashcardService.ensureSet(principal.userId());
        if (setId == null) {
            return "redirect:/learning/path";
        }
        model.addAttribute("setId", setId);
        return "learning/warm-up-flashcard";
    }
}
