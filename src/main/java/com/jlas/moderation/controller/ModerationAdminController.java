package com.jlas.moderation.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.moderation.dto.ModerationQueueItem;
import com.jlas.moderation.dto.ModerationResolution;
import com.jlas.moderation.service.ModerationAdminService;
import com.jlas.moderation.service.UserSanctionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/moderation")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MODERATOR','ADMIN','SUPER_ADMIN')")
public class ModerationAdminController {
    private final ModerationAdminService moderation;
    private final UserSanctionService sanctions;

    @GetMapping("/queue")
    public ApiResponse<List<ModerationQueueItem>> queue() {
        return ApiResponse.ok("OK", moderation.listOpen());
    }

    @PostMapping("/queue/{id}/resolve")
    public ApiResponse<ModerationResolution> resolve(
            @PathVariable Long id,
            @RequestBody(required = false) ResolveRequest request,
            @AuthenticationPrincipal UserPrincipal reviewer) {
        boolean approve = request == null || request.getApprove() == null || request.getApprove();
        return ApiResponse.ok("Đã xử lý", moderation.resolve(id, reviewer.userId(), approve,
                request == null ? null : request.getNote()));
    }

    @PostMapping("/sanctions")
    public ApiResponse<UserSanctionService.SanctionView> sanction(
            @Valid @RequestBody SanctionRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã áp dụng chế tài", sanctions.apply(
                request.getUserId(), actor.userId(), request.getType(), request.getDurationDays(),
                request.getReason()));
    }

    @PostMapping("/sanctions/{id}/revoke")
    public ApiResponse<UserSanctionService.SanctionView> revoke(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã thu hồi chế tài", sanctions.revoke(id, actor.userId()));
    }

    @Data
    public static class ResolveRequest {
        private Boolean approve;
        @Size(max = 500)
        private String note;
    }

    @Data
    public static class SanctionRequest {
        @NotNull
        private Long userId;
        @NotNull
        private UserSanctionService.SanctionType type;
        private Integer durationDays;
        @Size(max = 500)
        private String reason;
    }
}
