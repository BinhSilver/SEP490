package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.dto.JpathAdminItemView;
import com.jlas.jlptpath.dto.JpathContentEditRequest;
import com.jlas.jlptpath.dto.JpathEditExport;
import com.jlas.jlptpath.dto.JpathPage;
import com.jlas.jlptpath.dto.JpathReportGroupView;
import com.jlas.jlptpath.dto.JpathReportQueueFilter;
import com.jlas.jlptpath.dto.JpathReportResolveRequest;
import com.jlas.jlptpath.service.JpathContentAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * [JLAS-W0108] Content creators' "Báo sai" queue of the JLPT path: grouped reports, in-place fixes, closing reports and
 * exporting the fixes for the seed. CONTENT_CREATOR and ADMIN only (also gated in SecurityConfig).
 */
@RestController
@RequestMapping("/api/admin/learning-path")
@PreAuthorize("hasAnyRole('CONTENT_CREATOR','ADMIN')")
@RequiredArgsConstructor
public class JpathContentAdminController {

    private final JpathContentAdminService adminService;

    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<JpathPage<JpathReportGroupView>>> queue(
            @RequestParam(required = false) String status, @RequestParam(required = false) String level,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok("OK",
                adminService.queue(new JpathReportQueueFilter(status, level, page, size))));
    }

    @GetMapping("/items/{targetType}/{code}")
    public ResponseEntity<ApiResponse<JpathAdminItemView>> item(@PathVariable String targetType,
                                                                @PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.ok("OK", adminService.item(targetType, code)));
    }

    @PutMapping("/items/{targetType}/{code}")
    public ResponseEntity<ApiResponse<JpathAdminItemView>> edit(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String targetType, @PathVariable String code,
            @Valid @RequestBody JpathContentEditRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Đã lưu sửa đổi",
                adminService.edit(user.userId(), targetType, code, request)));
    }

    @PostMapping("/items/{targetType}/{code}/resolve")
    public ResponseEntity<ApiResponse<JpathAdminItemView>> resolve(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String targetType, @PathVariable String code,
            @Valid @RequestBody JpathReportResolveRequest request) {
        JpathAdminItemView view = adminService.resolve(user.userId(), targetType, code, request);
        return ResponseEntity.ok(ApiResponse.ok(JpathContentAdminService.resolveMessage(request, view), view));
    }

    @PostMapping("/edits/export")
    public ResponseEntity<ApiResponse<JpathEditExport>> export(@RequestParam String level) {
        return ResponseEntity.ok(ApiResponse.ok("OK", adminService.exportEdits(level)));
    }
}
