package com.jlas.flashcard.repository;

import com.jlas.moderation.entity.ContentCommentRating;
import org.springframework.data.jpa.repository.JpaRepository;

/** Flashcard-owned access to ratings attached to shared content comments. */
public interface FlashcardContentCommentRatingRepository
        extends JpaRepository<ContentCommentRating, Long> {
}
