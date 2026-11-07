package com.jlas.community.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Một user chỉ có một đánh giá 1-5 cho mỗi nội dung. Table: community_rating */
@Entity
@Table(name = "community_rating")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommunityRating {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "community_content_id", nullable = false)
    private Long communityContentId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "score", nullable = false)
    private Byte score;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
