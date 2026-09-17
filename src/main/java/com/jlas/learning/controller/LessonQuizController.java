package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.dto.QuizResultResponse;
import com.jlas.learning.dto.QuizStartResponse;
import com.jlas.learning.dto.QuizSubmitRequest;
import com.jlas.learning.dto.QuizSubmitResponse;
import com.jlas.learning.service.LessonQuizService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST API for the lesson-quiz flow. Base path: /api/learning/quizzes */
@RestController
@RequestMapping("/api/learning/quizzes")
@RequiredArgsConstructor
public class LessonQuizController {
    private final LessonQuizService quizService;

    @PostMapping("/lessons/{lessonId}/start")
    public ResponseEntity<ApiResponse<QuizStartResponse>> start(
            @PathVariable("lessonId") Long lessonId,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("Quiz started", quizService.start(user.userId(), lessonId)));
    }

    @PostMapping("/attempts/{attemptId}/submit")
    public ResponseEntity<ApiResponse<QuizSubmitResponse>> submit(
            @PathVariable("attemptId") Long attemptId,
            @RequestBody QuizSubmitRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("Quiz submitted", quizService.submit(user.userId(), attemptId, request)));
    }

    @GetMapping("/attempts/{attemptId}/result")
    public ResponseEntity<ApiResponse<QuizResultResponse>> result(
            @PathVariable("attemptId") Long attemptId,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", quizService.result(user.userId(), attemptId)));
    }
}
