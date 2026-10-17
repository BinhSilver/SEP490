package com.jlas.learning.entity;

import jakarta.persistence.*;
import lombok.*;

/** Reviewed dependency metadata for goal-specific lesson scheduling. */
@Entity
@Table(name = "lesson_prerequisite")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class LessonPrerequisite {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "prerequisite_code", nullable = false, unique = true, length = 100) private String prerequisiteCode;
    @Column(name = "lesson_id", nullable = false) private Long lessonId;
    @Column(name = "prerequisite_type", nullable = false, length = 40) private String prerequisiteType;
    @Column(name = "required_lesson_id") private Long requiredLessonId;
    @Column(name = "required_jlpt_level", length = 10) private String requiredJlptLevel;
    @Column(name = "required_competency_id") private Long requiredCompetencyId;
    @Column(name = "blocking_access", nullable = false) private boolean blockingAccess;
    @Column(name = "warmup_lesson_id") private Long warmupLessonId;
    @Column(name = "reason_vi", columnDefinition = "TEXT") private String reasonVi;
    @Column(name = "external_key", unique = true, length = 100) private String externalKey;
    @Column(name = "review_status", nullable = false, length = 20) private String reviewStatus;
}
