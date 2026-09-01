package com.jlas.learning.entity;

import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.Tag;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Điểm yếu tổng hợp theo tag KNOWLEDGE.
 * Table: user_knowledge_weakness
 */
@Entity
@Table(name = "user_knowledge_weakness")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserKnowledgeWeakness {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_weakness_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tag_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_weakness_tag"))
    private Tag tag;

    /** Fallback khi chưa tìm được lesson khớp tag. */
    @Column(name = "skill", length = 20)
    private String skill;

    @Column(name = "score_percent", precision = 5, scale = 2)
    private BigDecimal scorePercent;

    /** WEAK/NEEDS_REINFORCEMENT. */
    @Column(name = "severity", nullable = false, length = 30)
    private String severity;

    @Column(name = "source", length = 255)
    private String source;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private LocalDateTime detectedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        detectedAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
