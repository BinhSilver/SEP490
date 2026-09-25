package com.jlas.flashcard.repository;

import com.jlas.flashcard.entity.FlashcardCardProgress;
import com.jlas.flashcard.entity.FlashcardCardProgress.FlashcardCardProgressId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlashcardCardProgressRepository extends JpaRepository<FlashcardCardProgress, FlashcardCardProgressId> {
    List<FlashcardCardProgress> findByUserIdAndCardSetId(Long userId, Long setId);
}
