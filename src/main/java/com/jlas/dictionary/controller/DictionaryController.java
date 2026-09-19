package com.jlas.dictionary.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.dictionary.dto.HandwritingRequest;
import com.jlas.dictionary.dto.KanjiResponse;
import com.jlas.dictionary.dto.SearchHistoryResponse;
import com.jlas.dictionary.dto.SearchResultResponse;
import com.jlas.dictionary.dto.VocabularyResponse;
import com.jlas.dictionary.handwriting.HandwritingRateLimiter;
import com.jlas.dictionary.service.DictionaryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller xử lý các API từ điển (Dictionary Search / Kanji Search / Vocabulary Search).
 * Base path: /api/dictionary. Đây cũng là API dùng cho Global Search (mục 10 JLAS2-34).
 */
@RestController
@RequestMapping("/api/dictionary")
@RequiredArgsConstructor
public class DictionaryController {
    private final DictionaryService dictionaryService;
    private final HandwritingRateLimiter handwritingRateLimiter;

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

    private Long id(UserPrincipal user) {
        return user == null ? null : user.userId();
    }
}
