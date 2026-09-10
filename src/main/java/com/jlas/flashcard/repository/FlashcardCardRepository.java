package com.jlas.flashcard.repository;

import com.jlas.flashcard.entity.FlashcardCard;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlashcardCardRepository extends JpaRepository<FlashcardCard, Long> {
    List<FlashcardCard> findBySetId(Long setId);
    List<FlashcardCard> findBySetIdOrderByPositionAscIdAsc(Long setId);
    long countBySetId(Long setId);
}
