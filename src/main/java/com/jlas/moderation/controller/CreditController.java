package com.jlas.moderation.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import com.jlas.moderation.credit.service.UserCreditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/credits")
@RequiredArgsConstructor
public class CreditController {
    private final UserCreditService credits;

    public record CreditAccount(long balance, List<UserCreditService.CreditTransactionView> transactions) {
    }

    @GetMapping("/me")
    public ApiResponse<CreditAccount> mine(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", new CreditAccount(credits.balance(user.userId()),
                credits.history(user.userId())));
    }

    @PostMapping("/admin/grant")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<CreditAccount> grant(@Valid @RequestBody GrantRequest request) {
        credits.earn(request.getUserId(), request.getAmount(), CreditReason.ADMIN_GRANT,
                "ADMIN_GRANT", request.getReferenceId(), request.getNote());
        return ApiResponse.ok("Đã cộng credit", new CreditAccount(credits.balance(request.getUserId()),
                credits.history(request.getUserId())));
    }

    @Data
    public static class GrantRequest {
        @NotNull
        private Long userId;
        @Min(1)
        @Max(100000)
        private int amount;
        private Long referenceId;
        @Size(max = 200)
        private String note;
    }
}
