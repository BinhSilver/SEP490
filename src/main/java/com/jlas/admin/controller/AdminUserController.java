package com.jlas.admin.controller;

import com.jlas.admin.dto.AdminReasonRequest;
import com.jlas.admin.dto.AdminPage;
import com.jlas.admin.dto.AdminUserView;
import com.jlas.admin.service.AdminUserService;
import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/system/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminUserController {
    private final AdminUserService users;

    @GetMapping
    public ApiResponse<AdminPage<AdminUserView>> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) String role, @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean premium, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok("Danh sách người dùng", AdminPage.from(users.list(q, role, status, premium, page, size)));
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminUserView> detail(@PathVariable Long id) {
        return ApiResponse.ok("Thông tin người dùng", users.detail(id));
    }

    @PostMapping("/{id}/lock")
    public ApiResponse<AdminUserView> lock(@PathVariable Long id, @Valid @RequestBody AdminReasonRequest request,
                                          @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã khóa tài khoản", users.lock(actor.userId(), id, request.getReason()));
    }

    @PostMapping("/{id}/unlock")
    public ApiResponse<AdminUserView> unlock(@PathVariable Long id, @Valid @RequestBody AdminReasonRequest request,
                                            @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã mở khóa tài khoản", users.unlock(actor.userId(), id, request.getReason()));
    }
}
