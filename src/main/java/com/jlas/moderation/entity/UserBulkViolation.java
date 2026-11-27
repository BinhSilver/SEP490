package com.jlas.moderation.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Một lần tài khoản vượt ngưỡng tải nội dung hàng loạt (tải xuống / đăng tải) và mức xử lý hệ thống đã áp.
 * Table: user_bulk_violation
 */
@Entity
@Table(name = "user_bulk_violation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserBulkViolation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** DOWNLOAD/UPLOAD. */
    @Column(name = "kind", nullable = false, length = 10)
    private String kind;

    @Column(name = "request_count", nullable = false)
    private Integer requestCount;

    @Column(name = "request_limit", nullable = false)
    private Integer requestLimit;

    @Column(name = "window_seconds", nullable = false)
    private Integer windowSeconds;

    @Column(name = "sample_path", length = 255)
    private String samplePath;

    /** WARN/SUSPEND/LOCK. */
    @Column(name = "action_taken", nullable = false, length = 10)
    private String actionTaken;

    @Column(name = "sanction_id")
    private Long sanctionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "cleared_at")
    private LocalDateTime clearedAt;

    @Column(name = "cleared_by")
    private Long clearedBy;

    @Column(name = "clear_reason", length = 500)
    private String clearReason;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
