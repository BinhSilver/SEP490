package com.jlas.learning.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the authenticated personalized learning-path page. */
@Controller
public class LearningPathPageController {

    @GetMapping("/learning/path")
    public String learningPath(Model model) {
        model.addAttribute("layoutMode", "app");
        return "learning/path";
    }

    @GetMapping("/learning/path/v2")
    public String interactiveLearningPath(Model model) {
        model.addAttribute("layoutMode", "app");
        return "learning/interactive-path";
    }

    /** [JLAS-W0193] Task 8 — the completion popup's "Xem tổng kết chặng" (last package) routes here.
     * Placeholder only: Task 10 builds the real first/latest/best + readiness summary content. */
    @GetMapping("/learning/path/v2/summary")
    public String interactiveLearningPathSummary(Model model) {
        model.addAttribute("layoutMode", "app");
        return "learning/interactive-path-summary";
    }

    @GetMapping("/learning/v2/vocabulary")
    public String interactiveVocabulary(Model model) {
        model.addAttribute("layoutMode", "app");
        return "learning/interactive-study";
    }

    @GetMapping("/learning/v2/kanji")
    public String interactiveKanji(Model model) {
        model.addAttribute("layoutMode", "app");
        return "learning/interactive-kanji-study";
    }

    @GetMapping("/learning/v2/grammar")
    public String interactiveGrammar(Model model) {
        model.addAttribute("layoutMode", "app");
        return "learning/interactive-grammar-study";
    }

    @GetMapping("/learning/v2/dialogue")
    public String interactiveDialogue(Model model) {
        model.addAttribute("layoutMode", "app");
        return "learning/interactive-dialogue-study";
    }

    /** [JLAS-W0193] RC Task 5 — Reading and Listening share one template/JS/CSS bundle (mirrors the shape of
     * Vocabulary/Kanji sharing {@code interactive-study.js}); {@code comprehensionKind} only drives the static
     * pre-JS loading copy below. The page itself never trusts this attribute for which skill to render — the
     * script always renders from {@code study.activityType} as returned by the API, never from the URL. */
    @GetMapping("/learning/v2/reading")
    public String interactiveReading(Model model) {
        model.addAttribute("layoutMode", "app");
        model.addAttribute("comprehensionKind", "reading");
        return "learning/interactive-comprehension-study";
    }

    @GetMapping("/learning/v2/listening")
    public String interactiveListening(Model model) {
        model.addAttribute("layoutMode", "app");
        model.addAttribute("comprehensionKind", "listening");
        return "learning/interactive-comprehension-study";
    }
}
