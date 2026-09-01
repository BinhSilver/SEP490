package com.jlas.moderation.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Comment rating table.
 * Table: content_comment_rating
 */
@Entity
@Table(name = "content_comment_rating")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentCommentRating {

    @Id
    @Column(name = "comment_id")
    private Long commentId;

    @Column(name = "rating", nullable = false, columnDefinition = "TINYINT")
    private Integer rating;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
