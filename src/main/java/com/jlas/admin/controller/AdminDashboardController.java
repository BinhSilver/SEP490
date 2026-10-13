package com.jlas.admin.controller;

import com.jlas.admin.dto.AdminPage;
import com.jlas.admin.dto.AdminPaymentView;
import com.jlas.admin.service.AdminDashboardService;
import com.jlas.admin.service.AdminPaymentService;
import com.jlas.auth.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/** System Admin dashboard and read-only payment lookup. */
@RestController
@RequestMapping("/api/admin/system")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminDashboardController {
    private final AdminDashboardService dashboard;
    private final AdminPaymentService payments;

    @GetMapping("/dashboard")
    public ApiResponse<AdminDashboardService.Dashboard> dashboard() {
        return ApiResponse.ok("Tổng quan hệ thống", dashboard.summary());
    }

    @GetMapping("/payments")
    public ApiResponse<AdminPage<AdminPaymentView>> payments(@RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok("Giao dịch thanh toán", AdminPage.from(payments.list(q, status, from, to, page, size)));
    }

    @GetMapping("/payments/{id}")
    public ApiResponse<AdminPaymentView> payment(@PathVariable Long id) {
        return ApiResponse.ok("Giao dịch thanh toán", payments.detail(id));
    }
}
