package com.jlas.learning.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class GoalSurveyPageController {
    @GetMapping("/learning/goals/{goalCode}/survey")
    public String survey(@PathVariable String goalCode, Model model) {
        model.addAttribute("layoutMode", "app");
        model.addAttribute("goalCode", goalCode);
        return "learning/goal-survey";
    }
}
