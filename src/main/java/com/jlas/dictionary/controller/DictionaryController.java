package com.jlas.dictionary.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.dictionary.dto.HandwritingRequest;
import com.jlas.dictionary.dto.KanjiResponse;
import com.jlas.dictionary.dto.LessonContentResponse;
import com.jlas.dictionary.dto.LessonImportResult;
import com.jlas.dictionary.dto.LessonKanjiRequest;
import com.jlas.dictionary.dto.LessonSummaryResponse;
import com.jlas.dictionary.dto.SearchHistoryResponse;
import com.jlas.dictionary.dto.SearchResultResponse;
import com.jlas.dictionary.dto.AttachVocabularyRequest;
import com.jlas.dictionary.dto.DialogueLineResponse;
import com.jlas.dictionary.dto.DialogueLineUpsertRequest;
import com.jlas.dictionary.dto.DialogueRoleResponse;
import com.jlas.dictionary.dto.DialogueRoleUpsertRequest;
import com.jlas.dictionary.dto.DialogueSceneResponse;
import com.jlas.dictionary.dto.DialogueSceneUpsertRequest;
import com.jlas.dictionary.dto.ExampleSentenceResponse;
import com.jlas.dictionary.dto.ExampleSentenceUpsertRequest;
import com.jlas.dictionary.dto.GrammarPointResponse;
import com.jlas.dictionary.dto.GrammarPointUpsertRequest;
import com.jlas.dictionary.dto.LessonUpsertRequest;
import com.jlas.dictionary.dto.ReorderRequest;
import com.jlas.dictionary.dto.SectionResponse;
import com.jlas.dictionary.dto.SectionUpsertRequest;
import com.jlas.dictionary.dto.PagedResponse;
import com.jlas.dictionary.dto.TagResponse;
import com.jlas.dictionary.dto.VocabularyCreateRequest;
import com.jlas.dictionary.dto.VocabularySearchItem;
import com.jlas.dictionary.dto.TagUpsertRequest;
import com.jlas.dictionary.dto.TextbookResponse;
import com.jlas.dictionary.dto.TextbookUnitsResponse;
import com.jlas.dictionary.dto.TextbookUpsertRequest;
import com.jlas.dictionary.dto.TopicLessonsResponse;
import com.jlas.dictionary.dto.TopicResponse;
import com.jlas.dictionary.dto.UnitResponse;
import com.jlas.dictionary.dto.UnitUpsertRequest;
import com.jlas.dictionary.dto.UpdateStatusRequest;
import com.jlas.dictionary.dto.VocabularyResponse;
import com.jlas.dictionary.handwriting.HandwritingRateLimiter;
import com.jlas.dictionary.service.ContentAdminService;
import com.jlas.dictionary.service.DictionaryService;
import com.jlas.dictionary.service.DictionaryEngagementService;
import com.jlas.dictionary.dto.DictionaryFavoriteResponse;
import com.jlas.dictionary.dto.InlineLookupResponse;
import com.jlas.dictionary.service.LessonImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.data.domain.Page;

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
    private final ContentAdminService contentAdminService;
    private final LessonImportService lessonImportService;

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

    // ── Quản trị nội dung (CONTENT_CREATOR) ──────────────────────────────────
    // Mọi endpoint dưới đây đi qua ContentAdminService và LUÔN thấy cả bản ghi ARCHIVED, không có
    // tham số nào bật/tắt điều đó: cái gì thấy được do @PreAuthorize quyết, không do người gọi truyền vào.
    // SecurityConfig chỉ permitAll cho HttpMethod.GET trên /api/dictionary/**, nên các method ghi
    // dưới đây đã nằm trong anyRequest().authenticated() trước khi tới @PreAuthorize.

    /** GET /api/dictionary/manage/textbooks — danh sách giáo trình cho màn hình quản lý, kể cả đã lưu trữ. */
    @GetMapping("/manage/textbooks")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<List<TextbookResponse>>> listAllTextbooks() {
        return ResponseEntity.ok(ApiResponse.ok("OK", contentAdminService.listAllTextbooks()));
    }

    /** GET /api/dictionary/manage/textbooks/{id} — cây một giáo trình, kể cả unit và bài đã lưu trữ. */
    @GetMapping("/manage/textbooks/{id}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<TextbookUnitsResponse>> getTextbookTree(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok("OK", contentAdminService.getTextbookTree(id)));
    }

    /** POST /api/dictionary/textbooks — tạo giáo trình mới (luôn ở trạng thái DRAFT). */
    @PostMapping("/textbooks")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<TextbookResponse>> createTextbook(@Valid @RequestBody TextbookUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Đã tạo giáo trình", contentAdminService.createTextbook(request)));
    }

    /** PUT /api/dictionary/textbooks/{id} — sửa thông tin giáo trình; KHÔNG đổi trạng thái (dùng PATCH status). */
    @PutMapping("/textbooks/{id}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<TextbookResponse>> updateTextbook(@PathVariable Long id,
                                                                        @Valid @RequestBody TextbookUpsertRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật giáo trình",
                contentAdminService.updateTextbook(id, request)));
    }

    /**
     * POST /api/dictionary/textbooks/{id}/thumbnail — tải lên/thay ảnh thumbnail (multipart, field "file").
     * Chỉ JPG/PNG/WEBP ≤ 2MB, kiểm magic bytes; trả giáo trình đã cập nhật (data.thumbnailUrl).
     */
    @PostMapping(value = "/textbooks/{id}/thumbnail", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<TextbookResponse>> uploadTextbookThumbnail(@PathVariable Long id,
                                                                                 @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật ảnh thumbnail",
                contentAdminService.uploadTextbookThumbnail(id, file)));
    }

    /** DELETE /api/dictionary/textbooks/{id}/thumbnail — gỡ ảnh, giáo trình quay về thumbnail tự sinh. */
    @DeleteMapping("/textbooks/{id}/thumbnail")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<TextbookResponse>> removeTextbookThumbnail(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok("Đã gỡ ảnh thumbnail",
                contentAdminService.removeTextbookThumbnail(id)));
    }

    /** PUT /api/dictionary/textbooks/{id}/units/order — đánh số lại thứ tự unit theo danh sách id gửi lên. */
    @PutMapping("/textbooks/{id}/units/order")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<List<UnitResponse>>> reorderUnits(@PathVariable Long id,
                                                                        @Valid @RequestBody ReorderRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật thứ tự phần",
                contentAdminService.reorderUnits(id, request)));
    }

    /**
     * POST /api/dictionary/textbooks/{textbookId}/units — thêm phần vào cuối giáo trình.
     * Giáo trình cha nằm trên URL nên không có đường tạo unit mồ côi hay chuyển unit sang giáo trình khác.
     */
    @PostMapping("/textbooks/{textbookId}/units")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<UnitResponse>> createUnit(@PathVariable Long textbookId,
                                                                @Valid @RequestBody UnitUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Đã tạo phần", contentAdminService.createUnit(textbookId, request)));
    }

    /** PUT /api/dictionary/units/{id} — sửa tên/mô tả phần; không đổi được giáo trình cha. */
    @PutMapping("/units/{id}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<UnitResponse>> updateUnit(@PathVariable Long id,
                                                                @Valid @RequestBody UnitUpsertRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật phần", contentAdminService.updateUnit(id, request)));
    }

    /**
     * DELETE /api/dictionary/units/{id} — xoá cứng phần, chỉ khi phần đã rỗng (409 nếu còn bài).
     * Đây là endpoint xoá cứng DUY NHẤT của cây nội dung: giáo trình và bài học chỉ có xoá mềm
     * (PATCH status → ARCHIVED), vì mọi FK đều ON DELETE CASCADE và lesson_progress cascade theo lesson.
     */
    @DeleteMapping("/units/{id}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<Void>> deleteUnit(@PathVariable Long id) {
        contentAdminService.deleteUnit(id);
        return ResponseEntity.ok(ApiResponse.ok("Đã xoá phần", null));
    }

    /** PUT /api/dictionary/units/{id}/lessons/order — đánh số lại thứ tự bài trong một phần. */
    @PutMapping("/units/{id}/lessons/order")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<List<LessonSummaryResponse>>> reorderLessons(@PathVariable Long id,
                                                                                   @Valid @RequestBody ReorderRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật thứ tự bài học",
                contentAdminService.reorderLessons(id, request)));
    }

    /** POST /api/dictionary/units/{unitId}/lessons — thêm bài vào cuối một phần (luôn ở trạng thái DRAFT). */
    @PostMapping("/units/{unitId}/lessons")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<LessonSummaryResponse>> createLesson(@PathVariable Long unitId,
                                                                           @Valid @RequestBody LessonUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Đã tạo bài học", contentAdminService.createLesson(unitId, request)));
    }

    /** PUT /api/dictionary/lessons/{id} — sửa thông tin bài; không đổi được phần cha và không đổi trạng thái. */
    @PutMapping("/lessons/{id}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<LessonSummaryResponse>> updateLesson(@PathVariable Long id,
                                                                           @Valid @RequestBody LessonUpsertRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật bài học",
                contentAdminService.updateLesson(id, request)));
    }

    /**
     * DELETE /api/dictionary/lessons/{id} — xoá cứng bài học tạo nhầm.
     * Chỉ dành cho bài còn nháp, chưa có nội dung, chưa ai học và không gắn quiz; mọi trường hợp khác
     * trả 409 kèm lý do cụ thể, vì FK trỏ tới lesson đều CASCADE (tiến độ học, lộ trình, quiz).
     */
    @DeleteMapping("/lessons/{id}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<Void>> deleteLesson(@PathVariable Long id) {
        contentAdminService.deleteLesson(id);
        return ResponseEntity.ok(ApiResponse.ok("Đã xoá bài học", null));
    }

    // ── Phần nội dung trong bài (lesson_section) ─────────────────────────────
    // Cấp chứa của mọi nội dung bên trong bài: câu ví dụ, lời hội thoại và liên kết từ vựng đều là
    // content_item, mà content_item gắn vào section chứ không gắn thẳng vào lesson.

    /** GET /api/dictionary/manage/lessons/{lessonId}/sections — các phần nội dung của một bài. */
    @GetMapping("/manage/lessons/{lessonId}/sections")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<List<SectionResponse>>> listSections(@PathVariable Long lessonId) {
        return ResponseEntity.ok(ApiResponse.ok("OK", contentAdminService.listSections(lessonId)));
    }

    /** POST /api/dictionary/lessons/{lessonId}/sections — thêm phần nội dung vào cuối bài. */
    @PostMapping("/lessons/{lessonId}/sections")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<SectionResponse>> createSection(@PathVariable Long lessonId,
                                                                      @Valid @RequestBody SectionUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Đã tạo phần nội dung", contentAdminService.createSection(lessonId, request)));
    }

    /** PUT /api/dictionary/sections/{id} — sửa tên/loại phần nội dung; không đổi được bài cha. */
    @PutMapping("/sections/{id}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<SectionResponse>> updateSection(@PathVariable Long id,
                                                                      @Valid @RequestBody SectionUpsertRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật phần nội dung",
                contentAdminService.updateSection(id, request)));
    }

    /**
     * DELETE /api/dictionary/sections/{id} — xoá cứng phần nội dung, chỉ khi phần đã rỗng (409 nếu còn mục).
     * Cùng quy tắc với unit: cấp không có cột status thì chỉ xoá cứng khi đã rỗng.
     */
    @DeleteMapping("/sections/{id}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<Void>> deleteSection(@PathVariable Long id) {
        contentAdminService.deleteSection(id);
        return ResponseEntity.ok(ApiResponse.ok("Đã xoá phần nội dung", null));
    }

    /** PUT /api/dictionary/lessons/{lessonId}/sections/order — đánh số lại thứ tự phần nội dung. */
    @PutMapping("/lessons/{lessonId}/sections/order")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<List<SectionResponse>>> reorderSections(@PathVariable Long lessonId,
                                                                              @Valid @RequestBody ReorderRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật thứ tự phần nội dung",
                contentAdminService.reorderSections(lessonId, request)));
    }

    // ── Ngữ pháp (grammar_point + câu ví dụ) ─────────────────────────────────
    // grammar_point gắn thẳng vào lesson; câu ví dụ là content_item loại GRAMMAR trỏ về grammar_point_id
    // và nằm trong một section (service tự chọn/tạo section EXAMPLE nếu không chỉ định).

    /** GET /api/dictionary/manage/lessons/{lessonId}/grammar-points — mẫu ngữ pháp của một bài. */
    @GetMapping("/manage/lessons/{lessonId}/grammar-points")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<List<GrammarPointResponse>>> listGrammarPoints(@PathVariable Long lessonId) {
        return ResponseEntity.ok(ApiResponse.ok("OK", contentAdminService.listGrammarPoints(lessonId)));
    }

    /** POST /api/dictionary/lessons/{lessonId}/grammar-points — thêm mẫu ngữ pháp vào cuối bài. */
    @PostMapping("/lessons/{lessonId}/grammar-points")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<GrammarPointResponse>> createGrammarPoint(@PathVariable Long lessonId,
                                                                                @Valid @RequestBody GrammarPointUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Đã tạo mẫu ngữ pháp",
                        contentAdminService.createGrammarPoint(lessonId, request)));
    }

    /** PUT /api/dictionary/grammar-points/{id} — sửa mẫu ngữ pháp; không đổi được bài cha. */
    @PutMapping("/grammar-points/{id}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<GrammarPointResponse>> updateGrammarPoint(@PathVariable Long id,
                                                                                @Valid @RequestBody GrammarPointUpsertRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật mẫu ngữ pháp",
                contentAdminService.updateGrammarPoint(id, request)));
    }

    /**
     * DELETE /api/dictionary/grammar-points/{id} — xoá cứng mẫu ngữ pháp, 409 nếu còn câu ví dụ.
     * Chặn thay vì xoá kèm vì fk_content_item_grammar là ON DELETE SET NULL: xoá thẳng sẽ khiến câu ví dụ
     * mất liên kết trong im lặng chứ không báo lỗi.
     */
    @DeleteMapping("/grammar-points/{id}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<Void>> deleteGrammarPoint(@PathVariable Long id) {
        contentAdminService.deleteGrammarPoint(id);
        return ResponseEntity.ok(ApiResponse.ok("Đã xoá mẫu ngữ pháp", null));
    }

    /** PUT /api/dictionary/lessons/{lessonId}/grammar-points/order — đánh số lại thứ tự mẫu ngữ pháp. */
    @PutMapping("/lessons/{lessonId}/grammar-points/order")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<List<GrammarPointResponse>>> reorderGrammarPoints(@PathVariable Long lessonId,
                                                                                        @Valid @RequestBody ReorderRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật thứ tự mẫu ngữ pháp",
                contentAdminService.reorderGrammarPoints(lessonId, request)));
    }

    /** GET /api/dictionary/manage/grammar-points/{id}/examples — câu ví dụ của một mẫu. */
    @GetMapping("/manage/grammar-points/{id}/examples")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<List<ExampleSentenceResponse>>> listExamples(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok("OK", contentAdminService.listExamples(id)));
    }

    /** POST /api/dictionary/grammar-points/{id}/examples — thêm câu ví dụ cho một mẫu. */
    @PostMapping("/grammar-points/{id}/examples")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<ExampleSentenceResponse>> createExample(@PathVariable Long id,
                                                                              @Valid @RequestBody ExampleSentenceUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Đã tạo câu ví dụ", contentAdminService.createExample(id, request)));
    }

    /** PUT /api/dictionary/examples/{id} — sửa câu ví dụ; không đổi được mẫu cha hay phần chứa. */
    @PutMapping("/examples/{id}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<ExampleSentenceResponse>> updateExample(@PathVariable Long id,
                                                                              @Valid @RequestBody ExampleSentenceUpsertRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật câu ví dụ",
                contentAdminService.updateExample(id, request)));
    }

    /** DELETE /api/dictionary/examples/{id} — xoá cứng câu ví dụ (mục lá, không có chốt chặn). */
    @DeleteMapping("/examples/{id}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<Void>> deleteExample(@PathVariable Long id) {
        contentAdminService.deleteExample(id);
        return ResponseEntity.ok(ApiResponse.ok("Đã xoá câu ví dụ", null));
    }

    // ── Từ vựng của bài (content_vocabulary) ─────────────────────────────────
    // Chỉ tìm và gắn từ ĐÃ CÓ. Không có endpoint tạo từ mới: vocabulary_entry dùng chung toàn hệ thống
    // và cần hàm chuẩn hoá riêng cho uk_vocabulary_normalized.

    /**
     * GET /api/dictionary/manage/vocabulary/search — tìm từ để gắn vào bài, có phân trang.
     * Truyền lessonId để biết từ nào đã có trong bài đang soạn.
     */
    @GetMapping("/manage/vocabulary/search")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<PagedResponse<VocabularySearchItem>>> searchVocabulary(
            @RequestParam(value = "q", required = false) String keyword,
            @RequestParam(value = "lessonId", required = false) Long lessonId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok("OK",
                contentAdminService.searchVocabulary(keyword, lessonId, page, size)));
    }

    /** GET /api/dictionary/manage/lessons/{lessonId}/vocabulary — từ vựng hiện có trong bài. */
    @GetMapping("/manage/lessons/{lessonId}/vocabulary")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<List<VocabularySearchItem>>> listLessonVocabulary(@PathVariable Long lessonId) {
        return ResponseEntity.ok(ApiResponse.ok("OK", contentAdminService.listLessonVocabulary(lessonId)));
    }

    /** POST /api/dictionary/lessons/{lessonId}/vocabulary — gắn một từ có sẵn vào bài. */
    @PostMapping("/lessons/{lessonId}/vocabulary")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<VocabularySearchItem>> attachVocabulary(@PathVariable Long lessonId,
                                                                              @Valid @RequestBody AttachVocabularyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Đã thêm từ vào bài học",
                        contentAdminService.attachVocabulary(lessonId, request)));
    }

    /**
     * POST /api/dictionary/lessons/{lessonId}/vocabulary/new — tạo từ mới (DRAFT, phạm vi TEXTBOOK) rồi gắn
     * vào bài. Từ điển đã có cùng cặp cách viết/cách đọc thì gắn từ có sẵn, không tạo bản trùng.
     */
    @PostMapping("/lessons/{lessonId}/vocabulary/new")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<VocabularySearchItem>> createVocabulary(@PathVariable Long lessonId,
                                                                              @Valid @RequestBody VocabularyCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Đã thêm từ vào bài học",
                        contentAdminService.createVocabulary(lessonId, request)));
    }

    /**
     * DELETE /api/dictionary/lessons/{lessonId}/vocabulary/{vocabularyId} — gỡ từ khỏi bài.
     * Chỉ xoá dòng nối content_vocabulary; từ vẫn còn nguyên trong từ điển và ở các bài khác.
     */
    @DeleteMapping("/lessons/{lessonId}/vocabulary/{vocabularyId}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<Void>> detachVocabulary(@PathVariable Long lessonId,
                                                              @PathVariable Long vocabularyId) {
        contentAdminService.detachVocabulary(lessonId, vocabularyId);
        return ResponseEntity.ok(ApiResponse.ok("Đã gỡ từ khỏi bài học", null));
    }

    // ── Import nội dung bài học từ Excel ─────────────────────────────────────

    /**
     * POST /api/dictionary/lessons/{lessonId}/import — nhập từ vựng, ngữ pháp + ví dụ, hội thoại, kanji từ
     * file .xlsx (multipart, field "file"). Tất cả hoặc không: file có dòng lỗi thì không lưu gì và trả
     * {@code imported = false} kèm danh sách lỗi theo sheet/dòng.
     */
    @PostMapping(value = "/lessons/{lessonId}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<LessonImportResult>> importLesson(@PathVariable Long lessonId,
                                                                        @RequestParam("file") MultipartFile file) {
        LessonImportResult result = lessonImportService.importLesson(lessonId, file);
        return ResponseEntity.ok(ApiResponse.ok(
                result.isImported() ? "Đã nhập nội dung từ file" : "File có lỗi, chưa nhập dữ liệu nào",
                result));
    }

    /** GET /api/dictionary/manage/lessons/import-template — tải file Excel mẫu để nhập nội dung bài học. */
    @GetMapping("/manage/lessons/import-template")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<byte[]> downloadImportTemplate() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("mau-noi-dung-bai-hoc.xlsx", StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(lessonImportService.buildTemplate());
    }

    // ── Kanji gắn tay vào bài ────────────────────────────────────────────────

    /** GET /api/dictionary/manage/lessons/{lessonId}/kanji — kanji gắn tay của bài (không gồm kanji suy ra từ từ vựng). */
    @GetMapping("/manage/lessons/{lessonId}/kanji")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<List<KanjiResponse>>> listLessonKanji(@PathVariable Long lessonId) {
        return ResponseEntity.ok(ApiResponse.ok("OK", contentAdminService.listLessonKanji(lessonId)));
    }

    /** POST /api/dictionary/lessons/{lessonId}/kanji — gắn các chữ Kanji trong chuỗi vào cuối danh sách của bài. */
    @PostMapping("/lessons/{lessonId}/kanji")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<List<KanjiResponse>>> attachLessonKanji(@PathVariable Long lessonId,
                                                                             @Valid @RequestBody LessonKanjiRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Đã gắn kanji vào bài học",
                        contentAdminService.attachLessonKanji(lessonId, request)));
    }

    /** DELETE /api/dictionary/lessons/{lessonId}/kanji/{kanjiId} — gỡ một kanji gắn tay; chữ vẫn còn trong từ điển. */
    @DeleteMapping("/lessons/{lessonId}/kanji/{kanjiId}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<Void>> detachLessonKanji(@PathVariable Long lessonId,
                                                               @PathVariable Long kanjiId) {
        contentAdminService.detachLessonKanji(lessonId, kanjiId);
        return ResponseEntity.ok(ApiResponse.ok("Đã gỡ kanji khỏi bài học", null));
    }

    // ── Hội thoại ────────────────────────────────────────────────────────────
    // Mỗi ĐOẠN hội thoại là một lesson_section EXAMPLE title "Hội thoại: …"; mỗi LỜI là content_item
    // LISTENING kèm dialogue_line_detail và các dialogue_line_chunk. Cụm gửi kèm lời trong cùng request
    // vì ghép cụm phải ra đúng nguyên câu — kiểm được trên cả bộ, không kiểm được từng phần.

    /** GET /api/dictionary/manage/lessons/{lessonId}/dialogue-roles — vai hội thoại của bài. */
    @GetMapping("/manage/lessons/{lessonId}/dialogue-roles")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<List<DialogueRoleResponse>>> listDialogueRoles(@PathVariable Long lessonId) {
        return ResponseEntity.ok(ApiResponse.ok("OK", contentAdminService.listDialogueRoles(lessonId)));
    }

    /** POST /api/dictionary/lessons/{lessonId}/dialogue-roles — thêm vai; 409 nếu mã vai trùng trong bài. */
    @PostMapping("/lessons/{lessonId}/dialogue-roles")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<DialogueRoleResponse>> createDialogueRole(@PathVariable Long lessonId,
                                                                                @Valid @RequestBody DialogueRoleUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Đã tạo vai hội thoại",
                        contentAdminService.createDialogueRole(lessonId, request)));
    }

    /** PUT /api/dictionary/dialogue-roles/{id} — sửa vai. */
    @PutMapping("/dialogue-roles/{id}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<DialogueRoleResponse>> updateDialogueRole(@PathVariable Long id,
                                                                                @Valid @RequestBody DialogueRoleUpsertRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật vai hội thoại",
                contentAdminService.updateDialogueRole(id, request)));
    }

    /** DELETE /api/dictionary/dialogue-roles/{id} — xoá vai, 409 nếu vai còn lời thoại. */
    @DeleteMapping("/dialogue-roles/{id}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<Void>> deleteDialogueRole(@PathVariable Long id) {
        contentAdminService.deleteDialogueRole(id);
        return ResponseEntity.ok(ApiResponse.ok("Đã xoá vai hội thoại", null));
    }

    /** GET /api/dictionary/manage/lessons/{lessonId}/dialogue-scenes — các đoạn hội thoại kèm lời và cụm. */
    @GetMapping("/manage/lessons/{lessonId}/dialogue-scenes")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<List<DialogueSceneResponse>>> listDialogueScenes(@PathVariable Long lessonId) {
        return ResponseEntity.ok(ApiResponse.ok("OK", contentAdminService.listDialogueScenes(lessonId)));
    }

    /** POST /api/dictionary/lessons/{lessonId}/dialogue-scenes — tạo đoạn hội thoại mới. */
    @PostMapping("/lessons/{lessonId}/dialogue-scenes")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<DialogueSceneResponse>> createDialogueScene(@PathVariable Long lessonId,
                                                                                  @Valid @RequestBody DialogueSceneUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Đã tạo đoạn hội thoại",
                        contentAdminService.createDialogueScene(lessonId, request)));
    }

    /**
     * POST /api/dictionary/dialogue-scenes/{sectionId}/lines — thêm lời vào cuối đoạn.
     * 400 nếu ghép các cụm không ra đúng lời thoại, kèm cả hai chuỗi để thấy lệch ở đâu.
     */
    @PostMapping("/dialogue-scenes/{sectionId}/lines")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<DialogueLineResponse>> createDialogueLine(@PathVariable Long sectionId,
                                                                                @Valid @RequestBody DialogueLineUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Đã tạo lời thoại",
                        contentAdminService.createDialogueLine(sectionId, request)));
    }

    /** PUT /api/dictionary/dialogue-lines/{id} — sửa lời thoại; cụm bị ghi đè toàn bộ. */
    @PutMapping("/dialogue-lines/{id}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<DialogueLineResponse>> updateDialogueLine(@PathVariable Long id,
                                                                                @Valid @RequestBody DialogueLineUpsertRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật lời thoại",
                contentAdminService.updateDialogueLine(id, request)));
    }

    /** DELETE /api/dictionary/dialogue-lines/{id} — xoá lời thoại (detail và cụm cascade theo). */
    @DeleteMapping("/dialogue-lines/{id}")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<Void>> deleteDialogueLine(@PathVariable Long id) {
        contentAdminService.deleteDialogueLine(id);
        return ResponseEntity.ok(ApiResponse.ok("Đã xoá lời thoại", null));
    }

    /** PUT /api/dictionary/dialogue-scenes/{sectionId}/lines/order — đánh số lại thứ tự lời. */
    @PutMapping("/dialogue-scenes/{sectionId}/lines/order")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<List<DialogueLineResponse>>> reorderDialogueLines(@PathVariable Long sectionId,
                                                                                         @Valid @RequestBody ReorderRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật thứ tự lời thoại",
                contentAdminService.reorderDialogueLines(sectionId, request)));
    }

    /** GET /api/dictionary/manage/tags?type=KNOWLEDGE — tag theo loại để đổ dropdown ở form bài học. */
    @GetMapping("/manage/tags")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<List<TagResponse>>> listTags(@RequestParam("type") String type) {
        return ResponseEntity.ok(ApiResponse.ok("OK", contentAdminService.listTagsByType(type)));
    }

    /** POST /api/dictionary/manage/tags — tạo tag mới ngay tại form bài học. */
    @PostMapping("/manage/tags")
    @PreAuthorize("hasRole('CONTENT_CREATOR')")
    public ResponseEntity<ApiResponse<TagResponse>> createTag(@Valid @RequestBody TagUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Đã tạo điểm kiến thức", contentAdminService.createTag(request)));
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
