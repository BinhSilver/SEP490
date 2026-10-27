package com.jlas.shadowing.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * A media source that moderators removed for good, so nobody can import it into Shadowing again.
 * {@code sourceKey} is {@code youtube:<11-char id>}, which covers every URL form of the same video.
 */
@Entity
@Table(name = "shadowing_blocked_source")
@Data
public class BlockedVideoSource {
    @Id
    @Column(name = "source_key", length = 100)
    private String sourceKey;

    @Column(name = "source_url", columnDefinition = "TEXT")
    private String sourceUrl;

    @Column(length = 500)
    private String reason;

    /** The video that was removed first; informational only, so no foreign key. */
    @Column(name = "video_id")
    private Long videoId;

    @Column(name = "blocked_by")
    private Long blockedBy;

    @Column(name = "blocked_at", nullable = false)
    private LocalDateTime blockedAt;
}
