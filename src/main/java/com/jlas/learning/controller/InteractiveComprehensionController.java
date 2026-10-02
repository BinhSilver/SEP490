package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.dto.ComprehensionStudyState;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckStateResponse;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.service.InteractiveComprehensionService;
import com.jlas.learning.service.impl.InteractiveRateLimitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** [JLAS-W0193] RC Task 5 — Reading/Listening study + micro-check controller. One type-safe route serves both
 * activity types: {@link InteractiveComprehensionService} resolves READING vs LISTENING from the owned
 * {@code UserLearningPathItem} row itself (see {@code InteractiveComprehensionServiceImpl#ownedComprehension}),
 * never from the URL or request body, so a mismatched page/activity combination cannot spoof the other skill.
 *
 * <p>The micro-check sub-path ({@code /micro-check}, {@code /micro-check/submit}, {@code /micro-check/state},
 * {@code /micro-check/review}) mirrors {@link InteractiveGrammarController} exactly — same shared engine, same
 * rate-limit buckets, same envelope. {@code /review-evidence} is new for this task: a Dialogue-style no-body
 * POST (compare {@link InteractiveDialogueController#reachedBottom}) that records the server-timestamped
 * "reached end of passage" (Reading) or "played the canonical audio" (Listening) interaction that
 * {@code InteractiveStudyEngine#recordReviewEvidence} needs before {@code completeReview} can clear the
 * post-third-failure review gate. The client cannot supply a duration, source id, score or completion flag on
 * either endpoint — both take only the path-derived activity id and the JWT principal. */
@RestController
@RequestMapping("/api/learning/v2/activities/{activityId}/comprehension")
@RequiredArgsConstructor
public class InteractiveComprehensionController {
    private final InteractiveComprehensionService service;
    private final InteractiveRateLimitService rateLimit;

    @GetMapping("/study")
    public ResponseEntity<ApiResponse<ComprehensionStudyState>> study(
            @PathVariable Long activityId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.studyState(user.userId(), activityId)));
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

    @PostMapping("/review-evidence")
    public ResponseEntity<ApiResponse<MicroCheckStateResponse>> reviewEvidence(
            @PathVariable Long activityId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.recordReviewEvidence(user.userId(), activityId)));
    }

    @PostMapping("/micro-check/review")
    public ResponseEntity<ApiResponse<MicroCheckStateResponse>> completeReview(
            @PathVariable Long activityId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.completeReview(user.userId(), activityId)));
    }
}
