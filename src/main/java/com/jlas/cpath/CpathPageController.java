package com.jlas.cpath;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** [JLAS-W0108] Pages of the phase-2 communication path (templates/cpath/*), behind {@code learning.comm-path.enabled}. */
@Controller
@ConditionalOnProperty(name = "learning.comm-path.enabled", havingValue = "true")
public class CpathPageController {

    @GetMapping("/learning/comm/survey")
    public String survey() {
        return "cpath/survey";
    }

    @GetMapping("/learning/comm")
    public String path() {
        return "cpath/path";
    }

    @GetMapping("/learning/comm/situation/{id}")
    public String situation(@PathVariable String id, Model model) {
        model.addAttribute("situationId", id);
        return "cpath/situation";
    }

    @GetMapping("/learning/comm/day")
    public String day() {
        return "cpath/day";
    }
}
