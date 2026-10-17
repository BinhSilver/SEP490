package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.dto.GoalSurveyDefinition;
import com.jlas.learning.dto.GoalSurveySubmissionRequest;
import com.jlas.learning.dto.GoalSurveySubmissionResponse;
import com.jlas.learning.service.GoalSurveyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Structured preference survey for BA/Travel/Daily goal-specific paths. */
@RestController
@RequestMapping("/api/learning/v2/goals/{goalCode}/survey")
@RequiredArgsConstructor
public class GoalSurveyController {
    private final GoalSurveyService service;

    @GetMapping
    public ResponseEntity<ApiResponse<GoalSurveyDefinition>> definition(@PathVariable String goalCode) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.definition(goalCode)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<GoalSurveySubmissionResponse>> submit(
            @PathVariable String goalCode,
            @Valid @RequestBody GoalSurveySubmissionRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Đã lưu nhu cầu học", service.submit(user.userId(), goalCode, request)));
    }
}
