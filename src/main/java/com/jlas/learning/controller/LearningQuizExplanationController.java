package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.dto.QuizExplanationResponse;
import com.jlas.learning.service.LearningQuizExplanationService;
import com.jlas.learning.service.impl.InteractiveRateLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Optional explanation for an already-submitted activity quiz attempt. */
@RestController
@RequestMapping("/api/learning/v2/activities/{activityId}/attempts/{attemptId}/questions/{questionId}")
@RequiredArgsConstructor
public class LearningQuizExplanationController {
    private final LearningQuizExplanationService service;
    private final InteractiveRateLimitService rateLimit;

    @PostMapping("/explanation")
    public ResponseEntity<ApiResponse<QuizExplanationResponse>> explain(@PathVariable Long activityId,
            @PathVariable Long attemptId, @PathVariable Long questionId,
            @AuthenticationPrincipal UserPrincipal user) {
        rateLimit.check(user.userId(), InteractiveRateLimitService.Bucket.QUIZ_EXPLANATION);
        return ResponseEntity.ok(ApiResponse.ok("OK",
                service.explain(user.userId(), activityId, attemptId, questionId)));
    }
}
