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
}
