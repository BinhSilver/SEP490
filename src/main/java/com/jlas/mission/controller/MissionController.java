package com.jlas.mission.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.mission.dto.MissionSummaryResponse;
import com.jlas.mission.service.MissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST API for the learner's daily goal, credit, and streak. Base path: /api/mission */
@RestController
@RequestMapping("/api/mission")
@RequiredArgsConstructor
public class MissionController {
    private final MissionService missionService;

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<MissionSummaryResponse>> summary(
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", missionService.summary(user.userId())));
    }
}
