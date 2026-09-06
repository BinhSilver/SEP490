package com.jlas.flashcard.repository;

import com.jlas.flashcard.entity.FlashcardCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/** Repository cho {@link FlashcardCard}. */
@Repository
public interface FlashcardCardRepository extends JpaRepository<FlashcardCard, Long> {
    List<FlashcardCard> findBySetId(Long setId);
}
