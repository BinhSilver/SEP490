package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.dto.FoundationStudyState;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckStateResponse;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.service.InteractiveFoundationService;
import com.jlas.learning.service.impl.InteractiveRateLimitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** [JLAS-W0108] Hiragana/Katakana foundation study + micro-check for an owned V2 activity. Same contract
 * shape as {@link InteractiveKanjiController}, different content adapter underneath. */
@RestController
@RequestMapping("/api/learning/v2/activities/{activityId}/foundation")
@RequiredArgsConstructor
public class InteractiveFoundationController {
    private final InteractiveFoundationService service;
    private final InteractiveRateLimitService rateLimit;

    @GetMapping("/study")
    public ResponseEntity<ApiResponse<FoundationStudyState>> study(
            @PathVariable Long activityId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.studyState(user.userId(), activityId)));
    }

    @PostMapping("/cards/{memberId}/flip")
    public ResponseEntity<ApiResponse<FoundationStudyState>> flip(
            @PathVariable Long activityId, @PathVariable Long memberId,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.flip(user.userId(), activityId, memberId)));
    }

    @PostMapping("/cards/{memberId}/remember")
    public ResponseEntity<ApiResponse<FoundationStudyState>> remember(
            @PathVariable Long activityId, @PathVariable Long memberId,
            @RequestParam boolean remembered, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK",
                service.remember(user.userId(), activityId, memberId, remembered)));
    }

    @PostMapping("/micro-check")
    public ResponseEntity<ApiResponse<MicroCheckResponse>> issue(
            @PathVariable Long activityId, @AuthenticationPrincipal UserPrincipal user) {
        rateLimit.check(user.userId(), InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE);
        return ResponseEntity.ok(ApiResponse.ok("OK", service.issueMicroCheck(user.userId(), activityId)));
    }

    @PostMapping("/micro-check/submit")
    public ResponseEntity<ApiResponse<MicroCheckResult>> submit(
            @PathVariable Long activityId, @Valid @RequestBody MicroCheckSubmitRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        rateLimit.check(user.userId(), InteractiveRateLimitService.Bucket.MICRO_CHECK_SUBMIT);
        return ResponseEntity.ok(ApiResponse.ok("OK", service.submitMicroCheck(user.userId(), activityId, request)));
    }

    @GetMapping("/micro-check/state")
    public ResponseEntity<ApiResponse<MicroCheckStateResponse>> state(
            @PathVariable Long activityId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.microCheckState(user.userId(), activityId)));
    }

    @PostMapping("/micro-check/review")
    public ResponseEntity<ApiResponse<MicroCheckStateResponse>> completeReview(
            @PathVariable Long activityId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.completeReview(user.userId(), activityId)));
    }
}
