package com.jlas.moderation.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.CurrentUser;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.dto.ContributionDetail;
import com.jlas.moderation.dto.ModerationQueueItem;
import com.jlas.moderation.dto.ModerationResolution;
import com.jlas.moderation.dto.TextHighlight;
import com.jlas.moderation.report.ReportReviewService;
import com.jlas.moderation.report.ReportedTarget;
import com.jlas.moderation.service.ContentModerationService;
import com.jlas.moderation.service.ContributionDetailPort;
import com.jlas.moderation.service.DecisionRevisionService;
import com.jlas.moderation.service.ModerationAdminService;
import com.jlas.moderation.service.ModerationAuditService;
import com.jlas.moderation.service.ModerationOverviewService;
import com.jlas.moderation.service.UserSanctionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Moderation workspace API (JLAS-W0250): overview, reports, AI queue, dictionary, audit. */
@RestController
@RequestMapping("/api/admin/moderation")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MODERATOR','SUPER_ADMIN')")
public class ModerationAdminController {
    private final ModerationAdminService moderation;
    private final ModerationOverviewService overview;
    private final ReportReviewService reports;
    private final ContributionDetailPort contributions;
    private final ModerationAuditService audit;
    private final ContentModerationService scanner;
    private final DecisionRevisionService revisions;

    @GetMapping("/overview")
    public ApiResponse<ModerationOverviewService.Overview> overview() {
        return ApiResponse.ok("OK", overview.overview());
    }

    // ── AI review queue ─────────────────────────────────────────────────────

    @GetMapping("/queue")
    public ApiResponse<List<ModerationQueueItem>> queue() {
        return ApiResponse.ok("OK", moderation.listContentQueue());
    }

    @PostMapping("/queue/{id}/resolve")
    public ApiResponse<ModerationResolution> resolve(@PathVariable Long id,
                                                     @Valid @RequestBody DecisionRequest request,
                                                     @AuthenticationPrincipal UserPrincipal reviewer) {
        return ApiResponse.ok("Đã xử lý", moderation.resolve(id, reviewer.userId(), request.getApprove(),
                request.getReason()));
    }

    // ── Dictionary contributions ────────────────────────────────────────────

    @GetMapping("/dictionary")
    public ApiResponse<List<ModerationQueueItem>> dictionary() {
        return ApiResponse.ok("OK", moderation.listDictionaryContributions());
    }

    @GetMapping("/dictionary/entries/{vocabularyId}")
    public ApiResponse<ContributionDetail> contribution(@PathVariable Long vocabularyId) {
        return ApiResponse.ok("OK", contributions.detail(vocabularyId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy từ vựng đóng góp")));
    }

    // ── Reported community content ──────────────────────────────────────────

    @GetMapping("/reports")
    public ApiResponse<ReportList> reports() {
        return ApiResponse.ok("OK", new ReportList(reports.available(), reports.pending().stream()
                .map(target -> new ReportView(target, scanner.highlight(target.preview()))).toList()));
    }

    @PostMapping("/reports/resolve")
    public ApiResponse<ReportReviewService.ReportResolution> resolveReport(
            @Valid @RequestBody ReportDecisionRequest request,
            @AuthenticationPrincipal UserPrincipal moderator) {
        ReportReviewService.SanctionRequest sanction = request.getSanctionType() == null ? null
                : new ReportReviewService.SanctionRequest(request.getSanctionType(), request.getDurationDays());
        boolean canSanction = isAdmin();
        return ApiResponse.ok(request.getValid() ? "Đã gỡ nội dung" : "Đã khôi phục nội dung",
                reports.resolve(request.getTargetType(), request.getTargetId(), request.getValid(),
                        request.getReason(), sanction, moderator.userId(), canSanction));
    }

    // ── Audit trail ─────────────────────────────────────────────────────────

    @GetMapping("/audit")
    public ApiResponse<ModerationAuditService.AuditPage> audit(
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size,
            @AuthenticationPrincipal UserPrincipal viewer) {
        return ApiResponse.ok("OK", audit.search(actorId, action, targetType, targetId, from, to, page, size,
                viewer == null ? null : viewer.userId(), isAdmin()));
    }

    /** Flips the decision recorded in an audit row. Allowed for whoever made it, or any admin. */
    @PostMapping("/audit/{auditId}/revise")
    public ApiResponse<DecisionRevisionService.Revision> revise(@PathVariable Long auditId,
                                                              @Valid @RequestBody RevisionRequest request,
                                                              @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã đổi quyết định",
                revisions.revise(auditId, actor.userId(), isAdmin(), request.getReason()));
    }

    private static boolean isAdmin() {
        return CurrentUser.hasRole("ROLE_SUPER_ADMIN");
    }

    public record ReportList(boolean available, List<ReportView> items) {
    }

    /** Reported target plus the flagged terms located in its preview. */
    public record ReportView(ReportedTarget target, List<TextHighlight> highlights) {
    }

    @Data
    public static class DecisionRequest {
        @NotNull(message = "Thiếu quyết định")
        private Boolean approve;
        @NotBlank(message = "Vui lòng nhập lý do")
        @Size(max = ModerationAuditService.MAX_REASON, message = "Lý do tối đa 1000 ký tự")
        private String reason;
    }

    @Data
    public static class RevisionRequest {
        @NotBlank(message = "Vui lòng nhập lý do")
        @Size(max = ModerationAuditService.MAX_REASON, message = "Lý do tối đa 1000 ký tự")
        private String reason;
    }

    @Data
    public static class ReportDecisionRequest {
        @NotBlank(message = "Thiếu loại nội dung")
        private String targetType;
        @NotNull(message = "Thiếu nội dung")
        private Long targetId;
        @NotNull(message = "Thiếu quyết định")
        private Boolean valid;
        @NotBlank(message = "Vui lòng nhập lý do")
        @Size(max = ModerationAuditService.MAX_REASON, message = "Lý do tối đa 1000 ký tự")
        private String reason;
        private UserSanctionService.SanctionType sanctionType;
        private Integer durationDays;
    }
}
