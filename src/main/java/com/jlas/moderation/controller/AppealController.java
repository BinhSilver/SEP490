package com.jlas.moderation.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.moderation.dto.AppealView;
import com.jlas.moderation.service.AppealService;
import com.jlas.moderation.service.UserSanctionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AppealController {
    private final AppealService appeals;
    private final UserSanctionService sanctions;

    @PostMapping(value = "/api/appeals", consumes = "application/json")
    public ResponseEntity<ApiResponse<AppealView>> submit(@Valid @RequestBody AppealRequest request,
                                          @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("Đã gửi đơn kháng nghị", appeals.submit(user.userId(),
                request.getSanctionId(), request.getReason(), request.getEvidenceUrl())));
    }

    @PostMapping(value = "/api/appeals", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<AppealView>> submitFile(
            @Valid @ModelAttribute AppealRequest request,
            @RequestParam(required = false) MultipartFile file,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("Đã gửi đơn kháng nghị", appeals.submit(
                user.userId(), request.getSanctionId(), request.getReason(), request.getEvidenceUrl(), file)));
    }

    @GetMapping("/api/appeals/me")
    public ResponseEntity<ApiResponse<List<AppealView>>> mine(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", appeals.mine(user.userId())));
    }

    @GetMapping("/api/appeals/sanctions")
    public ResponseEntity<ApiResponse<List<UserSanctionService.SanctionView>>> activeSanctions(
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", sanctions.activeFor(user.userId())));
    }

    @GetMapping("/api/admin/moderation/appeals")
    @PreAuthorize("hasAnyRole('MODERATOR','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<AppealView>>> list(@RequestParam(required = false) String status) {
        return ResponseEntity.ok(ApiResponse.ok("OK", appeals.list(status)));
    }

    @PostMapping("/api/admin/moderation/appeals/{id}/resolve")
    @PreAuthorize("hasAnyRole('MODERATOR','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<AppealView>> resolve(@PathVariable Long id,
                                           @Valid @RequestBody AppealResolution request,
                                           @AuthenticationPrincipal UserPrincipal reviewer) {
        return ResponseEntity.ok(ApiResponse.ok("Đã xử lý đơn kháng nghị", appeals.resolve(id, reviewer.userId(),
                request.getApprove(), request.getNote())));
    }

    @Data
    public static class AppealRequest {
        @NotNull
        private Long sanctionId;
        @NotBlank
        @Size(min = 20, max = 1000, message = "Lý do cần từ 20 đến 1000 ký tự")
        private String reason;
        @Size(max = 500)
        private String evidenceUrl;
    }

    @Data
    public static class AppealResolution {
        @NotNull(message = "Thiếu quyết định")
        private Boolean approve;
        @NotBlank(message = "Vui lòng nhập lý do")
        @Size(max = 500, message = "Lý do tối đa 500 ký tự")
        private String note;
    }
}
