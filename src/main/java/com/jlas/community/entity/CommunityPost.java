package com.jlas.community.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Bài viết người học tự đăng (chỉ text). Là bảng gốc của content_type POST: trạng thái cộng đồng, bộ đếm,
 * bình luận, báo cáo dùng chung community_content / community_comment / community_report. Xoá mềm.
 * Table: community_post
 */
@Entity
@Table(name = "community_post")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommunityPost {
    public static final int MAX_LENGTH = 5000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(name = "body", nullable = false, length = MAX_LENGTH)
    private String body;

    /** N5..N1; null = Chung. */
    @Column(name = "jlpt_level", length = 4)
    private String jlptLevel;

    /** Lần sửa gần nhất; null nếu chưa sửa (FE hiện "(đã chỉnh sửa)"). */
    @Column(name = "edited_at")
    private LocalDateTime editedAt;

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
