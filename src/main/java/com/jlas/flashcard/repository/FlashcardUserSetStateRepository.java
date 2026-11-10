package com.jlas.flashcard.repository;

import com.jlas.flashcard.entity.FlashcardUserSetState;
import com.jlas.flashcard.entity.FlashcardUserSetState.FlashcardUserSetStateId;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlashcardUserSetStateRepository extends JpaRepository<FlashcardUserSetState, FlashcardUserSetStateId> {
    Optional<FlashcardUserSetState> findByUserIdAndSetId(Long userId, Long setId);
    List<FlashcardUserSetState> findBySetIdAndRatingIsNotNull(Long setId);
    List<FlashcardUserSetState> findByUserIdAndSetIdIn(Long userId, java.util.Collection<Long> setIds);
}
