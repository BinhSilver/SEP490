package com.jlas.learning.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** [JLAS-W0108] Trang chúc mừng hoàn thành một chặng (và mời nhận chứng chỉ nếu là chặng cuối). */
@Controller
public class LearningStageCompletePageController {

    @GetMapping("/learning/stages/{stageId}/complete")
    public String stageComplete(@PathVariable Long stageId, Model model) {
        model.addAttribute("stageId", stageId);
        return "learning/stage-complete";
    }
}
