package com.jlas.learning.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.WeaknessNotebookResponse;
import com.jlas.learning.service.impl.WeaknessNotebookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/learning/notebook")
@RequiredArgsConstructor
public class WeaknessNotebookController {
    private final WeaknessNotebookService service;
    public record ReviewRequest(String status) {}
    @GetMapping
    public ResponseEntity<ApiResponse<WeaknessNotebookResponse>> list(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required = false) String skill, @RequestParam(required = false) String level,
            @RequestParam(required = false) String status, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "recent") String sort, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        return ResponseEntity.ok(ApiResponse.ok("OK", service.list(id(user), skill, level, status, q, sort, page, size)));
    }
    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<Integer>> sync(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật từ Smart Test gần nhất.", service.syncLatest(id(user))));
    }
    @PatchMapping("/{entryId}/status")
    public ResponseEntity<ApiResponse<Void>> review(@AuthenticationPrincipal UserPrincipal user, @PathVariable Long entryId,
            @RequestBody ReviewRequest request) {
        service.review(id(user), entryId, request.status());
        return ResponseEntity.ok(ApiResponse.ok("Đã cập nhật trạng thái ôn tập."));
    }
    private static Long id(UserPrincipal user) {
        if (user == null) throw new ApiException(ErrorCode.UNAUTHORIZED);
        return user.userId();
    }
}
