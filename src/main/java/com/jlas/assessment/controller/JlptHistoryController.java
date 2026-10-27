package com.jlas.assessment.controller;

import com.jlas.assessment.dto.JlptHistoryEntryResponse;
import com.jlas.assessment.dto.JlptHistoryDetailResponse;
import com.jlas.assessment.dto.JlptHistoryPageResponse;
import com.jlas.assessment.dto.JlptHistorySubmitRequest;
import com.jlas.assessment.service.impl.JlptHistoryService;
import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/jlpt/history")
@Validated
public class JlptHistoryController {
    private final JlptHistoryService history;

    public JlptHistoryController(JlptHistoryService history) {
        this.history = history;
    }

    @GetMapping
    public ApiResponse<JlptHistoryPageResponse> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return ApiResponse.ok("Đã tải lịch sử làm bài JLPT.", history.list(userId(principal), page, size));
    }

    @PostMapping
    public ApiResponse<JlptHistoryEntryResponse> submit(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody JlptHistorySubmitRequest request) {
        return ApiResponse.ok("Đã lưu kết quả vào lịch sử làm bài JLPT.", history.save(userId(principal), request));
    }

    @GetMapping("/{historyId}")
    public ApiResponse<JlptHistoryDetailResponse> detail(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("historyId") long historyId) {
        return ApiResponse.ok("Đã tải chi tiết lần làm bài JLPT.", history.detail(userId(principal), historyId));
    }

    private static Long userId(UserPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để xem lịch sử làm bài.");
        }
        return principal.userId();
    }
}
