package com.jlas.community.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Trạng thái cộng đồng của một nội dung gốc (flashcard_set, video...). Không sao chép title/owner/visibility:
 * bảng gốc là nguồn sự thật. Dòng chỉ được tạo khi có rating/comment/report đầu tiên.
 * Table: community_content
 */
@Entity
@Table(name = "community_content")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommunityContent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_type", nullable = false, length = 30)
    private String contentType;

    @Column(name = "content_id", nullable = false)
    private Long contentId;

    /** PUBLISHED / TEMPORARILY_HIDDEN / REMOVED. */
    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "rating_avg", nullable = false, precision = 3, scale = 2)
    private BigDecimal ratingAvg;

    @Column(name = "rating_count", nullable = false)
    private Integer ratingCount;

    @Column(name = "comment_count", nullable = false)
    private Integer commentCount;

    @Column(name = "report_count", nullable = false)
    private Integer reportCount;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
