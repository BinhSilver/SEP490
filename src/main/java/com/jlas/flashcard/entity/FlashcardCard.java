package com.jlas.flashcard.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Thẻ flashcard tự do: front/back do user quyết định; metadata là tùy chọn.
 * Table: flashcard_card
 */
@Entity
@Table(name = "flashcard_card")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlashcardCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "set_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_flashcard_card_set"))
    private FlashcardSet set;

    @Column(name = "front", length = 500)
    private String front;

    @Column(name = "back", length = 500)
    private String back;

    @Column(name = "reading", length = 255)
    private String reading;

    @Column(name = "meaning", length = 500)
    private String meaning;

    @Column(name = "han_viet", length = 255)
    private String hanViet;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /** N5/N4/N3/N2/N1/CUSTOM. */
    @Column(name = "jlpt_level", length = 10)
    private String jlptLevel;

    @Column(name = "word_type", length = 50)
    private String wordType;

    @Column(name = "example_ja", columnDefinition = "TEXT")
    private String exampleJa;

    @Column(name = "example_vi", columnDefinition = "TEXT")
    private String exampleVi;

    @Column(name = "position", nullable = false)
    @Builder.Default
    private Integer position = 0;

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
