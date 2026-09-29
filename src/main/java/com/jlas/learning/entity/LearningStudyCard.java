package com.jlas.learning.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** [JLAS-W0193] Per-membership flip/remember state. "Remembered" is study evidence, never mastery. */
@Entity
@Table(name = "learning_study_card", uniqueConstraints = @UniqueConstraint(
        name = "uk_learning_study_card_identity", columnNames = {"study_session_id", "canonical_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LearningStudyCard {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "study_session_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_learning_study_card_session"))
    private LearningStudySession session;

    @Column(name = "canonical_id", nullable = false)
    private Long canonicalId;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    @Column(name = "flipped", nullable = false)
    @Builder.Default
    private boolean flipped = false;

    @Column(name = "remembered", nullable = false)
    @Builder.Default
    private boolean remembered = false;

    @Column(name = "flipped_at")
    private LocalDateTime flippedAt;

    @Column(name = "remembered_at")
    private LocalDateTime rememberedAt;
}
