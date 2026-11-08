package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.dto.JpathReportRequest;
import com.jlas.jlptpath.dto.JpathReportResponse;
import com.jlas.jlptpath.service.JpathReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** [JLAS-W0108] "Báo sai": a signed-in learner reports a content error of the JLPT path (not public). */
@RestController
@RequestMapping("/api/learning/jlpt/reports")
@RequiredArgsConstructor
public class JpathReportController {

    private final JpathReportService reportService;

    @PostMapping
    public ResponseEntity<ApiResponse<JpathReportResponse>> report(@AuthenticationPrincipal UserPrincipal user,
            @Valid @RequestBody JpathReportRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã gửi báo lỗi", reportService.report(user.userId(), request)));
    }
}
