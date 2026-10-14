package com.jlas.admin.controller;

import com.jlas.admin.dto.AdminAuditLogView;
import com.jlas.admin.dto.AdminPage;
import com.jlas.admin.service.AdminAuditService;
import com.jlas.auth.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/admin/system/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminAuditController {
    private final AdminAuditService audit;

    @GetMapping
    public ApiResponse<AdminPage<AdminAuditLogView>> list(@RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action, @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId, @RequestParam(required = false) String result,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok("Nhật ký quản trị",
                AdminPage.from(audit.list(actorId, action, targetType, targetId, result, from, to, page, size)));
    }
}
