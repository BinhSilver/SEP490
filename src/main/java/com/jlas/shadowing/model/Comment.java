package com.jlas.shadowing.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "content_comment")
@Data
public class Comment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "target_type", nullable = false, length = 30, updatable = false)
    private String targetType = "VIDEO";
    @Column(name = "target_id", nullable = false)
    private Long targetId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;
    @Column(name = "is_hidden", nullable = false)
    private Boolean hidden = false;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Transient private String clientId;
    @Transient private String displayName;
    @Transient private Integer rating;

    @PrePersist
    protected void onCreate() {
        targetType = "VIDEO";
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (hidden == null) hidden = false;
    }

    @PreUpdate protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
