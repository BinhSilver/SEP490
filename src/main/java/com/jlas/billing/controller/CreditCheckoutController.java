package com.jlas.billing.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.billing.service.PremiumCheckoutService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/credits")
public class CreditCheckoutController {
    private final PremiumCheckoutService checkout;
    @Getter @Setter public static class Request {
        @NotNull(message = "Vui lòng chọn gói CP.") private Long packageId;
        private String couponCode;
    }
    @GetMapping("/packages")
    public ApiResponse<PremiumCheckoutService.CreditPricing> packages(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("Gói CP", checkout.creditPricing(user == null ? null : user.userId()));
    }
    @PostMapping("/checkout")
    public ApiResponse<PremiumCheckoutService.OrderView> checkout(@Valid @RequestBody Request request,
            @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("Đã tạo đơn thanh toán", checkout.startCredit(user.userId(), request.getPackageId(), request.getCouponCode()));
    }
}
