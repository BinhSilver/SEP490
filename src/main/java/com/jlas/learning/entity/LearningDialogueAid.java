package com.jlas.learning.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** [JLAS-W0108] Owner 2026-10-19 (C2/C4): J-LAS supplement aids of one communication dialogue lesson — line furigana
 * and "Từ & cụm từ" as JSON. Written by the generated Learning-owned seed only; content stays NEEDS_REVIEW until a
 * reviewer approves it. */
@Entity
@Immutable
@Table(name = "learning_dialogue_aid")
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class LearningDialogueAid {
    @Id @Column(name = "lesson_id") private Long lessonId;
    @Column(name = "aid_json", nullable = false, columnDefinition = "MEDIUMTEXT") private String aidJson;
    @Column(name = "review_status", nullable = false, length = 20) private String reviewStatus;
}
