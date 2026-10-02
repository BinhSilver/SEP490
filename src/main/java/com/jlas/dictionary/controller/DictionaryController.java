package com.jlas.dictionary.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.dictionary.dto.HandwritingRequest;
import com.jlas.dictionary.dto.KanjiResponse;
import com.jlas.dictionary.dto.LessonContentResponse;
import com.jlas.dictionary.dto.LessonSummaryResponse;
import com.jlas.dictionary.dto.SearchHistoryResponse;
import com.jlas.dictionary.dto.SearchResultResponse;
import com.jlas.dictionary.dto.TextbookResponse;
import com.jlas.dictionary.dto.TextbookUnitsResponse;
import com.jlas.dictionary.dto.TopicLessonsResponse;
import com.jlas.dictionary.dto.TopicResponse;
import com.jlas.dictionary.dto.UpdateStatusRequest;
import com.jlas.dictionary.dto.VocabularyResponse;
import com.jlas.dictionary.handwriting.HandwritingRateLimiter;
import com.jlas.dictionary.service.DictionaryService;
import com.jlas.dictionary.service.DictionaryEngagementService;
import com.jlas.dictionary.dto.DictionaryFavoriteResponse;
import com.jlas.dictionary.dto.InlineLookupResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;

/**
 * Controller xử lý các API từ điển (Dictionary Search / Kanji Search / Vocabulary Search — cũng là API
 * dùng cho Global Search, mục 10 JLAS2-34) và duyệt sách đã số hoá.
 * Base path: /api/dictionary
 * <p>
 * GET /api/dictionary/** đang permitAll trong SecurityConfig; quyền xem DRAFT/PUBLISHED của nội dung
 * giáo trình do service quyết định theo role của người gọi (CONTENT_CREATOR thấy tất cả, còn lại — kể cả
 * ADMIN — chỉ PUBLISHED).
 */
@RestController
@RequestMapping("/api/dictionary")
@RequiredArgsConstructor
public class DictionaryController {
    private final DictionaryService dictionaryService;
    private final HandwritingRateLimiter handwritingRateLimiter;
    private final DictionaryEngagementService engagementService;

    // ---------------------------------------------------------------------
    // Tra cứu từ điển
    // ---------------------------------------------------------------------

    @GetMapping("/search")
    public ApiResponse<SearchResultResponse> search(
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", dictionaryService.search(query, page, size, id(user)));
    }

    @GetMapping("/vocabulary/{id}")
    public ApiResponse<VocabularyResponse> vocabularyDetail(@PathVariable Long id) {
        return ApiResponse.ok("OK", dictionaryService.getVocabularyDetail(id));
    }

    @GetMapping("/kanji/{character}")
    public ApiResponse<KanjiResponse> kanjiDetail(@PathVariable String character) {
        return ApiResponse.ok("OK", dictionaryService.getKanjiDetail(character));
    }

    /** Nhận diện Kanji vẽ tay theo thời gian thực; FE gọi sau mỗi nét (đã debounce), không gọi theo mousemove. */
    @PostMapping("/handwriting/recognize")
    public ApiResponse<List<KanjiResponse>> recognizeHandwriting(
            @Valid @RequestBody HandwritingRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        handwritingRateLimiter.check(id(user));
        return ApiResponse.ok("OK", dictionaryService.recognizeHandwriting(request));
    }

    @GetMapping("/history")
    public ApiResponse<List<SearchHistoryResponse>> history(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", dictionaryService.getRecentHistory(id(user)));
    }

    @GetMapping("/history/page")
    public ApiResponse<Page<SearchHistoryResponse>> historyPage(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", engagementService.history(requiredId(user), page, size));
    }

    @GetMapping("/inline")
    public ApiResponse<InlineLookupResponse> inlineLookup(@RequestParam("q") String query,
            @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", engagementService.inlineLookup(query, id(user)));
    }

    @GetMapping("/favorites")
    public ApiResponse<Page<DictionaryFavoriteResponse>> favorites(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", engagementService.favorites(requiredId(user), page, size));
    }

    @PostMapping("/favorites/{vocabularyId}")
    public ResponseEntity<ApiResponse<DictionaryFavoriteResponse>> saveFavorite(@PathVariable Long vocabularyId,
            @AuthenticationPrincipal UserPrincipal user) {
        var result = engagementService.saveFavorite(requiredId(user), vocabularyId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(result.created() ? "Đã lưu từ" : "Từ đã được lưu", result));
    }

    @DeleteMapping("/favorites/{vocabularyId}")
    public ApiResponse<Void> deleteFavorite(@PathVariable Long vocabularyId,
            @AuthenticationPrincipal UserPrincipal user) {
        engagementService.deleteFavorite(requiredId(user), vocabularyId);
        return ApiResponse.ok("Đã bỏ lưu từ");
    }

    // ---------------------------------------------------------------------
    // Duyệt giáo trình đã số hoá
    // ---------------------------------------------------------------------

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

    private Long id(UserPrincipal user) {
        return user == null ? null : user.userId();
    }

    private Long requiredId(UserPrincipal user) {
        if (user == null) throw new com.jlas.common.exception.ApiException(
                com.jlas.common.exception.ErrorCode.UNAUTHORIZED, "Bạn cần đăng nhập.");
        return user.userId();
    }
}
