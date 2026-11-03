package com.jlas.jlptpath.controller;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.service.JpathPathService;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * [JLAS-W0108] Learner pages of the JLPT path that need an ACTIVE path (grammar lesson; the check, study and done pages
 * join here). {@link #guard} sends a guest to {@code /login} and a learner without an ACTIVE path to {@code /learning}.
 * The feature flag is checked by {@code JpathFeatureInterceptor}; the page scripts load their data from the REST API.
 */
@Controller
@RequiredArgsConstructor
public class JpathLearnerPageController {

    private static final Pattern GRAMMAR_CODE = Pattern.compile("^JG-[0-9a-f]{10}$");
    private static final Pattern CHECK_TOKEN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final JpathPathService paths;

    /** Grammar lesson page; jpath-lists.js draws it from {@code GET /api/learning/jlpt/grammar/{code}}. */
    @GetMapping("/learning/jlpt/grammar/{code}")
    public String grammar(@AuthenticationPrincipal UserPrincipal user, @PathVariable String code, Model model) {
        String blocked = guard(user);
        if (blocked != null) {
            return blocked;
        }
        if (code == null || !GRAMMAR_CODE.matcher(code).matches()) {
            return "redirect:/learning?tab=grammar";
        }
        model.addAttribute("layoutMode", "app");
        model.addAttribute("grammarCode", code);
        return "jlptpath/grammar-lesson";
    }

    /** Micro-check page; jpath-check.js draws it from {@code GET /api/learning/jlpt/checks/{token}}. */
    @GetMapping("/learning/jlpt/check/{token}")
    public String check(@AuthenticationPrincipal UserPrincipal user, @PathVariable String token, Model model) {
        String blocked = guard(user);
        if (blocked != null) {
            return blocked;
        }
        if (token == null || !CHECK_TOKEN.matcher(token).matches()) {
            return "redirect:/learning";
        }
        model.addAttribute("layoutMode", "app");
        model.addAttribute("checkToken", token);
        return "jlptpath/check";
    }

    /** {@code null} when the learner may open the page, otherwise the redirect to send. */
    String guard(UserPrincipal user) {
        if (user == null) {
            return "redirect:/login";
        }
        if (paths.entryStatus(user.userId()).pathStatus() != JpathPathStatus.ACTIVE) {
            return "redirect:/learning";
        }
        return null;
    }
}
