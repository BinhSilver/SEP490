package com.jlas.progress.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.progress.dto.LearningAnalyticsResponse;
import com.jlas.progress.service.LearningAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/progress")
@RequiredArgsConstructor
public class LearningAnalyticsController {
    private final LearningAnalyticsService analytics;

    @GetMapping("/analytics")
    public ResponseEntity<ApiResponse<LearningAnalyticsResponse>> get(
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail("Vui lòng đăng nhập để xem tiến độ."));
        }
        return ResponseEntity.ok(ApiResponse.ok("OK", analytics.get(principal.userId())));
    }
}
