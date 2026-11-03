package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathReportError;
import com.jlas.jlptpath.enums.JpathReportTarget;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * [JLAS-W0108] A learner's content error report (jpath_content_report). status: OPEN, RESOLVED or REJECTED. openFlag
 * is derived from status (TRUE when OPEN, NULL otherwise) so that uk_jpath_report_open allows one OPEN report per
 * reporter and target; it has no public setter.
 */
@Entity
@Table(name = "jpath_content_report", uniqueConstraints = @UniqueConstraint(
        name = "uk_jpath_report_open", columnNames = {"target_type", "target_code", "reporter_user_id", "open_flag"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathContentReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "reporter_user_id", nullable = false)
    private Long reporterUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private JpathReportTarget targetType;

    @Column(name = "target_code", nullable = false, length = 24)
    private String targetCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 8)
    private JpathItemType itemType;

    @Column(name = "item_code", nullable = false, length = 24)
    private String itemCode;

    @Column(name = "level", length = 2)
    private String level;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_type", nullable = false, length = 8)
    private JpathReportError errorType;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "page_context", length = 40)
    private String pageContext;

    @Builder.Default
    @Column(name = "status", nullable = false, length = 10)
    private String status = "OPEN";

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Setter(AccessLevel.NONE)
    @Column(name = "open_flag")
    private Boolean openFlag;

    @PrePersist
    @PreUpdate
    void syncFlag() {
        openFlag = "OPEN".equals(status) ? Boolean.TRUE : null;
    }
}
