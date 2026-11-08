package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.dto.JpathMockStatus;
import com.jlas.jlptpath.service.JpathMockService;
import com.jlas.learning.dto.CompleteFromMockRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * [JLAS-W0108] Plan 3 Task 17 — the "Thi thử" card of a JLPT path level. Authenticated (default {@code /api/**} rule) and
 * behind the JLPT path feature flag ({@code JpathFeatureInterceptor} covers {@code /api/learning/jlpt/**}). The body of
 * {@code /mock/complete} is the Mock team's {@link CompleteFromMockRequest}: only {@code attemptId}, never pass/fail.
 */
@RestController
@RequestMapping("/api/learning/jlpt/mock")
@RequiredArgsConstructor
public class JpathMockController {

    private final JpathMockService mockService;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<JpathMockStatus>> status(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required = false) String level) {
        return ResponseEntity.ok(ApiResponse.ok("OK", mockService.status(user.userId(), level)));
    }

    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<JpathMockStatus>> sync(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required = false) String level) {
        return ResponseEntity.ok(ApiResponse.ok("OK", mockService.sync(user.userId(), level)));
    }

    @PostMapping("/complete")
    public ResponseEntity<ApiResponse<JpathMockStatus>> complete(@AuthenticationPrincipal UserPrincipal user,
            @Valid @RequestBody CompleteFromMockRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật kết quả Thi thử.", mockService.complete(user.userId(), request)));
    }
}
