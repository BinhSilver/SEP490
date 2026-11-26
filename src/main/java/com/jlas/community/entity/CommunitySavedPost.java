package com.jlas.community.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Một mục trên feed Cộng đồng mà người dùng đã "Lưu" để xem lại sau. Lưu theo loại + id gốc (như community_content)
 * nên áp dụng cho mọi loại nội dung. Mỗi người lưu một mục một lần. Table: community_saved_post
 */
@Entity
@Table(name = "community_saved_post")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommunitySavedPost {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "content_type", nullable = false, length = 30)
    private String contentType;

    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
