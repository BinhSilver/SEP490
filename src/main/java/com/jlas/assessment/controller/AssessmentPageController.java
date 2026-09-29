package com.jlas.assessment.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AssessmentPageController {

    @GetMapping("/smart-test")
    public String smartTest(Model model) {
        model.addAttribute("layoutMode", "app");
        return "smart-test";
    }

    @GetMapping("/survey")
    public String smartTestSurvey(Model model) {
        model.addAttribute("layoutMode", "app");
        return "smart-test-survey";
    }
}
