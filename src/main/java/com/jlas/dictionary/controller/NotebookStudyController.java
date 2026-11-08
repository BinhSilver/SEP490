package com.jlas.dictionary.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.service.NotebookStudyService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dictionary/notebooks/{notebookId}/study")
@RequiredArgsConstructor
public class NotebookStudyController {
    private final NotebookStudyService service;
    public record RatingRequest(String rating) {}
    public record DailyNewLimitRequest(Integer dailyNewLimit) {}

    @GetMapping
    public ApiResponse<NotebookStudyService.Session> next(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long notebookId) {
        return ApiResponse.ok("OK", service.next(userId(user), notebookId));
    }

    @PutMapping("/settings")
    public ApiResponse<Integer> settings(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long notebookId, @RequestBody DailyNewLimitRequest request) {
        if (request == null || request.dailyNewLimit() == null)
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chưa chọn mục tiêu từ mới.");
        return ApiResponse.ok("Đã cập nhật mục tiêu từ mới", service.updateDailyNewLimit(
                userId(user), notebookId, request.dailyNewLimit()));
    }

    @PostMapping("/{vocabularyId}")
    public ApiResponse<NotebookStudyService.Schedule> rate(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long notebookId, @PathVariable Long vocabularyId, @RequestBody RatingRequest request) {
        return ApiResponse.ok("Đã cập nhật lịch ôn", service.rate(userId(user), notebookId, vocabularyId, request == null ? null : request.rating()));
    }

    @PostMapping("/kanji/{character}")
    public ApiResponse<NotebookStudyService.Schedule> rateKanji(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long notebookId, @PathVariable String character, @RequestBody RatingRequest request) {
        return ApiResponse.ok("Đã cập nhật lịch ôn Kanji", service.rateKanji(userId(user), notebookId, character,
                request == null ? null : request.rating()));
    }

    private static Long userId(UserPrincipal user) {
        if (user == null) throw new ApiException(ErrorCode.UNAUTHORIZED, "Bạn cần đăng nhập.");
        return user.userId();
    }
}
