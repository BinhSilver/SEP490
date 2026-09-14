package com.jlas.shadowing.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Video/audio shadowing và trạng thái xử lý.
 * Table: video
 */
@Entity
@Table(name = "video")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Video {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id",
            foreignKey = @ForeignKey(name = "fk_video_owner"))
    private User owner;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "video_path", columnDefinition = "TEXT")
    private String videoPath;

    @Column(name = "source_url", columnDefinition = "TEXT")
    private String sourceUrl;

    @Column(name = "thumbnail_url", columnDefinition = "TEXT")
    private String thumbnailUrl;

    @Column(name = "duration_seconds", precision = 10, scale = 3)
    private BigDecimal durationSeconds;

    /** READY/PROCESSING/ERROR. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "READY";

    @Column(name = "processing_step", length = 32)
    private String processingStep;

    @Column(name = "progress_percent", nullable = false)
    @Builder.Default
    private Byte progressPercent = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "target_language", nullable = false, length = 10)
    @Builder.Default
    private String targetLanguage = "vi";

    @Column(name = "generate_furigana", nullable = false)
    @Builder.Default
    private Boolean generateFurigana = false;

    /** PUBLIC/PRIVATE. */
    @Column(name = "visibility", nullable = false, length = 20)
    @Builder.Default
    private String visibility = "PRIVATE";

    @Column(name = "moderation_status", nullable = false, length = 20)
    @Builder.Default
    private String moderationStatus = "NOT_SCANNED";

    @Column(name = "moderation_checked_at")
    private LocalDateTime moderationCheckedAt;

    @Column(name = "moderation_summary", length = 500)
    private String moderationSummary;

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
