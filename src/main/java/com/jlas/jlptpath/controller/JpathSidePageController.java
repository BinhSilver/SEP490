package com.jlas.jlptpath.controller;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.jlptpath.dto.JpathPrintRequest;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.service.JpathPathService;
import com.jlas.jlptpath.service.JpathPrintService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * [JLAS-W0108] Side pages of the JLPT path that open in their own tab. {@link #guard} sends a guest to {@code /login}
 * and a learner without an ACTIVE path to {@code /learning}, like the learner pages. The feature flag is checked by
 * {@code JpathFeatureInterceptor}.
 */
@Controller
@RequiredArgsConstructor
public class JpathSidePageController {

    private final JpathPathService paths;
    private final JpathPrintService print;

    /**
     * A4 writing sheet ({@code jlptpath/print}, no base shell): the query is a {@link JpathPrintRequest}; a bad query
     * renders the page with the error line instead of a sheet.
     */
    @GetMapping("/learning/jlpt/print")
    public String print(@AuthenticationPrincipal UserPrincipal user, @ModelAttribute JpathPrintRequest request,
                        Model model) {
        String blocked = guard(user);
        if (blocked != null) {
            return blocked;
        }
        try {
            model.addAttribute("sheet", print.sheet(user.userId(), request));
        } catch (ApiException e) {
            model.addAttribute("printError", e.getMessage());
        }
        return "jlptpath/print";
    }

    /** History page; jpath-history.js reads {@code GET /api/learning/jlpt/history} (and {@code /history/{day}}). */
    @GetMapping("/learning/jlpt/history")
    public String history(@AuthenticationPrincipal UserPrincipal user) {
        String blocked = guard(user);
        return blocked != null ? blocked : "jlptpath/history";
    }

    /** Owner 05/11: one study day in full; jpath-day.js reads {@code GET /api/learning/jlpt/history/{day}}. */
    @GetMapping("/learning/jlpt/day")
    public String day(@AuthenticationPrincipal UserPrincipal user) {
        String blocked = guard(user);
        return blocked != null ? blocked : "jlptpath/day";
    }

    /** Stats page; jpath-stats.js reads {@code /stats}, {@code /stats/heatmap} and {@code /badges}. */
    @GetMapping("/learning/jlpt/stats")
    public String stats(@AuthenticationPrincipal UserPrincipal user) {
        String blocked = guard(user);
        return blocked != null ? blocked : "jlptpath/stats";
    }

    /** Known store; jpath-wordbank.js reads {@code GET /api/learning/jlpt/known}. */
    @GetMapping("/learning/jlpt/known")
    public String known(@AuthenticationPrincipal UserPrincipal user) {
        String blocked = guard(user);
        return blocked != null ? blocked : "jlptpath/known";
    }

    /** Notebook; jpath-wordbank.js reads {@code GET /api/learning/jlpt/notebook}. */
    @GetMapping("/learning/jlpt/notebook")
    public String notebook(@AuthenticationPrincipal UserPrincipal user) {
        String blocked = guard(user);
        return blocked != null ? blocked : "jlptpath/notebook";
    }

    /** Confusable pairs; jpath-confusables.js reads {@code GET /api/learning/jlpt/confusables}. */
    @GetMapping("/learning/jlpt/confusables")
    public String confusables(@AuthenticationPrincipal UserPrincipal user) {
        String blocked = guard(user);
        return blocked != null ? blocked : "jlptpath/confusables";
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
