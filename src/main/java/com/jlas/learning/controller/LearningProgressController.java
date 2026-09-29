package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.dto.LessonProgressResponse;
import com.jlas.learning.service.LearningProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST API for the current user's lesson progress. Base path: /api/learning/progress */
@RestController
@RequestMapping("/api/learning/progress")
@RequiredArgsConstructor
public class LearningProgressController {
    private final LearningProgressService progressService;

    @GetMapping("/lessons/{lessonId}")
    public ResponseEntity<ApiResponse<LessonProgressResponse>> status(
            @PathVariable("lessonId") Long lessonId,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", progressService.status(user.userId(), lessonId)));
    }

    @PostMapping("/lessons/{lessonId}/start")
    public ResponseEntity<ApiResponse<LessonProgressResponse>> start(
            @PathVariable("lessonId") Long lessonId,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("Lesson started", progressService.start(user.userId(), lessonId)));
    }

    @PostMapping("/lessons/{lessonId}/complete")
    public ResponseEntity<ApiResponse<LessonProgressResponse>> complete(
            @PathVariable("lessonId") Long lessonId,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("Lesson completed", progressService.complete(user.userId(), lessonId)));
    }
}
