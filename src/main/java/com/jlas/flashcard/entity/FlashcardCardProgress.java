package com.jlas.flashcard.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Tiến độ spaced repetition của từng card theo user.
 * Table: flashcard_card_progress
 */
@Entity
@Table(name = "flashcard_card_progress")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlashcardCardProgress {

    @EmbeddedId
    private FlashcardCardProgressId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("userId")
    @JoinColumn(name = "user_id",
            foreignKey = @ForeignKey(name = "fk_flashcard_progress_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("cardId")
    @JoinColumn(name = "card_id",
            foreignKey = @ForeignKey(name = "fk_flashcard_progress_card"))
    private FlashcardCard card;

    /** NEW/LEARNING/MASTERED. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "NEW";

    @Column(name = "review_count", nullable = false)
    @Builder.Default
    private Integer reviewCount = 0;

    /** 0-5. */
    @Column(name = "last_quality")
    private Byte lastQuality;

    @Column(name = "easiness_factor", nullable = false, precision = 4, scale = 2)
    @Builder.Default
    private BigDecimal easinessFactor = new BigDecimal("2.50");

    @Column(name = "repetition_count", nullable = false)
    @Builder.Default
    private Integer repetitionCount = 0;

    @Column(name = "interval_days", nullable = false)
    @Builder.Default
    private Integer intervalDays = 0;

    @Column(name = "last_reviewed_at")
    private LocalDateTime lastReviewedAt;

    @Column(name = "next_review_at")
    private LocalDateTime nextReviewAt;

    @Column(name = "is_starred", nullable = false)
    @Builder.Default
    private Boolean isStarred = false;

    // -----------------------------------------------------------------------

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class FlashcardCardProgressId implements Serializable {
        @Column(name = "user_id")
        private Long userId;

        @Column(name = "card_id")
        private Long cardId;
    }
}
