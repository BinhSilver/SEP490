package com.jlas.shadowing.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.shadowing.service.ShadowingReportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
public class VideoReportController {
    private final ShadowingReportService reportService;

    public record ReportRequest(@NotBlank String reason, @Size(max = 500) String detail) { }

    @PostMapping("/{id}/report")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> report(@PathVariable Long id,
                                                                    @Valid @RequestBody ReportRequest request) {
        boolean hidden = reportService.report(id, request.reason(), request.detail());
        return ResponseEntity.ok(ApiResponse.ok("Đã gửi báo cáo. Cảm ơn bạn, quản trị viên sẽ xem xét.",
                Map.of("hidden", hidden)));
    }
}
