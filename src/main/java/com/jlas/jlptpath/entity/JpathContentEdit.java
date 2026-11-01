package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathReportTarget;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * [JLAS-W0108] Log of one content field edit (jpath_content_edit). editorUserId 0 = system; exportedAt = already
 * exported back to the source files.
 */
@Entity
@Table(name = "jpath_content_edit")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathContentEdit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "editor_user_id", nullable = false)
    private Long editorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 8)
    private JpathReportTarget targetType;

    @Column(name = "target_code", nullable = false, length = 24)
    private String targetCode;

    @Column(name = "level", length = 2)
    private String level;

    @Column(name = "field_name", nullable = false, length = 40)
    private String fieldName;

    @Column(name = "old_value", columnDefinition = "mediumtext")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "mediumtext")
    private String newValue;

    @Column(name = "report_id")
    private Long reportId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "exported_at")
    private LocalDateTime exportedAt;
}
