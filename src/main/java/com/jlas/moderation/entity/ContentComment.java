package com.jlas.moderation.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Comment đa hình thay flashcard_comment và video_comment.
 * Table: content_comment
 */
@Entity
@Table(name = "content_comment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FLASHCARD_SET/VIDEO/LESSON/SCENARIO/TEXTBOOK. */
    @Column(name = "target_type", nullable = false, length = 30)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_content_comment_user"))
    private User user;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "is_hidden", nullable = false)
    @Builder.Default
    private Boolean isHidden = false;

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
