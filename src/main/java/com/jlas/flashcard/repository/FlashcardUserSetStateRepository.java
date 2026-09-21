package com.jlas.flashcard.repository;

import com.jlas.flashcard.entity.FlashcardUserSetState;
import com.jlas.flashcard.entity.FlashcardUserSetState.FlashcardUserSetStateId;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlashcardUserSetStateRepository extends JpaRepository<FlashcardUserSetState, FlashcardUserSetStateId> {
    Optional<FlashcardUserSetState> findByUserIdAndSetId(Long userId, Long setId);
}
