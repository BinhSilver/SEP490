package com.jlas.billing.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity @Table(name = "credit_packages") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CreditPackage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 100, unique = true) private String name;
    @Column(length = 500) private String description;
    @Column(name = "credit_amount", nullable = false) private Integer creditAmount;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal price;
    @Column(name = "is_active", nullable = false) @Builder.Default private Boolean isActive = true;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @PrePersist void create() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate void update() { updatedAt = LocalDateTime.now(); }
}
