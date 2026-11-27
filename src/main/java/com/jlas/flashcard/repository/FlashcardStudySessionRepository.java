package com.jlas.flashcard.repository;

import com.jlas.flashcard.entity.FlashcardStudySession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlashcardStudySessionRepository extends JpaRepository<FlashcardStudySession, Long> {
    // Hibernate 7 treats the persisted VARCHAR status as a string; JPQL must compare it to the string value.
    java.util.Optional<FlashcardStudySession> findByUserIdAndCreationRequestId(Long userId, String requestId);
    long countBySetIdAndStatus(Long setId, String status);
    boolean existsByUserIdAndSetIdAndSessionTypeAndModeAndStatus(Long userId, Long setId,
            String sessionType, String mode, String status);
}
