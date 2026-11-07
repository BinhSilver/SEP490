package com.jlas.learning.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * [JLAS-W0108] Owner 07/11: only the new learning paths remain (JLPT path, communication path). The old learning-path
 * pages are gone; their addresses still work for old links and bookmarks and send the learner to {@code /learning},
 * which opens the right path for the survey goal (or the survey when there is none).
 */
@Controller
public class LegacyLearningRedirectController {

    private static final String LEARNING = "redirect:/learning";

    @GetMapping({"/learning/path/v2", "/learning/path/v2/summary",
            "/learning/path/gate-quiz", "/learning/path/flashcard-quiz",
            "/learning/v2/vocabulary", "/learning/v2/kanji", "/learning/v2/foundation", "/learning/v2/grammar",
            "/learning/v2/dialogue", "/learning/v2/reading", "/learning/v2/listening", "/learning/v2/certificate",
            "/learning/warm-up/flashcard", "/learning/stages/{stageId}/complete", "/learning/stages/{stageId}/final-quiz",
            "/learning/certificate/{pathId}", "/learning/goals/{goalCode}/survey"})
    public String toLearning() {
        return LEARNING;
    }
}
