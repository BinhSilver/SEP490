package com.jlas.dictionary.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.dictionary.dto.LessonPageImagesResponse;
import com.jlas.dictionary.service.LessonPageImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [JLAS2-17] Ảnh trang từ vựng / ngữ pháp / kanji trên trang học. GET công khai (nằm trong GET /api/dictionary/**
 * permitAll); service lọc theo quyền xem bài (learner chỉ thấy bài đã công bố).
 */
@RestController
@RequestMapping("/api/dictionary")
@RequiredArgsConstructor
public class LessonPageImageController {

    private final LessonPageImageService lessonPageImageService;

    /** GET /api/dictionary/lessons/{lessonId}/page-images — ảnh trang của bài theo tab (VOCAB / GRAMMAR / KANJI). */
    @GetMapping("/lessons/{lessonId}/page-images")
    public ResponseEntity<ApiResponse<LessonPageImagesResponse>> list(@PathVariable Long lessonId) {
        return ResponseEntity.ok(ApiResponse.ok("OK", lessonPageImageService.listForLesson(lessonId)));
    }
}
