package com.jlas.flashcard.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Card xuất hiện trong session và kết quả trả lời.
 * Table: flashcard_study_session_item
 */
@Entity
@Table(name = "flashcard_study_session_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlashcardStudySessionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_flashcard_session_item_session"))
    private FlashcardStudySession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_flashcard_session_item_card"))
    private FlashcardCard card;

    /** Card được chọn làm đáp án (quiz mode). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chosen_card_id",
            foreignKey = @ForeignKey(name = "fk_flashcard_session_item_chosen"))
    private FlashcardCard chosenCard;

    @Column(name = "position", nullable = false)
    private Integer position;

    @Column(name = "is_wrong_review", nullable = false)
    @Builder.Default
    private Boolean isWrongReview = false;

    @Column(name = "is_correct")
    private Boolean isCorrect;

    @Column(name = "answered_at")
    private LocalDateTime answeredAt;
}
