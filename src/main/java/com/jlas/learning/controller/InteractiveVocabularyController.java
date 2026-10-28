package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.dto.MicroCheckDraftAnswer;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckSaveDraftRequest;
import com.jlas.learning.dto.MicroCheckStateResponse;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.dto.VocabularyStudyState;
import com.jlas.learning.service.InteractiveVocabularyService;
import com.jlas.learning.service.impl.InteractiveRateLimitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** [JLAS-W0193] Vocabulary study + micro-check for an owned V2 activity. */
@RestController
@RequestMapping("/api/learning/v2/activities/{activityId}/vocabulary")
@RequiredArgsConstructor
public class InteractiveVocabularyController {
    private final InteractiveVocabularyService service;
    private final InteractiveRateLimitService rateLimit;

    @GetMapping("/study")
    public ResponseEntity<ApiResponse<VocabularyStudyState>> study(
            @PathVariable Long activityId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.studyState(user.userId(), activityId)));
    }

    @PostMapping("/cards/{memberId}/flip")
    public ResponseEntity<ApiResponse<VocabularyStudyState>> flip(
            @PathVariable Long activityId, @PathVariable Long memberId,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.flip(user.userId(), activityId, memberId)));
    }

    @PostMapping("/complete")
    public ResponseEntity<ApiResponse<VocabularyStudyState>> complete(
            @PathVariable Long activityId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.markStudied(user.userId(), activityId)));
    }

    @PostMapping("/cards/{memberId}/remember")
    public ResponseEntity<ApiResponse<VocabularyStudyState>> remember(
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

    /** [JLAS-W0113] Autosave in-progress selections for an issued attempt (restored on reopen/reload). */
    @PostMapping("/micro-check/draft")
    public ResponseEntity<ApiResponse<java.util.List<MicroCheckDraftAnswer>>> saveDraft(
            @PathVariable Long activityId, @Valid @RequestBody MicroCheckSaveDraftRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        rateLimit.check(user.userId(), InteractiveRateLimitService.Bucket.MICRO_CHECK_DRAFT);
        return ResponseEntity.ok(ApiResponse.ok("OK", service.saveDraft(user.userId(), activityId, request)));
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
    public ResponseEntity<ApiResponse<VocabularyStudyState>> review(
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

    /** [JLAS-W0108] REVIEW feedback of past attempts; read-only. */
    @GetMapping("/review/attempts")
    public ResponseEntity<ApiResponse<java.util.List<com.jlas.learning.dto.AttemptFeedbackResponse>>> reviewAttempts(
            @PathVariable Long activityId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.reviewFeedback(user.userId(), activityId)));
    }

    @GetMapping("/attempt-history")
    public ResponseEntity<ApiResponse<java.util.List<com.jlas.learning.dto.QuizAttemptHistoryResponse>>> attemptHistory(
            @PathVariable Long activityId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.quizAttemptHistory(user.userId(), activityId)));
    }
}
