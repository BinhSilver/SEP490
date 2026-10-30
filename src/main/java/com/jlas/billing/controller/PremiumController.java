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
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Premium pricing, checkout and billing history (JLAS2-49). Pricing is public; buying needs an account. */
@RestController
@RequestMapping("/api/premium")
@RequiredArgsConstructor
public class PremiumController {
    private final PremiumCheckoutService checkout;

    @Getter
    @Setter
    public static class CheckoutRequest {
        @NotNull(message = "Vui lòng chọn gói.")
        private Long planId;
        private String couponCode;
    }

    @GetMapping("/plans")
    public ApiResponse<PremiumCheckoutService.Pricing> plans(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("Bảng giá Premium", checkout.pricing(user == null ? null : user.userId()));
    }

    @PostMapping("/checkout")
    public ApiResponse<PremiumCheckoutService.OrderView> start(@Valid @RequestBody CheckoutRequest request,
                                                               @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("Đã tạo đơn thanh toán", checkout.start(user.userId(), request.getPlanId(), request.getCouponCode()));
    }

    /** Polled by the checkout page; the server checks PayOS itself, the browser's result is never trusted. */
    @GetMapping("/orders/{orderCode}")
    public ApiResponse<PremiumCheckoutService.OrderView> order(@PathVariable long orderCode,
                                                               @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("Đơn thanh toán", checkout.order(user.userId(), orderCode));
    }

    @PostMapping("/orders/{orderCode}/cancel")
    public ApiResponse<PremiumCheckoutService.OrderView> cancel(@PathVariable long orderCode,
                                                                @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("Đã huỷ đơn thanh toán", checkout.cancel(user.userId(), orderCode));
    }

    @GetMapping("/orders")
    public ApiResponse<List<PremiumCheckoutService.OrderView>> history(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("Lịch sử thanh toán", checkout.history(user.userId()));
    }

    /** Thymeleaf pages; data comes from the API above. */
    @Controller
    public static class Pages {
        @GetMapping("/credits")
        public String credits(Model model) {
            model.addAttribute("page", "credits");
            return "premium/premium";
        }
        @GetMapping("/premium")
        public String pricing(Model model) {
            model.addAttribute("page", "pricing");
            return "premium/premium";
        }

        @GetMapping("/premium/checkout/{orderCode}")
        public String checkout(@PathVariable long orderCode, Model model) {
            model.addAttribute("page", "checkout");
            model.addAttribute("orderCode", orderCode);
            return "premium/premium";
        }

        @GetMapping("/premium/billing")
        public String billing(Model model) {
            model.addAttribute("page", "billing");
            return "premium/premium";
        }
    }
}
