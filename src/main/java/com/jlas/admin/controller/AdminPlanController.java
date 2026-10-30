package com.jlas.admin.controller;

import com.jlas.admin.dto.AdminPlanRequest;
import com.jlas.admin.dto.AdminPlanView;
import com.jlas.admin.dto.AdminReasonRequest;
import com.jlas.admin.service.AdminPlanService;
import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Premium plans for sale (JLAS2-49): list, create, edit, start/stop selling. No delete. */
@RestController
@RequestMapping("/api/admin/system/premium-plans")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminPlanController {
    private final AdminPlanService plans;

    @GetMapping
    public ApiResponse<List<AdminPlanView>> list() {
        return ApiResponse.ok("Gói Premium", plans.list());
    }

    @PostMapping
    public ApiResponse<AdminPlanView> create(@Valid @RequestBody AdminPlanRequest request,
                                             @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã tạo gói Premium", plans.create(actor.userId(), request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AdminPlanView> update(@PathVariable Long id, @Valid @RequestBody AdminPlanRequest request,
                                             @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã cập nhật gói Premium", plans.update(actor.userId(), id, request));
    }

    @PostMapping("/{id}/activate")
    public ApiResponse<AdminPlanView> activate(@PathVariable Long id, @Valid @RequestBody AdminReasonRequest request,
                                               @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã mở bán gói", plans.setActive(actor.userId(), id, true, request.getReason()));
    }

    @PostMapping("/{id}/deactivate")
    public ApiResponse<AdminPlanView> deactivate(@PathVariable Long id, @Valid @RequestBody AdminReasonRequest request,
                                                 @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã ngừng bán gói", plans.setActive(actor.userId(), id, false, request.getReason()));
    }
}
