package com.jlas.cpath;

import com.jlas.auth.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    /** I21: guests go to sign in (SecurityConfig does the same; kept here so no page renders without a user). */
    static final String LOGIN = "redirect:/login";

    private final CpathService service;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.jlas.auth.repository.UserRepository users;

    public CpathPageController(CpathService service) {
        this.service = service;
    }

    /** Owner 06/11: certificate of a passed goal AI call, on the project's shared certificate page. */
    @GetMapping("/learning/comm/certificate")
    public String certificate(@org.springframework.security.core.annotation.AuthenticationPrincipal
                              com.jlas.auth.security.UserPrincipal user, Model model,
                              @RequestParam(name = "goal", required = false) String goal) {
        if (user == null) return LOGIN;
        model.addAttribute("learnerName", certificateName(user));
        // F125: ?goal= picks one finished path (a paused one after a promotion or a goal change); else the newest
        java.util.Optional<java.util.Map<String, Object>> c = service.certificate(user.userId(), goal);
        if (c.isPresent()) {
            String earnedGoal = String.valueOf(c.get().get("goal"));
            model.addAttribute("earned", true);
            model.addAttribute("targetLevel", "Giao tiếp · " + c.get().get("nameVi"));
            model.addAttribute("pathGoal", earnedGoal);
            model.addAttribute("certificateMascot", certificateMascot(earnedGoal));
            model.addAttribute("completedAt", c.get().get("takenAt"));
            model.addAttribute("workGoal", "WORK".equals(earnedGoal) || "BA".equals(earnedGoal));
            return "cpath/certificate";
        }
        java.util.Map<String, Object> path;
        if (goal != null && !goal.isBlank()) {
            try {
                path = service.path(user.userId(), goal);
            } catch (com.jlas.common.exception.ApiException ex) {
                String gate = gate(user, model);
                if (gate != null) return gate;
                path = service.path(user.userId());
            }
        } else {
            String gate = gate(user, model);
            if (gate != null) return gate;
            path = service.path(user.userId());
        }
        @SuppressWarnings("unchecked") java.util.Map<String, Object> progress = (java.util.Map<String, Object>) path.get("progress");
        model.addAttribute("earned", false);
        model.addAttribute("targetLevel", "Giao tiếp · " + path.get("nameVi"));
        model.addAttribute("completedAt", null);
        String pathGoal = String.valueOf(path.get("goal"));
        model.addAttribute("pathGoal", pathGoal);
        model.addAttribute("certificateMascot", certificateMascot(pathGoal));
        model.addAttribute("workGoal", "WORK".equals(pathGoal) || "BA".equals(pathGoal));
        model.addAttribute("passedTopics", progress.get("passedTopics"));
        model.addAttribute("totalTopics", progress.get("totalTopics"));
        return "cpath/certificate";
    }

    private static String certificateMascot(String goal) {
        return switch (goal == null ? "" : goal) {
            case "TRAVEL" -> "/images/mascots/certificates/kitsu-travel.png";
            case "DAILY_LIFE" -> "/images/mascots/certificates/kitsu-daily-life.png";
            case "WORK" -> "/images/mascots/certificates/kitsu-work.png";
            case "BA" -> "/images/mascots/certificates/kitsu-brse.png";
            default -> "/images/mascots/fox/kitsu-announcing-128.webp";
        };
    }

    private String certificateName(UserPrincipal user) {
        if (users == null) return user.username();
        return users.findById(user.userId()).map(u -> u.getFullName() == null || u.getFullName().isBlank()
                ? user.username() : u.getFullName()).orElse(user.username());
    }

    @GetMapping("/learning/comm/survey")
    public String survey(@AuthenticationPrincipal UserPrincipal user) {
        return user == null ? LOGIN : "cpath/survey";
    }

    @GetMapping("/learning/comm/settings")
    public String settings(@AuthenticationPrincipal UserPrincipal user, Model model) {
        return pathPage(user, model, "cpath/settings");
    }

    @GetMapping({"/learning/comm", "/learning/comm/path"})
    public String path(@AuthenticationPrincipal UserPrincipal user, Model model) {
        return pathPage(user, model, "cpath/path");
    }

    @GetMapping("/learning/comm/final-test")
    public String finalTest(@AuthenticationPrincipal UserPrincipal user, Model model) {
        return pathPage(user, model, "cpath/final-test");
    }

    @GetMapping("/learning/comm/situation/{id}")
    public String situation(@PathVariable String id, Model model, @AuthenticationPrincipal UserPrincipal user) {
        String gate = gate(user, model);
        if (gate != null) return gate;
        model.addAttribute("situationId", id);
        return "cpath/situation";
    }

    /** The pages link with {@code ?id=}; the situation page reads the id from the query first. */
    @GetMapping("/learning/comm/situation")
    public String situationByQuery(@RequestParam(name = "id", required = false) String id, Model model,
                                   @AuthenticationPrincipal UserPrincipal user) {
        String gate = gate(user, model);
        if (gate != null) return gate;
        // no id (old bookmark, hand-typed url): nothing to show, go back to the path instead of an error line
        if (id == null || id.isBlank()) return "redirect:/learning/comm";
        model.addAttribute("situationId", id);
        return "cpath/situation";
    }

    @GetMapping("/learning/comm/notebook")
    public String notebook(@AuthenticationPrincipal UserPrincipal user, Model model) {
        return pathPage(user, model, "cpath/notebook");
    }

    @GetMapping("/learning/comm/weekly")
    public String weekly(@AuthenticationPrincipal UserPrincipal user, Model model) {
        return pathPage(user, model, "cpath/weekly");
    }

    @GetMapping("/learning/comm/day")
    public String day(@AuthenticationPrincipal UserPrincipal user, Model model) {
        return pathPage(user, model, "cpath/day");
    }

    /** F70: today's base lesson ("Bài nền"); {@code ?check=1} is the "Đã biết – ôn nhanh" check. */
    @GetMapping("/learning/comm/core")
    public String core(@AuthenticationPrincipal UserPrincipal user, Model model) {
        return pathPage(user, model, "cpath/core");
    }

    /** F70 Du lịch: Sổ câu cấp tốc. */
    @GetMapping("/learning/comm/phrasebook")
    public String phrasebook(@AuthenticationPrincipal UserPrincipal user, Model model) {
        return pathPage(user, model, "cpath/phrasebook");
    }

    /** F70 Du lịch: Trò chơi nghe nhanh (60 giây). */
    @GetMapping("/learning/comm/game")
    public String game(@AuthenticationPrincipal UserPrincipal user, Model model) {
        return pathPage(user, model, "cpath/game");
    }

    private String pathPage(UserPrincipal user, Model model, String template) {
        String gate = gate(user, model);
        return gate != null ? gate : template;
    }

    /** QA 19/11: no path goes to the survey; a paused-only path shows one card with "Học tiếp" on every page. */
    private String gate(UserPrincipal user, Model model) {
        // I21: a guest never gets a page whose script calls the learner APIs
        if (user == null) return LOGIN;
        String state = service.pathState(user.userId());
        if ("NONE".equals(state)) return "redirect:/learning/comm/survey";
        if ("PAUSED".equals(state)) {
            model.addAttribute("pausedName", service.pausedGoalName(user.userId()));
            return "cpath/paused";
        }
        return null;
    }
}
