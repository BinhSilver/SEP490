package com.jlas.community.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Bình luận cộng đồng, đăng thẳng PUBLISHED (không qua duyệt), xoá mềm. Table: community_comment */
@Entity
@Table(name = "community_comment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommunityComment {
    public static final int MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "community_content_id", nullable = false)
    private Long communityContentId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "body", nullable = false, length = MAX_LENGTH)
    private String body;

    /** PUBLISHED / TEMPORARILY_HIDDEN / REMOVED. */
    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "report_count", nullable = false)
    private Integer reportCount;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
