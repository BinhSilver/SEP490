package com.jlas.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Lịch sử thanh toán premium.
 * Table: payments
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Người thanh toán. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_payment_user"))
    private User user;

    /** Gói được mua. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "premium_plan_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_payment_plan"))
    private PremiumPlan premiumPlan;

    /** Cổng thanh toán, ví dụ VNPAY/MOMO. */
    @Column(name = "provider", nullable = false, length = 30)
    private String provider;

    /** Mã đơn hàng gửi sang cổng thanh toán. */
    @Column(name = "order_code", nullable = false, length = 100)
    private String orderCode;

    /** Mã giao dịch do cổng thanh toán trả về. */
    @Column(name = "provider_transaction_id", length = 150)
    private String providerTransactionId;

    /** PENDING/PAID/FAILED/CANCELLED/REFUNDED. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    /** Số tiền thực tế user đã thanh toán. */
    @Column(name = "amount_paid", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountPaid;

    /** Mã tiền tệ ISO 4217, ví dụ VND/USD. */
    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "VND";

    /** Mã kết quả cuối cùng từ cổng thanh toán. */
    @Column(name = "response_code", length = 50)
    private String responseCode;

    /** Tên gói tại lúc mua, phục vụ hóa đơn lịch sử. */
    @Column(name = "plan_name_snapshot", nullable = false, length = 100)
    private String planNameSnapshot;

    /** Số ngày được mua tại lúc giao dịch. */
    @Column(name = "duration_days_snapshot", nullable = false)
    private Integer durationDaysSnapshot;

    /** Thời điểm thanh toán thành công. */
    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
