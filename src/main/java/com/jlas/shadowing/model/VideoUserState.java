package com.jlas.shadowing.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

@Entity
@Table(name = "video_user_state")
@IdClass(VideoUserStateId.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VideoUserState {
    @Id @Column(name = "user_id") private Long userId;
    @Id @Column(name = "video_id") private Long videoId;
    @JdbcTypeCode(SqlTypes.DECIMAL)
    @Column(name = "max_position_sec", nullable = false)
    @Builder.Default private Double maxPositionSec = 0.0;
    @Column(name = "counted_as_view", nullable = false)
    @Builder.Default private boolean countedAsView = false;
    @JdbcTypeCode(SqlTypes.TINYINT)
    private Integer rating;
    @Column(name = "is_favorite", nullable = false)
    @Builder.Default private boolean favorite = false;
    @Column(name = "last_watched_at") private LocalDateTime lastWatchedAt;
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
