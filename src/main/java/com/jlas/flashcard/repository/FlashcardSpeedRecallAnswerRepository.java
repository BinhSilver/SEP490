package com.jlas.flashcard.repository;

import com.jlas.flashcard.entity.FlashcardSpeedRecallAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FlashcardSpeedRecallAnswerRepository extends JpaRepository<FlashcardSpeedRecallAnswer, Long> {
    List<FlashcardSpeedRecallAnswer> findBySessionIdOrderByQuestionIndexAsc(Long sessionId);
}
