package com.jlas.kaiwa.controller;

import com.jlas.kaiwa.service.SampleConversationService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class SampleConversationPageController {
    private final SampleConversationService samples;

    public SampleConversationPageController(SampleConversationService samples) {
        this.samples = samples;
    }

    @GetMapping("/scenario/{id}/sample")
    public String sample(@PathVariable Long id, @RequestParam(required = false) String stage, Model model) {
        model.addAttribute("sample", samples.page(id, stage));
        return "aicall/sample-conversation";
    }
}
