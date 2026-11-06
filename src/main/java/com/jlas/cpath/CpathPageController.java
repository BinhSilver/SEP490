package com.jlas.cpath;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/** [JLAS-W0108] Pages of the phase-2 communication path (templates/cpath/*), behind {@code learning.comm-path.enabled}. */
@Controller
@ConditionalOnProperty(name = "learning.comm-path.enabled", havingValue = "true")
public class CpathPageController {

    @GetMapping("/learning/comm/survey")
    public String survey() {
        return "cpath/survey";
    }

    @GetMapping({"/learning/comm", "/learning/comm/path"})
    public String path() {
        return "cpath/path";
    }

    @GetMapping("/learning/comm/situation/{id}")
    public String situation(@PathVariable String id, Model model) {
        model.addAttribute("situationId", id);
        return "cpath/situation";
    }

    /** The pages link with {@code ?id=}; the situation page reads the id from the query first. */
    @GetMapping("/learning/comm/situation")
    public String situationByQuery(@RequestParam(name = "id", required = false) String id, Model model) {
        model.addAttribute("situationId", id);
        return "cpath/situation";
    }

    @GetMapping("/learning/comm/day")
    public String day() {
        return "cpath/day";
    }
}
