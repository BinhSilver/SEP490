package com.jlas.learning.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Controller trang đề xuất lộ trình học cho người chưa cần làm Smart Test. */
@Controller
public class LearningPageController {

    @GetMapping("/learning-path")
    public String learningPath(Model model) {
        model.addAttribute("layoutMode", "app");
        return "learning-path";
    }
}
