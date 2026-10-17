package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.dto.GoalSpecificPathRequest;
import com.jlas.learning.service.GoalSpecificPathService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/learning/v2/goal-paths")
@RequiredArgsConstructor
public class GoalSpecificPathController {
    private final GoalSpecificPathService service;

    @PostMapping
    public ResponseEntity<ApiResponse<GoalSpecificPathService.Result>> create(
            @Valid @RequestBody GoalSpecificPathRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        var result = service.create(user.userId(), request.submissionId(), request.assessmentAttemptId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(result.created() ? "Đã tạo lộ trình" : "Lộ trình đã tồn tại", result));
    }
}
