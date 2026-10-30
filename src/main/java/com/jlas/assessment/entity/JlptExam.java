package com.jlas.assessment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Đề thi JLPT. Table: jlpt_exam. */
@Entity
@Table(name = "jlpt_exam")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JlptExam {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_code", unique = true, length = 30)
    private String examCode;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** N5/N4/N3/N2/N1. */
    @Column(name = "jlpt_level", nullable = false, length = 10)
    private String jlptLevel;

    @Column(name = "exam_year")
    private Short examYear;

    @Column(name = "exam_month")
    private Byte examMonth;

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(name = "source_pack_path", length = 500)
    private String sourcePackPath;

    @Column(name = "passing_score", nullable = false)
    @Builder.Default
    private Integer passingScore = 95;

    @Column(name = "review_status", nullable = false, length = 30)
    @Builder.Default
    private String reviewStatus = "UNREVIEWED";

    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    /** DRAFT/PUBLISHED/ARCHIVED. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "DRAFT";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
