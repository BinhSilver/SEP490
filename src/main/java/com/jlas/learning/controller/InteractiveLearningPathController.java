package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.dto.InteractiveLearningPackageResponse;
import com.jlas.learning.dto.InteractiveLearningPathResponse;
import com.jlas.learning.service.InteractiveLearningPathService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/learning/v2")
@RequiredArgsConstructor
public class InteractiveLearningPathController {
    private final InteractiveLearningPathService service;

    @GetMapping("/paths/current")
    public ResponseEntity<ApiResponse<InteractiveLearningPathResponse>> current(
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.current(user.userId())));
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
