package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathDaySummaryView;
import com.jlas.jlptpath.dto.JpathSessionStartRequest;
import com.jlas.jlptpath.dto.JpathSessionView;
import com.jlas.jlptpath.dto.JpathTodayView;
import com.jlas.jlptpath.enums.JpathSessionTab;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.service.JpathDailyPlanService;
import com.jlas.jlptpath.service.JpathStudyService;
import jakarta.validation.Valid;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * [JLAS-W0108] Today's plan and study sessions of the JLPT path. The learner is always the principal.
 */
@RestController
@RequestMapping("/api/learning/jlpt")
@RequiredArgsConstructor
public class JpathStudyController {

    private final JpathDailyPlanService dailyPlans;
    private final JpathStudyService study;

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<JpathTodayView>> today(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", dailyPlans.today(user.userId())));
    }

    @PostMapping("/today/review-day")
    public ResponseEntity<ApiResponse<JpathTodayView>> reviewDay(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("Hôm nay là ngày ôn.", dailyPlans.chooseReviewDay(user.userId())));
    }

    @GetMapping("/today/summary")
    public ResponseEntity<ApiResponse<JpathDaySummaryView>> summary(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", dailyPlans.summary(user.userId())));
    }

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<JpathSessionView>> start(@AuthenticationPrincipal UserPrincipal user,
            @Valid @RequestBody JpathSessionStartRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("OK", study.start(user.userId(), request)));
    }

    /** The open TODAY session on {@code tab} or COMBINED; none → {@code data = null}. No tab or COMBINED = any. */
    @GetMapping("/sessions/current")
    public ResponseEntity<ApiResponse<JpathSessionView>> current(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(name = "tab", required = false) String tab) {
        return ResponseEntity.ok(ApiResponse.ok("OK", study.current(user.userId(), parseTab(tab)).orElse(null)));
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<ApiResponse<JpathSessionView>> get(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok("OK", study.get(user.userId(), id)));
    }

    private static JpathTab parseTab(String tab) {
        if (tab == null || tab.isBlank()) {
            return null;
        }
        String t = tab.trim().toUpperCase(Locale.ROOT);
        if (t.equals(JpathSessionTab.COMBINED.name())) {
            return null;
        }
        try {
            return JpathTab.valueOf(t);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
