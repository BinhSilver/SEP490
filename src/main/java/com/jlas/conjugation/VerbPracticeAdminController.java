package com.jlas.conjugation;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/verb-practice")
@PreAuthorize("hasRole('CONTENT_CREATOR')")
@RequiredArgsConstructor
public class VerbPracticeAdminController {
    private final VerbCatalog catalog;

    public record CatalogView(VerbCatalog.Data content, Instant updatedAt, Long updatedBy) {}
    public record FormOption(String id, String label) {}

    @GetMapping("/forms")
    public ApiResponse<java.util.List<FormOption>> forms() {
        return ApiResponse.ok("Đã tải danh sách thể.", java.util.Arrays.stream(VerbEngine.Form.values())
                .map(form -> new FormOption(form.name(), form.label)).toList());
    }

    @GetMapping("/catalog")
    public ApiResponse<CatalogView> catalog() {
        var metadata = catalog.metadata();
        return ApiResponse.ok("Đã tải kho nội dung luyện chia.",
                new CatalogView(catalog.data(), metadata.updatedAt(), metadata.updatedBy()));
    }

    @PutMapping("/catalog")
    public ApiResponse<CatalogView> save(@RequestBody VerbCatalog.Data content,
                                         @AuthenticationPrincipal UserPrincipal principal) {
        VerbCatalog.CatalogMetadata metadata;
        try {
            metadata = catalog.replace(content, principal == null ? null : principal.userId());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        return ApiResponse.ok("Đã lưu kho nội dung luyện chia.",
                new CatalogView(catalog.data(), metadata.updatedAt(), metadata.updatedBy()));
    }
}

@org.springframework.stereotype.Controller
class VerbPracticeAdminPageController {
    @org.springframework.web.bind.annotation.GetMapping("/admin/verb-practice")
    public String page() { return "admin/verb-practice"; }
}
