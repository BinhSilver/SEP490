package com.jlas.moderation.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.moderation.credit.service.CpAutoPayService;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** "Không hỏi lại hôm nay" của từng chức năng tốn CP, dùng chung mọi máy (js/components/cp-auto-pay.js). */
@RestController
@RequestMapping("/api/credits/auto-pay")
@RequiredArgsConstructor
public class CpAutoPayController {
    private final CpAutoPayService autoPay;

    @Data
    public static class Choice {
        private boolean on;
        private int cost;
    }

    @GetMapping
    public ApiResponse<Map<String, CpAutoPayService.State>> today(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", autoPay.today(user.userId()));
    }

    @PutMapping("/{feature}")
    public ApiResponse<Map<String, CpAutoPayService.State>> set(@AuthenticationPrincipal UserPrincipal user,
                                                               @PathVariable String feature,
                                                               @RequestBody Choice choice) {
        return ApiResponse.ok("OK", autoPay.set(user.userId(), feature, choice.isOn(), choice.getCost()));
    }
}
