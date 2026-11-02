package com.jlas.jlptpath.controller;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathEntryStatusView;
import com.jlas.jlptpath.enums.JpathEntrySource;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.service.JpathFeature;
import com.jlas.jlptpath.service.JpathPathService;
import com.jlas.jlptpath.util.JpathStartRedirect;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * [JLAS-W0108] Entry pages of the JLPT path: {@code /learning/jlpt/start} sends the learner on by
 * {@link JpathStartRedirect}, {@code /resume} makes a paused path active again, {@code /confirm} shows the confirmation
 * screen (its script reads the data from the API). The feature flag is checked by {@code JpathFeatureInterceptor}.
 */
@Controller
@RequiredArgsConstructor
public class JpathEntryFlowPageController {

    private static final String START = "redirect:/learning/jlpt/start";
    private static final String RESUME = "redirect:/learning/jlpt/resume";
    private static final String LEARNING = "redirect:/learning";

    private final JpathPathService pathService;
    private final JpathFeature feature;

    @GetMapping("/learning/jlpt/start")
    public String start(@AuthenticationPrincipal UserPrincipal user) {
        if (!feature.enabled()) {
            return "redirect:" + JpathStartRedirect.resolve(
                    new JpathStartRedirect.StartState(false, false, null, false, null, null, null, null));
        }
        JpathEntryStatusView status = pathService.entryStatus(user.userId());
        JpathEntryStatusView.SmartTest smartTest = status.smartTest();
        return "redirect:" + JpathStartRedirect.resolve(new JpathStartRedirect.StartState(true, status.surveyDone(),
                status.goal(), status.preferenceDone(), status.studiedBefore(), status.pathStatus(),
                smartTest == null ? null : smartTest.attemptId(), smartTest == null ? null : smartTest.status()));
    }

    /** Resumes the paused path; without one, /learning decides where the learner belongs. */
    @GetMapping("/learning/jlpt/resume")
    public String resume(@AuthenticationPrincipal UserPrincipal user) {
        try {
            pathService.resumePaused(user.userId());
        } catch (ApiException e) {
            if (e.getErrorCode() != ErrorCode.JPATH_NO_ACTIVE_PATH) {
                throw e;
            }
        }
        return LEARNING;
    }

    /**
     * Confirmation screen; the model carries only {@code source} and {@code assessmentAttemptId}. A missing or unknown
     * source or a malformed attempt id goes back to start; an ACTIVE path goes to /learning, a PAUSED one to resume.
     */
    @GetMapping("/learning/jlpt/confirm")
    public String confirm(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String assessmentAttemptId,
            Model model) {
        JpathEntrySource entrySource = parseSource(source);
        Long attemptId = parseAttemptId(assessmentAttemptId);
        boolean attemptGiven = assessmentAttemptId != null && !assessmentAttemptId.isBlank();
        if (entrySource == null || (attemptGiven && attemptId == null)) {
            return START;
        }
        JpathPathStatus pathStatus = pathService.entryStatus(user.userId()).pathStatus();
        if (pathStatus == JpathPathStatus.ACTIVE) {
            return LEARNING;
        }
        if (pathStatus == JpathPathStatus.PAUSED) {
            return RESUME;
        }
        model.addAttribute("source", entrySource.name());
        model.addAttribute("assessmentAttemptId", attemptId);
        return "jlptpath/confirm";
    }

    private static JpathEntrySource parseSource(String source) {
        if (source == null) {
            return null;
        }
        try {
            return JpathEntrySource.valueOf(source.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Long parseAttemptId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(value.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
