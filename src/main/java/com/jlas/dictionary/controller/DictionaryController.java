package com.jlas.dictionary.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.dictionary.dto.LessonContentResponse;
import com.jlas.dictionary.dto.LessonSummaryResponse;
import com.jlas.dictionary.dto.TextbookResponse;
import com.jlas.dictionary.dto.TextbookUnitsResponse;
import com.jlas.dictionary.dto.TopicLessonsResponse;
import com.jlas.dictionary.dto.TopicResponse;
import com.jlas.dictionary.dto.UpdateStatusRequest;
import com.jlas.dictionary.service.DictionaryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controller xử lý các API từ điển và duyệt sách đã số hoá.
 * Base path: /api/dictionary
 * <p>
 * GET /api/dictionary/** đang permitAll trong SecurityConfig; quyền xem DRAFT/PUBLISHED
 * do service quyết định theo role của người gọi (CONTENT_CREATOR thấy tất cả, còn lại — kể cả ADMIN — chỉ PUBLISHED).
 */
@RestController
@RequestMapping("/api/dictionary")
@RequiredArgsConstructor
public class DictionaryController {
    private final DictionaryService dictionaryService;

    /** GET /api/dictionary/textbooks — danh sách giáo trình kèm jlpt_level và số unit. */
    @GetMapping("/textbooks")
    public ResponseEntity<ApiResponse<List<TextbookResponse>>> listTextbooks() {
        return ResponseEntity.ok(ApiResponse.ok("OK", dictionaryService.listTextbooks()));
    }

    /** GET /api/dictionary/textbooks/{id}/units — unit và bài học của một giáo trình. */
    @GetMapping("/textbooks/{id}/units")
    public ResponseEntity<ApiResponse<TextbookUnitsResponse>> getTextbookUnits(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok("OK", dictionaryService.getTextbookUnits(id)));
    }

    /** GET /api/dictionary/topics — chủ đề học (tag SITUATION) kèm số bài được xem; chủ đề 0 bài vẫn có mặt. */
    @GetMapping("/topics")
    public ResponseEntity<ApiResponse<List<TopicResponse>>> listTopics() {
        return ResponseEntity.ok(ApiResponse.ok("OK", dictionaryService.listTopics()));
    }

    /** GET /api/dictionary/topics/{code}/lessons — bài của một chủ đề; tag không tồn tại → 404, có tag mà 0 bài → 200 rỗng. */
    @GetMapping("/topics/{code}/lessons")
    public ResponseEntity<ApiResponse<TopicLessonsResponse>> getTopicLessons(@PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.ok("OK", dictionaryService.getTopicLessons(code)));
    }

    /**
     * PATCH /api/dictionary/textbooks/{id}/status — CONTENT_CREATOR đổi trạng thái duyệt giáo trình.
     * Chặn quyền bằng @PreAuthorize (chặn từ cửa, không dựa vào CurrentUser như phần đọc): learner → 403.
     */
    @PatchMapping("/textbooks/{id}/status")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<TextbookResponse>> updateTextbookStatus(@PathVariable Long id,
                                                                              @Valid @RequestBody UpdateStatusRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật trạng thái giáo trình",
                dictionaryService.updateTextbookStatus(id, request.getStatus())));
    }

    /** PATCH /api/dictionary/lessons/{id}/status — CONTENT_CREATOR đổi trạng thái duyệt bài học; 409 nếu giáo trình chưa công bố. */
    @PatchMapping("/lessons/{id}/status")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<LessonSummaryResponse>> updateLessonStatus(@PathVariable Long id,
                                                                                @Valid @RequestBody UpdateStatusRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật trạng thái bài học",
                dictionaryService.updateLessonStatus(id, request.getStatus())));
    }

    /** GET /api/dictionary/lessons/{id}/content — nội dung đầy đủ một bài học. */
    @GetMapping("/lessons/{id}/content")
    public ResponseEntity<ApiResponse<LessonContentResponse>> getLessonContent(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok("OK", dictionaryService.getLessonContent(id)));
    }
}
