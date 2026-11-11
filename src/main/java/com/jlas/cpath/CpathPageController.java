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

    private final CpathService service;

    public CpathPageController(CpathService service) {
        this.service = service;
    }

    /** Owner 06/11: certificate of a passed goal AI call, on the project's shared certificate page. */
    @GetMapping("/learning/comm/certificate")
    public String certificate(@org.springframework.security.core.annotation.AuthenticationPrincipal
                              com.jlas.auth.security.UserPrincipal user, Model model) {
        if (user == null) return "redirect:/login";
        java.util.Optional<java.util.Map<String, Object>> c = service.certificate(user.userId());
        java.util.Map<String, Object> path = service.path(user.userId());
        @SuppressWarnings("unchecked") java.util.Map<String, Object> progress = (java.util.Map<String, Object>) path.get("progress");
        model.addAttribute("earned", c.isPresent());
        model.addAttribute("targetLevel", c.isPresent() ? "Giao tiếp · " + c.get().get("nameVi") : "Giao tiếp · " + path.get("nameVi"));
        model.addAttribute("completedAt", c.isPresent() ? c.get().get("takenAt") : null);
        String goal = c.isPresent() ? String.valueOf(c.get().get("goal")) : String.valueOf(path.get("goal"));
        model.addAttribute("workGoal", "WORK".equals(goal) || "BA".equals(goal));
        model.addAttribute("passedTopics", progress.get("passedTopics"));
        model.addAttribute("totalTopics", progress.get("totalTopics"));
        return "cpath/certificate";
    }

    @GetMapping("/learning/comm/survey")
    public String survey() {
        return "cpath/survey";
    }

    @GetMapping("/learning/comm/settings")
    public String settings() {
        return "cpath/settings";
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

    @GetMapping("/learning/comm/notebook")
    public String notebook() {
        return "cpath/notebook";
    }

    @GetMapping("/learning/comm/weekly")
    public String weekly() {
        return "cpath/weekly";
    }

    @GetMapping("/learning/comm/day")
    public String day() {
        return "cpath/day";
    }
}
