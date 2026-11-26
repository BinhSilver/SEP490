package com.jlas.community.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Ảnh / video đính kèm một bài viết Cộng đồng. IMAGE và VIDEO nằm trên Azure Blob ({@code storageKey} = tên blob),
 * YOUTUBE chỉ lưu mã video. Thứ tự hiển thị theo {@code sortOrder}.
 * Table: community_post_media
 */
@Entity
@Table(name = "community_post_media")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommunityPostMedia {
    public static final String IMAGE = "IMAGE";
    public static final String VIDEO = "VIDEO";
    public static final String YOUTUBE = "YOUTUBE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    /** IMAGE | VIDEO | YOUTUBE. */
    @Column(name = "kind", nullable = false, length = 10)
    private String kind;

    @Column(name = "storage_key", nullable = false, length = 300)
    private String storageKey;

    @Column(name = "content_type", length = 60)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (sortOrder == null) sortOrder = 0;
    }
}
