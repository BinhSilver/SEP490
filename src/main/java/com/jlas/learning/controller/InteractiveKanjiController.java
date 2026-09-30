package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.dto.KanjiStudyState;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckStateResponse;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.service.InteractiveKanjiService;
import com.jlas.learning.service.impl.InteractiveRateLimitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** [JLAS-W0193] Kanji study + micro-check for an owned V2 activity. Same contract shape as
 * {@link InteractiveVocabularyController} (Task 4), different content adapter underneath (Task 5). */
@RestController
@RequestMapping("/api/learning/v2/activities/{activityId}/kanji")
@RequiredArgsConstructor
public class InteractiveKanjiController {
    private final InteractiveKanjiService service;
    private final InteractiveRateLimitService rateLimit;

    @GetMapping("/study")
    public ResponseEntity<ApiResponse<KanjiStudyState>> study(
            @PathVariable Long activityId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.studyState(user.userId(), activityId)));
    }

    @PostMapping("/cards/{memberId}/flip")
    public ResponseEntity<ApiResponse<KanjiStudyState>> flip(
            @PathVariable Long activityId, @PathVariable Long memberId,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.flip(user.userId(), activityId, memberId)));
    }

    @PostMapping("/cards/{memberId}/remember")
    public ResponseEntity<ApiResponse<KanjiStudyState>> remember(
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

    /** [JLAS-W0108] Task 10 - read-only REVIEW of a finished activity; writes nothing. */
    @GetMapping("/review")
    public ResponseEntity<ApiResponse<KanjiStudyState>> review(
            @PathVariable Long activityId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.reviewState(user.userId(), activityId)));
    }

    /** [JLAS-W0108] Task 10 - PRACTICE replay: recorded but never completes, rewards or unlocks. */
    @PostMapping("/practice")
    public ResponseEntity<ApiResponse<MicroCheckResponse>> issuePractice(
            @PathVariable Long activityId, @AuthenticationPrincipal UserPrincipal user) {
        rateLimit.check(user.userId(), InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE);
        return ResponseEntity.ok(ApiResponse.ok("OK", service.issuePractice(user.userId(), activityId)));
    }

    @PostMapping("/practice/submit")
    public ResponseEntity<ApiResponse<MicroCheckResult>> submitPractice(
            @PathVariable Long activityId, @Valid @RequestBody MicroCheckSubmitRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        rateLimit.check(user.userId(), InteractiveRateLimitService.Bucket.MICRO_CHECK_SUBMIT);
        return ResponseEntity.ok(ApiResponse.ok("OK",
                service.submitPractice(user.userId(), activityId, request)));
    }
}
