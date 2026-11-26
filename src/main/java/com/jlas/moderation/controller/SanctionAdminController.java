package com.jlas.moderation.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.service.BulkContentGuardService;
import com.jlas.moderation.service.ModerationAuditService;
import com.jlas.moderation.service.UserSanctionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** User sanctions (MUTE/BAN) and bulk-content violations (chống tải nội dung hàng loạt). Admin only. */
@RestController
@RequestMapping("/api/admin/moderation/sanctions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SanctionAdminController {
    private final UserSanctionService sanctions;
    private final UserRepository users;
    private final BulkContentGuardService bulkGuard;

    public record UserSummary(Long id, String email, String fullName, String role) {
    }

    public record UserSanctions(UserSummary user, List<UserSanctionService.SanctionView> history,
                                List<BulkContentGuardService.ViolationView> bulkViolations) {
    }

    @GetMapping("/active")
    public ApiResponse<List<UserSanctionService.SanctionView>> active() {
        return ApiResponse.ok("OK", sanctions.allActive());
    }

    @GetMapping("/lookup")
    @Transactional(readOnly = true)
    public ApiResponse<UserSanctions> lookup(@RequestParam String email) {
        User user = users.findByEmail(email.trim().toLowerCase())
                .or(() -> users.findByEmail(email.trim()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy tài khoản với email này"));
        UserSummary summary = new UserSummary(user.getId(), user.getEmail(), user.getFullName(),
                user.getRole() == null ? null : user.getRole().getName());
        return ApiResponse.ok("OK", new UserSanctions(summary, sanctions.history(user.getId()),
                bulkGuard.forUser(user.getId())));
    }

    @PostMapping
    public ApiResponse<UserSanctionService.SanctionView> apply(@Valid @RequestBody ApplyRequest request,
                                                               @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã áp dụng chế tài", sanctions.apply(request.getUserId(), actor.userId(),
                request.getType(), request.getDurationDays(), request.getReason()));
    }

    @PostMapping("/{id}/revoke")
    public ApiResponse<UserSanctionService.SanctionView> revoke(@PathVariable Long id,
                                                                @Valid @RequestBody RevokeRequest request,
                                                                @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã gỡ chế tài", sanctions.revoke(id, actor.userId(), request.getReason()));
    }

    /** Vi phạm tải nội dung hàng loạt gần nhất (mới nhất trước). */
    @GetMapping("/bulk-violations")
    public ApiResponse<List<BulkContentGuardService.ViolationView>> bulkViolations(
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok("OK", bulkGuard.recent(limit));
    }

    /** Gỡ một vi phạm: không còn tính vào số lần tái phạm. Chế tài đi kèm (nếu có) gỡ riêng bằng /{id}/revoke. */
    @PostMapping("/bulk-violations/{id}/clear")
    public ApiResponse<BulkContentGuardService.ViolationView> clearBulkViolation(
            @PathVariable Long id, @Valid @RequestBody RevokeRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã gỡ vi phạm", bulkGuard.clear(id, actor.userId(), request.getReason()));
    }

    /** Ngưỡng chống tải hàng loạt đang áp dụng (đặt trong application.yaml / biến môi trường). */
    @GetMapping("/bulk-guard/settings")
    public ApiResponse<BulkContentGuardService.Settings> bulkGuardSettings() {
        return ApiResponse.ok("OK", bulkGuard.settings());
    }

    @Data
    public static class ApplyRequest {
        @NotNull(message = "Thiếu người dùng")
        private Long userId;
        @NotNull(message = "Vui lòng chọn loại chế tài")
        private UserSanctionService.SanctionType type;
        private Integer durationDays;
        @NotBlank(message = "Vui lòng nhập lý do")
        @Size(max = 500, message = "Lý do tối đa 500 ký tự")
        private String reason;
    }

    @Data
    public static class RevokeRequest {
        @NotBlank(message = "Vui lòng nhập lý do")
        @Size(max = ModerationAuditService.MAX_REASON, message = "Lý do tối đa 1000 ký tự")
        private String reason;
    }
}
