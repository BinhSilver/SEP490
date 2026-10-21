package com.jlas.shadowing.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** One user's report against a Shadowing video. Closed as VALID/INVALID when a moderator decides. */
@Entity
@Table(name = "shadowing_video_report")
@Data
public class VideoReport {
    public static final String OPEN = "OPEN";
    public static final String VALID = "VALID";
    public static final String INVALID = "INVALID";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_id", nullable = false)
    private Long videoId;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    /** COPYRIGHT | INAPPROPRIATE | OTHER */
    @Column(nullable = false, length = 20)
    private String reason;

    @Column(length = 500)
    private String detail;

    /** OPEN | VALID | INVALID */
    @Column(nullable = false, length = 12)
    private String status = OPEN;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
