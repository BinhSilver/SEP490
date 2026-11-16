package com.jlas.admin.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.common.ai.AiCostReportService;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Chi phí AI theo tháng và giá CP gợi ý, để rà giá hằng tháng (kế hoạch Pro/CP, mục 2). */
@RestController
@RequestMapping("/api/admin/system/ai-cost")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminAiCostController {
    private final AiCostReportService reports;

    /** {@code month} như 2026-11; bỏ trống là tháng này. */
    @GetMapping
    public ApiResponse<AiCostReportService.Report> report(@RequestParam(required = false) String month) {
        YearMonth period;
        try {
            period = month == null || month.isBlank() ? YearMonth.now() : YearMonth.parse(month.trim());
        } catch (DateTimeParseException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tháng không hợp lệ, ví dụ 2026-11.");
        }
        return ApiResponse.ok("Chi phí AI", reports.report(period));
    }
}
