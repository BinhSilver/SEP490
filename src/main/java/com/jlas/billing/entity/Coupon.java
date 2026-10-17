package com.jlas.billing.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity @Table(name = "coupons") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Coupon {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 40, unique = true) private String code;
    @Column(length = 500) private String description;
    @Column(name = "discount_type", nullable = false, length = 10) private String discountType;
    @Column(name = "discount_value", nullable = false, precision = 12, scale = 2) private BigDecimal discountValue;
    @Column(name = "max_discount", precision = 12, scale = 2) private BigDecimal maxDiscount;
    @Column(name = "min_order_amount", precision = 12, scale = 2) private BigDecimal minOrderAmount;
    @Column(name = "applies_to", nullable = false, length = 10) private String appliesTo;
    @Column(name = "starts_at") private LocalDateTime startsAt;
    @Column(name = "ends_at") private LocalDateTime endsAt;
    @Column(name = "usage_limit") private Integer usageLimit;
    @Column(name = "per_user_limit") private Integer perUserLimit;
    @Column(name = "is_active", nullable = false) @Builder.Default private Boolean isActive = true;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @PrePersist void create() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate void update() { updatedAt = LocalDateTime.now(); }
}
