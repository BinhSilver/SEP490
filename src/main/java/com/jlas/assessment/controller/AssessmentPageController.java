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

    @GetMapping("/jlpt")
    public String jlptLibrary(Model model) {
        model.addAttribute("layoutMode", "app");
        return "jlpt-library";
    }

    @GetMapping("/jlpt/exam")
    public String jlptExam(Model model) {
        model.addAttribute("layoutMode", "app");
        return "jlpt-exam";
    }

    @GetMapping("/jlpt/practice")
    public String jlptPractice(Model model) {
        model.addAttribute("layoutMode", "app");
        return "jlpt-practice";
    }
}
