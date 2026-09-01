package com.jlas.flashcard.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Favorite/rating/lần học đầu theo một user và set.
 * Table: flashcard_user_set_state
 */
@Entity
@Table(name = "flashcard_user_set_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlashcardUserSetState {

    @EmbeddedId
    private FlashcardUserSetStateId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("userId")
    @JoinColumn(name = "user_id",
            foreignKey = @ForeignKey(name = "fk_flashcard_user_set_state_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("setId")
    @JoinColumn(name = "set_id",
            foreignKey = @ForeignKey(name = "fk_flashcard_user_set_state_set"))
    private FlashcardSet set;

    @Column(name = "is_favorite", nullable = false)
    @Builder.Default
    private Boolean isFavorite = false;

    /** 1-5. */
    @Column(name = "rating")
    private Byte rating;

    @Column(name = "first_completed_at")
    private LocalDateTime firstCompletedAt;

    @Column(name = "last_accessed_at")
    private LocalDateTime lastAccessedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // -----------------------------------------------------------------------

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class FlashcardUserSetStateId implements Serializable {
        @Column(name = "user_id")
        private Long userId;

        @Column(name = "set_id")
        private Long setId;
    }
}
