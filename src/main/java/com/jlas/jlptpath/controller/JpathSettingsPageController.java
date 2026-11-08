package com.jlas.jlptpath.controller;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.service.JpathPathService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * [JLAS-W0108] Settings page of the JLPT path ({@code /learning/jlpt/settings}); jpath-settings.js fills it from
 * {@code GET /api/learning/jlpt/settings}. Without an ACTIVE path (none, or PAUSED) the learner goes to
 * {@code /learning/jlpt/start}, which decides where they belong. The feature flag is checked by
 * {@code JpathFeatureInterceptor}.
 */
@Controller
@RequiredArgsConstructor
public class JpathSettingsPageController {

    private final JpathPathService pathService;

    @GetMapping("/learning/jlpt/settings")
    public String settings(@AuthenticationPrincipal UserPrincipal user) {
        if (pathService.entryStatus(user.userId()).pathStatus() != JpathPathStatus.ACTIVE) {
            return "redirect:/learning/jlpt/start";
        }
        return "jlptpath/settings";
    }
}
