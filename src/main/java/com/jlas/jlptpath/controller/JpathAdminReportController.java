package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.jlptpath.dto.JpathAdminReportView;
import com.jlas.jlptpath.service.JpathAdminReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** [JLAS-W0108] System admin report of the JLPT path (ADMIN only; /api/admin/system/** is ADMIN in SecurityConfig). */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class JpathAdminReportController {

    private final JpathAdminReportService reportService;

    @GetMapping("/api/admin/system/learning-path-report")
    public ResponseEntity<ApiResponse<JpathAdminReportView>> report() {
        return ResponseEntity.ok(ApiResponse.ok("OK", reportService.report()));
    }
}
