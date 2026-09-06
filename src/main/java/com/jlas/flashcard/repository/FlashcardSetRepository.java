package com.jlas.flashcard.repository;

import com.jlas.flashcard.entity.FlashcardSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/** Repository cho {@link FlashcardSet}. */
@Repository
public interface FlashcardSetRepository extends JpaRepository<FlashcardSet, Long> {
    List<FlashcardSet> findByOwnerId(Long ownerId);
}
