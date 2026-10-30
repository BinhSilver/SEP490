package com.jlas.moderation.entity;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.time.LocalDateTime;

@Entity
@Table(name = "content_comment_vote")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentCommentVote {
    @EmbeddedId
    private Id id;

    @Column(name = "vote", nullable = false)
    private Byte vote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Id implements Serializable {
        @Column(name = "comment_id", nullable = false)
        private Long commentId;
        @Column(name = "user_id", nullable = false)
        private Long userId;
    }
}
