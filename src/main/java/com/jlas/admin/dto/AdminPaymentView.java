package com.jlas.admin.dto;

import com.jlas.auth.entity.Payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A payment as recorded by the payment flow; plan name and days are the snapshot taken at purchase. */
public record AdminPaymentView(Long id, String orderCode, String provider, String providerTransactionId,
                               String status, BigDecimal amountPaid, String currency, String responseCode,
                               String planName, Integer durationDays, LocalDateTime paidAt,
                               LocalDateTime createdAt, LocalDateTime updatedAt,
                               Long userId, String userEmail, String userName) {
    public static AdminPaymentView from(Payment p) {
        return new AdminPaymentView(p.getId(), p.getOrderCode(), p.getProvider(), p.getProviderTransactionId(),
                p.getStatus(), p.getAmountPaid(), p.getCurrency(), p.getResponseCode(), p.getPlanNameSnapshot(),
                p.getDurationDaysSnapshot(), p.getPaidAt(), p.getCreatedAt(), p.getUpdatedAt(),
                p.getUser().getId(), p.getUser().getEmail(), p.getUser().getFullName());
    }
}
