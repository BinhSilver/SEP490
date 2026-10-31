package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.dto.CompleteFromMockRequest;
import com.jlas.learning.dto.FinalEligibilityResponse;
import com.jlas.learning.dto.LearningPacePreviewResponse;
import com.jlas.learning.dto.LearningPaceRequest;
import com.jlas.learning.dto.PathCompletionResponse;
import com.jlas.learning.service.InteractivePaceReplanService;
import com.jlas.learning.service.LearningFinalEligibilityService;
import com.jlas.learning.service.LearningPathCompletionService;
import jakarta.validation.Valid;
import com.jlas.learning.dto.InteractiveLearningPackageResponse;
import com.jlas.learning.dto.InteractiveLearningPathResponse;
import com.jlas.learning.dto.InteractivePathSummaryResponse;
import com.jlas.learning.service.InteractiveLearningPathService;
import com.jlas.learning.service.InteractivePathSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/learning/v2")
@RequiredArgsConstructor
public class InteractiveLearningPathController {
    private final InteractiveLearningPathService service;
    private final InteractivePathSummaryService summaryService;
    private final LearningFinalEligibilityService eligibilityService;
    private final LearningPathCompletionService completionService;
    private final InteractivePaceReplanService paceService;

    @GetMapping("/paths/current")
    public ResponseEntity<ApiResponse<InteractiveLearningPathResponse>> current(
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.current(user.userId())));
    }

    @GetMapping("/paths/completed/roadmap")
    public ResponseEntity<ApiResponse<InteractiveLearningPathResponse>> completedRoadmap(
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.completedRoadmap(user.userId())));
    }

    /** [JLAS-W0193] Task 10 — real content for the completion popup's "Xem tổng kết chặng" destination.
     * Resolves the caller's most recent V2 path itself (never accepts a path id), so it stays reachable once
     * the path has transitioned to COMPLETED (Task 10's path-completion fix) when {@code current} would no
     * longer match it. */
    @GetMapping("/paths/current/summary")
    public ResponseEntity<ApiResponse<InteractivePathSummaryResponse>> summary(
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", summaryService.summary(user.userId())));
    }

    /** [JLAS-W0108] Task 9 — server-derived stage progress and whether the Mock Test is open yet. */
    @GetMapping("/paths/current/final-eligibility")
    public ResponseEntity<ApiResponse<FinalEligibilityResponse>> finalEligibility(
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", eligibilityService.forUser(user.userId())));
    }

    /** [JLAS-W0108] Task 9 — body is only an attempt id; the server verifies the Mock Test itself. Idempotent. */
    @PostMapping("/paths/current/complete")
    public ResponseEntity<ApiResponse<PathCompletionResponse>> completeFromMock(
            @Valid @RequestBody CompleteFromMockRequest request, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK",
                completionService.completeFromMock(user.userId(), request.attemptId())));
    }

    /** [JLAS-W0108] Task 10 - read-only preview of LIGHTER / SAME / FASTER pace; returns the confirm token. */
    @GetMapping("/paths/current/pace/preview")
    public ResponseEntity<ApiResponse<LearningPacePreviewResponse>> pacePreview(
            @RequestParam String direction, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", paceService.preview(user.userId(), direction)));
    }

    /** F1-6 (owner 2026-10-28) - read-only: real study time against estimates and a pace suggestion (never applied). */
    @GetMapping("/paths/current/pace/measured")
    public ResponseEntity<ApiResponse<com.jlas.learning.dto.LearningPaceMeasurement>> paceMeasured(
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", paceService.measure(user.userId())));
    }

    /** [JLAS-W0108] Task 10 - applies a previewed pace; only day layout and minutes per day change. */
    @PostMapping("/paths/current/pace")
    public ResponseEntity<ApiResponse<LearningPacePreviewResponse>> applyPace(
            @RequestBody LearningPaceRequest request, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", paceService.apply(user.userId(), request)));
    }

    @GetMapping("/packages/{packageId}")
    public ResponseEntity<ApiResponse<InteractiveLearningPackageResponse>> packageDetail(
            @PathVariable Long packageId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.packageDetail(user.userId(), packageId)));
    }

    @PostMapping("/packages/{packageId}/start")
    public ResponseEntity<ApiResponse<InteractiveLearningPackageResponse>> start(
            @PathVariable Long packageId, @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("Đã bắt đầu ngày học", service.start(user.userId(), packageId)));
    }
}
