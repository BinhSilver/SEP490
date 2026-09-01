package com.jlas.shadowing.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Item trong shadowing practice session.
 * Table: shadowing_practice_item
 */
@Entity
@Table(name = "shadowing_practice_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShadowingPracticeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_shadowing_practice_item_session"))
    private ShadowingPracticeSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subtitle_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_shadowing_practice_item_subtitle"))
    private Subtitle subtitle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cloze_candidate_id",
            foreignKey = @ForeignKey(name = "fk_shadowing_practice_item_candidate"))
    private ShadowingClozeCandidate clozeCandidate;

    @Column(name = "position_index", nullable = false)
    private Integer positionIndex;

    @Column(name = "difficulty_score", nullable = false)
    private Byte difficultyScore;

    @Column(name = "answered_at")
    private LocalDateTime answeredAt;
}
