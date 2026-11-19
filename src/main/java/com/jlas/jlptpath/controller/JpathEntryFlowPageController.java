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
import org.springframework.web.bind.annotation.PostMapping;
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
    private static final String CONFIRM_PATH = "/learning/jlpt/confirm?";
    private static final String LEARNING = "redirect:/learning";

    private final JpathPathService pathService;
    private final JpathFeature feature;

    @GetMapping("/learning/jlpt/start")
    public String start(@AuthenticationPrincipal UserPrincipal user) {
        if (!feature.enabled()) {
            return "redirect:" + JpathStartRedirect.resolve(
                    new JpathStartRedirect.StartState(false, false, null, false, null, null, null, null));
        }
        return "redirect:" + JpathStartRedirect.resolve(startState(pathService.entryStatus(user.userId())));
    }

    /**
     * I4 (bug 55): opening the link only shows a "Học tiếp" screen; it never changes the path, so a prefetch or a link
     * from another page cannot make the path ACTIVE. Without a paused path, /learning decides where the learner belongs.
     */
    @GetMapping("/learning/jlpt/resume")
    public String resumePage(@AuthenticationPrincipal UserPrincipal user) {
        if (pathService.entryStatus(user.userId()).pathStatus() != JpathPathStatus.PAUSED) {
            return LEARNING;
        }
        return "jlptpath/resume";
    }

    /** Resumes the paused path (button on the resume screen). */
    @PostMapping("/learning/jlpt/resume")
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
     * Confirmation screen; the model carries only {@code source} and {@code assessmentAttemptId}. An ACTIVE path goes to
     * /learning. The page renders only when {@link JpathStartRedirect} would send the learner to a confirmation screen;
     * otherwise (missing or unknown source, malformed attempt id, PAUSED path, goal not JLPT, no survey or answers,
     * nothing to confirm) it goes back to start, which decides in one place.
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
        JpathEntryStatusView status = pathService.entryStatus(user.userId());
        if (status.pathStatus() == JpathPathStatus.ACTIVE) {
            return LEARNING;
        }
        if (!JpathStartRedirect.resolve(startState(status)).startsWith(CONFIRM_PATH)) {
            return START;
        }
        model.addAttribute("source", entrySource.name());
        model.addAttribute("assessmentAttemptId", attemptId);
        return "jlptpath/confirm";
    }

    private static JpathStartRedirect.StartState startState(JpathEntryStatusView status) {
        JpathEntryStatusView.SmartTest smartTest = status.smartTest();
        // [JLAS-W0108] Owner 07/11: who studied but does not remember kana skips Smart Test like a beginner
        // (they cannot read it): the start route treats them as not studied, the path starts at N5 with kana review.
        // An existing Smart Test attempt still wins: its result is used.
        Boolean studied = "REVIEW".equals(status.kanaChoice()) && smartTest == null ? Boolean.FALSE : status.studiedBefore();
        return new JpathStartRedirect.StartState(true, status.surveyDone(), status.goal(), status.preferenceDone(),
                studied, status.pathStatus(), smartTest == null ? null : smartTest.attemptId(),
                smartTest == null ? null : smartTest.status());
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
