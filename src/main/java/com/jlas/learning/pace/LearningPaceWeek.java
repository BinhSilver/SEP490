package com.jlas.learning.pace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/** [JLAS-W0108] First weekly score and the learner's one decision for that week. */
@Entity
@Table(name = "learning_pace_week")
@IdClass(LearningPaceWeek.Key.class)
public class LearningPaceWeek {
    @Id @Column(name = "path_kind", nullable = false, length = 8)
    private String pathKind;
    @Id @Column(name = "path_id", nullable = false)
    private Long pathId;
    @Id @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;
    @Column(name = "first_score")
    private Byte firstScore;
    @Column(name = "decision", length = 8)
    private String decision;
    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    public static class Key implements Serializable {
        private String pathKind;
        private Long pathId;
        private LocalDate weekStart;

        public Key() {}

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return Objects.equals(pathKind, key.pathKind) && Objects.equals(pathId, key.pathId)
                    && Objects.equals(weekStart, key.weekStart);
        }

        @Override public int hashCode() { return Objects.hash(pathKind, pathId, weekStart); }
    }
}
