package com.jlas.flashcard.repository;

import com.jlas.flashcard.entity.FlashcardStudySession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository cho {@link FlashcardStudySession}. */
@Repository
public interface FlashcardStudySessionRepository extends JpaRepository<FlashcardStudySession, Long> { }
