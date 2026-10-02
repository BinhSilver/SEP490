package com.jlas.moderation.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity(name = "ShadowingContentModerationLog")
@Table(name = "content_moderation_log", indexes = {
        @Index(name = "idx_mod_target", columnList = "target_type,target_id"),
        @Index(name = "idx_mod_verdict", columnList = "verdict,created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentModerationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_type", nullable = false, length = 40)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "content_hash", columnDefinition = "char(64)")
    private String contentHash;

    @Column(nullable = false, length = 40)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ModerationVerdict verdict;

    @Column(name = "categories_json", columnDefinition = "JSON")
    @JdbcTypeCode(SqlTypes.JSON)
    private String categoriesJson;

    @JdbcTypeCode(SqlTypes.DECIMAL)
    private Double confidence;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "summary", length = 500)
    private String summary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
