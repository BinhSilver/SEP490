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

    @GetMapping("/learning/jlpt/levels/{level}/certificate")
    public String certificate(@AuthenticationPrincipal UserPrincipal user, @PathVariable String level, Model model) {
        if (user == null) {
            return "redirect:/login";
        }
        if (paths.entryStatus(user.userId()).pathStatus() != JpathPathStatus.ACTIVE) {
            return "redirect:/learning";
        }
        String lvl = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        Optional<LocalDateTime> passedAt;
        try {
            passedAt = levelUp.passedAt(user.userId(), lvl);
        } catch (RuntimeException ex) {
            passedAt = Optional.empty();
        }
        if (passedAt.isEmpty()) {
            return "redirect:/learning";
        }
        model.addAttribute("targetLevel", lvl);
        model.addAttribute("completedAt", passedAt.get());
        return "learning/certificate";
    }
}
