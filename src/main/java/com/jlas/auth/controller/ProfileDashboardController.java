package com.jlas.auth.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.dto.ProfileDashboardResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.auth.service.ProfileDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileDashboardController {
    private final ProfileDashboardService dashboard;

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<ProfileDashboardResponse>> get(
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail("Unauthorized"));
        }
        return ResponseEntity.ok(ApiResponse.ok("OK", dashboard.get(principal.userId())));
    }
}
