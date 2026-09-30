package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.LessonContentResponse;
import com.jlas.learning.service.LessonContentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST API for published lesson content. Base path: /api/learning/lessons */
@RestController
@RequestMapping("/api/learning/lessons")
@RequiredArgsConstructor
public class LessonContentController {
    private final LessonContentService lessonContentService;

    @GetMapping("/{lessonId}")
    public ResponseEntity<ApiResponse<LessonContentResponse>> content(@PathVariable("lessonId") Long lessonId) {
        return lessonContentService.findPublished(lessonId)
                .map(data -> ResponseEntity.ok(ApiResponse.ok("OK", data)))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Lesson not found."));
    }
}
