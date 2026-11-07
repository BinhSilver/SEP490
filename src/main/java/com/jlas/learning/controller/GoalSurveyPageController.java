package com.jlas.learning.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.util.UriUtils;

@Controller
public class GoalSurveyPageController {

    /** [JLAS-W0108] With the communication path on, BA/TRAVEL/DAILY_LIFE use its survey and circle map. */
    @Value("${learning.comm-path.enabled:false}")
    private boolean commPathEnabled;

    @GetMapping("/learning/goals/{goalCode}/survey")
    public String survey(@PathVariable String goalCode, Model model) {
        if (commPathEnabled && !"JLPT".equalsIgnoreCase(goalCode)) {
            return "redirect:/learning/comm/survey?goal="
                    + UriUtils.encodeQueryParam(goalCode, java.nio.charset.StandardCharsets.UTF_8);
        }
        model.addAttribute("layoutMode", "app");
        model.addAttribute("goalCode", goalCode);
        return "learning/goal-survey";
    }
}
