package com.jlas.billing.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.billing.service.PremiumCheckoutService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/billing/coupons")
public class CouponPreviewController {
    private final PremiumCheckoutService checkout;
    @Getter @Setter public static class Request {
        @NotBlank private String code;
        @NotBlank private String productType;
        @NotNull private Long productId;
    }
    @PostMapping("/preview")
    public ApiResponse<PremiumCheckoutService.CouponPreview> preview(@Valid @RequestBody Request request,
            @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("Giảm giá", checkout.preview(user.userId(), request.getCode(), request.getProductType(), request.getProductId()));
    }
}
