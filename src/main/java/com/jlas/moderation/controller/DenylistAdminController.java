package com.jlas.moderation.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.moderation.dto.TextHighlight;
import com.jlas.moderation.service.ContentModerationService;
import com.jlas.moderation.service.DenylistService;
import com.jlas.moderation.service.ModerationAuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Denylist management. Moderators may edit it (decision recorded in the W0250 plan). */
@RestController
@RequestMapping("/api/admin/moderation/denylist")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MODERATOR','ADMIN')")
public class DenylistAdminController {
    private final DenylistService denylist;
    private final ContentModerationService moderation;

    @GetMapping
    public ApiResponse<List<DenylistService.TermView>> list() {
        return ApiResponse.ok("OK", denylist.list());
    }

    @PostMapping
    public ApiResponse<DenylistService.TermView> create(@Valid @RequestBody CreateRequest request,
                                                        @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã thêm từ khoá", denylist.create(request.getTerm(), request.getAction(),
                request.isAccentSensitive(), request.getReason(), actor.userId()));
    }

    @PutMapping("/{id}")
    public ApiResponse<DenylistService.TermView> update(@PathVariable Long id,
                                                        @Valid @RequestBody UpdateRequest request,
                                                        @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã cập nhật từ khoá", denylist.update(id, request.getAction(),
                request.isAccentSensitive(), request.isActive(), request.getReason(), actor.userId()));
    }

    @PostMapping("/test")
    public ApiResponse<TestResult> test(@Valid @RequestBody TestRequest request) {
        return ApiResponse.ok("OK", new TestResult(moderation.testDenylist(request.getText()),
                moderation.highlight(request.getText())));
    }

    public record TestResult(List<ContentModerationService.DenylistHit> hits, List<TextHighlight> highlights) {
    }

    @Data
    public static class CreateRequest {
        @NotBlank(message = "Vui lòng nhập từ khoá")
        @Size(max = 255, message = "Từ khoá tối đa 255 ký tự")
        private String term;
        @NotBlank(message = "Vui lòng chọn hành động")
        private String action;
        private boolean accentSensitive;
        @NotBlank(message = "Vui lòng nhập lý do")
        @Size(max = ModerationAuditService.MAX_REASON, message = "Lý do tối đa 1000 ký tự")
        private String reason;
    }

    @Data
    public static class UpdateRequest {
        @NotBlank(message = "Vui lòng chọn hành động")
        private String action;
        private boolean accentSensitive;
        private boolean active;
        @NotBlank(message = "Vui lòng nhập lý do")
        @Size(max = ModerationAuditService.MAX_REASON, message = "Lý do tối đa 1000 ký tự")
        private String reason;
    }

    @Data
    public static class TestRequest {
        @NotBlank(message = "Vui lòng nhập văn bản cần thử")
        @Size(max = 5000, message = "Văn bản tối đa 5000 ký tự")
        private String text;
    }
}
