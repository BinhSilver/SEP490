package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.dto.ExtraPracticeIssue;
import com.jlas.learning.dto.ExtraPracticeStartRequest;
import com.jlas.learning.dto.ExtraPracticeSuggestions;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.service.impl.ComprehensionExtraPracticeService;
import com.jlas.learning.service.impl.InteractiveRateLimitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** §2.32 (owner 2026-10-26) — "Luyện thêm" beside a Reading/Listening activity: suggestions, start, submit. The learner
 * picks only one of their own suggestions; scoring, unlocking, rewards and completion are never touched. */
@RestController
@RequestMapping("/api/learning/v2/activities/{activityId}/comprehension/extra-practice")
@RequiredArgsConstructor
public class InteractiveExtraPracticeController {
    private final ComprehensionExtraPracticeService service;
    private final InteractiveRateLimitService rateLimit;

    @GetMapping
    public ResponseEntity<ApiResponse<ExtraPracticeSuggestions>> suggestions(
            @PathVariable Long activityId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.suggestions(user.userId(), activityId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ExtraPracticeIssue>> start(
            @PathVariable Long activityId, @Valid @RequestBody ExtraPracticeStartRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        rateLimit.check(user.userId(), InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE);
        return ResponseEntity.ok(ApiResponse.ok("OK", service.start(user.userId(), activityId, request.passage())));
    }

    @PostMapping("/submit")
    public ResponseEntity<ApiResponse<MicroCheckResult>> submit(
            @PathVariable Long activityId, @Valid @RequestBody MicroCheckSubmitRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        rateLimit.check(user.userId(), InteractiveRateLimitService.Bucket.MICRO_CHECK_SUBMIT);
        return ResponseEntity.ok(ApiResponse.ok("OK", service.submit(user.userId(), activityId, request)));
    }
}
