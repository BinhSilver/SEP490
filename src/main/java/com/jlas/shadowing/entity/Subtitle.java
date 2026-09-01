package com.jlas.shadowing.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Câu subtitle và dữ liệu AI furigana/dịch.
 * Table: subtitle
 */
@Entity
@Table(name = "subtitle")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subtitle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_subtitle_video"))
    private Video video;

    @Column(name = "start_time", nullable = false, precision = 10, scale = 3)
    private BigDecimal startTime;

    @Column(name = "end_time", nullable = false, precision = 10, scale = 3)
    private BigDecimal endTime;

    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "furigana", columnDefinition = "TEXT")
    private String furigana;

    @Column(name = "translation", columnDefinition = "TEXT")
    private String translation;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /** PENDING/PROCESSING/COMPLETED/FAILED/SKIPPED. */
    @Column(name = "ai_status", nullable = false, length = 20)
    @Builder.Default
    private String aiStatus = "PENDING";

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
