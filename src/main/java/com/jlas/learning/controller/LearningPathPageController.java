package com.jlas.learning.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * [JLAS-W0108] Owner 07/11: the old learning-path pages are gone. {@code /learning/path} is kept as an address (team
 * pages and bookmarks link it): a Smart Test hand-off goes to the JLPT path's confirmation, anything else to
 * {@code /learning}, which opens the learner's path. The notebook page stays (the Kanji map opens it).
 */
@Controller
public class LearningPathPageController {

    @GetMapping("/learning/notebook")
    public String weaknessNotebook(Model model) {
        model.addAttribute("layoutMode", "app");
        return "learning/weakness-notebook";
    }

    @GetMapping({"/learning/path", "/learning/path/"})
    public String learningPath(@RequestParam(required = false) Long assessmentAttemptId) {
        return assessmentAttemptId == null ? "redirect:/learning"
                : "redirect:/learning/jlpt/confirm?source=SMART_TEST&assessmentAttemptId=" + assessmentAttemptId;
    }
}
