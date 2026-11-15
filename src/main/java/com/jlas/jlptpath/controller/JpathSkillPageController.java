package com.jlas.jlptpath.controller;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.service.JpathPathService;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * [JLAS-W0108] Plan 3 pages of Đọc · Nghe: the skill session ({@code /learning/jlpt/skills/session/{id}}) and the timed
 * section practice ({@code /learning/jlpt/skills/section/{token}}). A guest goes to {@code /login}, a learner without
 * an ACTIVE path to {@code /learning}; the scripts read everything from the REST API. The feature flag is checked by
 * {@code JpathFeatureInterceptor}.
 */
@Controller
@RequiredArgsConstructor
public class JpathSkillPageController {

    private static final Pattern TOKEN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final JpathPathService paths;

    @GetMapping("/learning/jlpt/skills/session/{id}")
    public String session(@AuthenticationPrincipal UserPrincipal user, @PathVariable long id, Model model) {
        String blocked = guard(user);
        if (blocked != null) {
            return blocked;
        }
        if (id <= 0) {
            return "redirect:/learning";
        }
        model.addAttribute("sessionId", id);
        return "jlptpath/skill-session";
    }

    @GetMapping("/learning/jlpt/skills/section/{token}")
    public String section(@AuthenticationPrincipal UserPrincipal user, @PathVariable String token, Model model) {
        String blocked = guard(user);
        if (blocked != null) {
            return blocked;
        }
        if (token == null || !TOKEN.matcher(token).matches()) {
            return "redirect:/learning";
        }
        model.addAttribute("token", token);
        return "jlptpath/skill-section";
    }

    /**
     * F142: a malformed id in the address ({@code ?session=abc}, {@code /session/abc}) goes back to the path instead
     * of the raw 400 JSON envelope of the API handler.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public String malformedAddress() {
        return "redirect:/learning";
    }

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
