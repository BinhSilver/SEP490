package com.jlas.assessment.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Thuộc tính riêng của lần thi JLPT.
 * Table: jlpt_exam_attempt
 */
@Entity
@Table(name = "jlpt_exam_attempt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JlptExamAttempt {

    @Id
    @Column(name = "attempt_id")
    private Long attemptId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "attempt_id",
            foreignKey = @ForeignKey(name = "fk_jlpt_exam_attempt_attempt"))
    private AssessmentAttempt attempt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_jlpt_exam_attempt_exam"))
    private JlptExam exam;

    /** [JLAS-W0108] Policy version the attempt was taken under (written by the Mock Test owner, never by Learning). */
    @Column(name = "policy_version", length = 100)
    private String policyVersion;
}
