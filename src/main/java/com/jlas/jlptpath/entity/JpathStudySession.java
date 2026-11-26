package com.jlas.jlptpath.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * [JLAS-W0108] A study session with its frozen step queue (jpath_study_session). tab (session tab, may be COMBINED),
 * mode and status (OPEN, COMPLETED, ABANDONED) are plain strings.
 */
@Entity
@Table(name = "jpath_study_session")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathStudySession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "plan_id")
    private Long planId;

    @Column(name = "tab", nullable = false, length = 8)
    private String tab;

    @Column(name = "mode", nullable = false, length = 12)
    private String mode;

    @Builder.Default
    @Column(name = "status", nullable = false, length = 10)
    private String status = "OPEN";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "queue_json", nullable = false, columnDefinition = "json")
    private String queueJson;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "`position`", nullable = false)
    private Integer position = 0;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "total", nullable = false)
    private Integer total;

    /** Server time; filled on insert when not set. */
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    void fillStartedAt() {
        if (startedAt == null) {
            startedAt = LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        }
    }
}
