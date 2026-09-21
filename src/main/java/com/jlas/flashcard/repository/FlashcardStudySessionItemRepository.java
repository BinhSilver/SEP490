package com.jlas.flashcard.repository;

import com.jlas.flashcard.entity.FlashcardStudySessionItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlashcardStudySessionItemRepository extends JpaRepository<FlashcardStudySessionItem, Long> {
    List<FlashcardStudySessionItem> findBySessionIdOrderByPositionAsc(Long sessionId);
    Optional<FlashcardStudySessionItem> findBySessionIdAndCardId(Long sessionId, Long cardId);
}
