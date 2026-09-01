package com.jlas.moderation.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Tổng quota AI theo ngày.
 * Table: ai_usage_daily
 */
@Entity
@Table(name = "ai_usage_daily")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiUsageDaily {

    @EmbeddedId
    private AiUsageDailyId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("userId")
    @JoinColumn(name = "user_id",
            foreignKey = @ForeignKey(name = "fk_ai_usage_user"))
    private User user;

    @Column(name = "minutes_used", nullable = false, precision = 10, scale = 3)
    @Builder.Default
    private BigDecimal minutesUsed = BigDecimal.ZERO;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // -----------------------------------------------------------------------

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class AiUsageDailyId implements Serializable {
        @Column(name = "user_id")
        private Long userId;

        @Column(name = "usage_date")
        private LocalDate usageDate;
    }
}
