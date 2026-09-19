package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.dto.CreateLearningPathRequest;
import com.jlas.learning.dto.CreateLearningPathResult;
import com.jlas.learning.dto.LearningPathResponse;
import com.jlas.learning.service.LearningService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controller API lộ trình học. Base path: /api/learning */
@RestController
@RequestMapping("/api/learning")
@RequiredArgsConstructor
public class LearningController {
    private final LearningService learningService;

    @PostMapping("/paths")
    public ResponseEntity<ApiResponse<LearningPathResponse>> create(
            @RequestBody CreateLearningPathRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        CreateLearningPathResult result = learningService.create(user.userId(), request.assessmentAttemptId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(result.created() ? "Learning path created" : "Learning path already exists",
                        result.path()));
    }

    @GetMapping("/paths/current")
    public ResponseEntity<ApiResponse<LearningPathResponse>> current(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", learningService.current(user.userId())));
    }
}
