package com.jlas.flashcard.repository;

import com.jlas.flashcard.entity.FlashcardStudySession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlashcardStudySessionRepository extends JpaRepository<FlashcardStudySession, Long> {
    long countBySetIdAndStatus(Long setId, String status);
}
