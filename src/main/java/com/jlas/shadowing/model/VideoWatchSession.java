package com.jlas.shadowing.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

@Table(name = "video_watch_sessions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"client_id", "video_id"})
})
@Data
public class VideoWatchSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String clientId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id", nullable = false)
    private Video video;

    @JdbcTypeCode(SqlTypes.DECIMAL)
    private Double maxPositionSec = 0.0;

    private Boolean countedAsView = false;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
