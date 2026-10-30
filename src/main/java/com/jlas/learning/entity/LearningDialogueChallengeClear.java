package com.jlas.learning.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/** [JLAS-W0108] Owner 2026-10-19 (G2/G3): first clear of a communication dialogue's optional "vượt ải" challenge.
 * One row per learner and dialogue lesson; inserting it is the guard for the one-time 2 CP reward. */
@Entity
@Table(name = "learning_dialogue_challenge_clear")
@IdClass(LearningDialogueChallengeClear.Key.class)
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class LearningDialogueChallengeClear {
    @Id @Column(name = "user_id") private Long userId;
    @Id @Column(name = "lesson_id") private Long lessonId;
    @Column(name = "correct_count", nullable = false) private Integer correctCount;
    @Column(name = "question_count", nullable = false) private Integer questionCount;
    @Column(name = "cleared_at", nullable = false, insertable = false, updatable = false) private LocalDateTime clearedAt;

    @Getter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long userId;
        private Long lessonId;
    }
}
