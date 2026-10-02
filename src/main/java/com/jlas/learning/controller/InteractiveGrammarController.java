package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.dto.GrammarFillSubmitRequest;
import com.jlas.learning.dto.GrammarOrderingSubmitRequest;
import com.jlas.learning.dto.GrammarPracticeResult;
import com.jlas.learning.dto.GrammarStudyState;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckStateResponse;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.service.InteractiveGrammarService;
import com.jlas.learning.service.impl.InteractiveRateLimitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** [JLAS-W0193] Task 6 — Grammar study + practice + micro-check for an owned V2 activity. The micro-check
 * sub-path mirrors {@link InteractiveKanjiController}/{@code InteractiveVocabularyController} exactly (same
 * shared engine); {@code /study} and {@code /points/{id}/practice/**} are Grammar-specific. */
@RestController
@RequestMapping("/api/learning/v2/activities/{activityId}/grammar")
@RequiredArgsConstructor
public class InteractiveGrammarController {
    private final InteractiveGrammarService service;
    private final InteractiveRateLimitService rateLimit;

    @GetMapping("/study")
    public ResponseEntity<ApiResponse<GrammarStudyState>> study(
            @PathVariable Long activityId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.studyState(user.userId(), activityId)));
    }

    @GetMapping("/review")
    public ResponseEntity<ApiResponse<GrammarStudyState>> review(
            @PathVariable Long activityId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.reviewState(user.userId(), activityId)));
    }

    @PostMapping("/points/{grammarPointId}/practice/ordering")
    public ResponseEntity<ApiResponse<GrammarPracticeResult>> submitOrdering(
            @PathVariable Long activityId, @PathVariable Long grammarPointId,
            @Valid @RequestBody GrammarOrderingSubmitRequest request, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK",
                service.submitOrdering(user.userId(), activityId, grammarPointId, request)));
    }

    @PostMapping("/points/{grammarPointId}/practice/fill")
    public ResponseEntity<ApiResponse<GrammarPracticeResult>> submitFill(
            @PathVariable Long activityId, @PathVariable Long grammarPointId,
            @Valid @RequestBody GrammarFillSubmitRequest request, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK",
                service.submitFill(user.userId(), activityId, grammarPointId, request)));
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
}
