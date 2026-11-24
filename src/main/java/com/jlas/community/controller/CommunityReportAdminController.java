package com.jlas.community.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.CurrentUser;
import com.jlas.community.dto.CommunityDtos.PageResult;
import com.jlas.community.service.CommunityReportAdminService;
import com.jlas.community.service.CommunityReportAdminService.ReportItem;
import com.jlas.community.service.CommunityReportAdminService.KindCount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API trang "Báo cáo cộng đồng" (/admin/community-reports). CONTENT_CREATOR / MODERATOR / ADMIN (SecurityConfig).
 * targetType = COMMUNITY_CONTENT (id dòng community_content) hoặc COMMUNITY_COMMENT (id bình luận).
 */
@RestController
@RequestMapping("/api/admin/community-reports")
@RequiredArgsConstructor
public class CommunityReportAdminController {
    private final CommunityReportAdminService reports;

    public record DecisionRequest(@NotBlank String targetType, @NotNull Long targetId, String reason) {
    }

    /** Số đối tượng theo loại (Bài viết / Bình luận / Bộ flashcard…) cho hàng tab. */
    @GetMapping("/kinds")
    public ResponseEntity<ApiResponse<List<KindCount>>> kinds(@RequestParam(required = false) String status) {
        return ResponseEntity.ok(ApiResponse.ok("OK", reports.kinds(status)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<ReportItem>>> list(@RequestParam(required = false) String status,
                                                                    @RequestParam(required = false) String kind,
                                                                    @RequestParam(required = false) Integer page,
                                                                    @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(ApiResponse.ok("OK", reports.list(status, kind, page, size)));
    }

    @PostMapping("/remove")
    public ResponseEntity<ApiResponse<Void>> remove(@Valid @RequestBody DecisionRequest request) {
        reports.remove(request.targetType(), request.targetId(), CurrentUser.requireUserId(), request.reason());
        return ResponseEntity.ok(ApiResponse.ok("Đã gỡ nội dung"));
    }

    @PostMapping("/dismiss")
    public ResponseEntity<ApiResponse<Void>> dismiss(@Valid @RequestBody DecisionRequest request) {
        reports.dismiss(request.targetType(), request.targetId(), CurrentUser.requireUserId(), request.reason());
        return ResponseEntity.ok(ApiResponse.ok("Đã giữ nội dung, báo cáo được đóng"));
    }
}
