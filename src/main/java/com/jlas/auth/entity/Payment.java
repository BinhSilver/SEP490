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
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "premium_plan_id",
            foreignKey = @ForeignKey(name = "fk_payment_plan"))
    private PremiumPlan premiumPlan;

    @Column(name = "product_type", nullable = false, length = 20)
    @Builder.Default private String productType = "PREMIUM";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credit_package_id", foreignKey = @ForeignKey(name = "fk_payment_credit_package"))
    private com.jlas.billing.entity.CreditPackage creditPackage;

    @Column(name = "credit_amount_snapshot") private Integer creditAmountSnapshot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_id", foreignKey = @ForeignKey(name = "fk_payment_coupon"))
    private com.jlas.billing.entity.Coupon coupon;

    @Column(name = "coupon_code_snapshot", length = 40) private String couponCodeSnapshot;
    @Column(name = "original_amount", precision = 12, scale = 2) private BigDecimal originalAmount;
    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default private BigDecimal discountAmount = BigDecimal.ZERO;

    /** Cổng thanh toán, ví dụ VNPAY/MOMO. */
    @Column(name = "provider", nullable = false, length = 30)
    private String provider;

    /** Mã đơn hàng gửi sang cổng thanh toán. */
    @Column(name = "order_code", nullable = false, length = 100)
    private String orderCode;

    /** Mã giao dịch do cổng thanh toán trả về. */
    @Column(name = "provider_transaction_id", length = 150)
    private String providerTransactionId;

    /** Mã link thanh toán PayOS. */
    @Column(name = "payment_link_id", length = 100)
    private String paymentLinkId;

    /** Trang thanh toán của cổng. */
    @Column(name = "checkout_url", length = 500)
    private String checkoutUrl;

    /** Chuỗi VietQR để hiện mã QR. */
    @Column(name = "qr_code", length = 1000)
    private String qrCode;

    /** PENDING/PAID/FAILED/CANCELLED/EXPIRED/REFUNDED. */
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
    @Column(name = "duration_days_snapshot")
    private Integer durationDaysSnapshot;

    /** Thời điểm thanh toán thành công. */
    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /** Link thanh toán hết hạn lúc này. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** Lần gần nhất server hỏi trạng thái ở cổng thanh toán. */
    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    /** Premium có hiệu lực đến đâu sau khi đơn này được cộng. */
    @Column(name = "premium_until")
    private LocalDateTime premiumUntil;

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
