package com.jlas.shadowing.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Watch/rating/favorite video theo user.
 * Table: video_user_state
 */
@Entity
@Table(name = "video_user_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VideoUserState {

    @EmbeddedId
    private VideoUserStateId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("userId")
    @JoinColumn(name = "user_id",
            foreignKey = @ForeignKey(name = "fk_video_user_state_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("videoId")
    @JoinColumn(name = "video_id",
            foreignKey = @ForeignKey(name = "fk_video_user_state_video"))
    private Video video;

    @Column(name = "max_position_sec", nullable = false, precision = 10, scale = 3)
    @Builder.Default
    private BigDecimal maxPositionSec = BigDecimal.ZERO;

    @Column(name = "counted_as_view", nullable = false)
    @Builder.Default
    private Boolean countedAsView = false;

    /** 1-5. */
    @Column(name = "rating")
    private Byte rating;

    @Column(name = "is_favorite", nullable = false)
    @Builder.Default
    private Boolean isFavorite = false;

    @Column(name = "last_watched_at")
    private LocalDateTime lastWatchedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // -----------------------------------------------------------------------

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class VideoUserStateId implements Serializable {
        @Column(name = "user_id")
        private Long userId;

        @Column(name = "video_id")
        private Long videoId;
    }
}
