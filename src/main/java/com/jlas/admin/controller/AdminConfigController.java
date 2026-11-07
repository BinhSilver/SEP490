package com.jlas.admin.controller;

import com.jlas.admin.service.SystemConfigService;
import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** [JLAS2-51] Runtime settings and the read-only integration status. Outcomes: see {@link SystemConfigService}. */
@RestController
@RequestMapping("/api/admin/system/configs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminConfigController {
    private final SystemConfigService configs;

    /** {@code value}: a JSON string, number or boolean; {@code version}: the one returned by the list. */
    public record UpdateRequest(Object value, Long version, String reason) { }

    public record ResetRequest(String reason, Long version) { }

    @GetMapping
    public ApiResponse<List<SystemConfigService.ConfigView>> list() {
        return ApiResponse.ok("Cấu hình hệ thống", configs.list());
    }

    @GetMapping("/integrations")
    public ApiResponse<List<SystemConfigService.IntegrationView>> integrations() {
        return ApiResponse.ok("Tích hợp", configs.integrations());
    }

    @PutMapping("/{key}")
    public ApiResponse<SystemConfigService.ConfigView> update(@PathVariable String key,
            @RequestBody UpdateRequest request, @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã lưu cấu hình",
                configs.update(actor.userId(), key, request.value(), request.version(), request.reason()));
    }

    @PostMapping("/{key}/reset")
    public ApiResponse<SystemConfigService.ConfigView> reset(@PathVariable String key,
            @RequestBody ResetRequest request, @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã khôi phục mặc định",
                configs.reset(actor.userId(), key, request.version(), request.reason()));
    }
}
