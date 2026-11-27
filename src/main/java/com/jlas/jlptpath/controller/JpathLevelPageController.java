package com.jlas.jlptpath.controller;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.service.JpathLevelUpService;
import com.jlas.jlptpath.service.JpathPathService;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * [JLAS-W0108] Plan 3 Task 18 — certificate of a passed JLPT path level. Renders the shared template
 * {@code learning/certificate.html} read-only with its own model ({@code targetLevel}, {@code completedAt}); only when the
 * level's stage is PASSED, otherwise back to {@code /learning}. Guest → {@code /login}. The flag is checked by
 * {@code JpathFeatureInterceptor}.
 */
@Controller
@RequiredArgsConstructor
public class JpathLevelPageController {

    private final JpathPathService paths;
    private final JpathLevelUpService levelUp;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.jlas.auth.repository.UserRepository users;

    /** Level summary page (Plan 3 Task 23); jpath-level-summary.js reads GET /api/learning/jlpt/levels/{level}/summary. */
    @GetMapping("/learning/jlpt/levels/{level}/summary")
    public String summary(@AuthenticationPrincipal UserPrincipal user, @PathVariable String level, Model model) {
        if (user == null) {
            return "redirect:/login";
        }
        if (paths.entryStatus(user.userId()).pathStatus() != JpathPathStatus.ACTIVE) {
            return "redirect:/learning?view=jlpt";
        }
        String lvl = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        if (!lvl.matches("N[1-5]")) {
            return "redirect:/learning?view=jlpt";
        }
        model.addAttribute("level", lvl);
        return "jlptpath/level-summary";
    }

    @GetMapping("/learning/jlpt/levels/{level}/certificate")
    public String certificate(@AuthenticationPrincipal UserPrincipal user, @PathVariable String level, Model model) {
        if (user == null) {
            return "redirect:/login";
        }
        if (paths.entryStatus(user.userId()).pathStatus() != JpathPathStatus.ACTIVE) {
            return "redirect:/learning?view=jlpt";
        }
        String lvl = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        Optional<LocalDateTime> passedAt;
        try {
            passedAt = levelUp.passedAt(user.userId(), lvl);
        } catch (RuntimeException ex) {
            passedAt = Optional.empty();
        }
        if (passedAt.isEmpty()) {
            return "redirect:/learning?view=jlpt";
        }
        model.addAttribute("targetLevel", lvl);
        model.addAttribute("completedAt", passedAt.get());
        model.addAttribute("learnerName", certificateName(user));
        model.addAttribute("certificateMascot", certificateMascot(lvl));
        try {
            var summary = levelUp.summary(user.userId(), lvl);
            if (summary != null) {
                model.addAttribute("mockScore", summary.mockScore());
                model.addAttribute("nextLevel", summary.nextLevel());
            }
        } catch (RuntimeException ignored) {
            // keep certificate working even if summary lookup fails
        }
        return "learning/certificate";
    }

    private String certificateName(UserPrincipal user) {
        if (users == null) return user.username();
        return users.findById(user.userId()).map(u -> u.getFullName() == null || u.getFullName().isBlank()
                ? user.username() : u.getFullName()).orElse(user.username());
    }

    private static String certificateMascot(String level) {
        return switch (level == null ? "" : level) {
            case "N5" -> "/images/mascots/certificates/kitsu-daily-life.png";
            case "N4" -> "/images/mascots/certificates/kitsu-travel.png";
            case "N3" -> "/images/mascots/certificates/kitsu-work.png";
            case "N2", "N1" -> "/images/mascots/certificates/kitsu-brse.png";
            default -> "/images/mascots/fox/kitsu-celebrate-30.webp";
        };
    }
}
