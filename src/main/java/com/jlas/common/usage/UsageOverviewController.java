package com.jlas.common.usage;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mức sử dụng hiện trong trang hồ sơ (GET /api/profile/usage). */
@RestController
@RequiredArgsConstructor
public class UsageOverviewController {
    private final UsageOverviewService usage;

    @GetMapping("/api/profile/usage")
    public ResponseEntity<ApiResponse<UsageOverviewService.UsageOverview>> overview(
            @AuthenticationPrincipal UserPrincipal user) {
        if (user == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        return ResponseEntity.ok(ApiResponse.ok("OK", usage.overview(user)));
    }
}
