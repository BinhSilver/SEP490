package com.jlas.flashcard.repository;

import com.jlas.flashcard.entity.FlashcardFolder;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlashcardFolderRepository extends JpaRepository<FlashcardFolder, Long> {
    List<FlashcardFolder> findByOwnerIdOrderByNameAsc(Long ownerId);
    Optional<FlashcardFolder> findByIdAndOwnerId(Long id, Long ownerId);
    boolean existsByOwnerIdAndNameIgnoreCase(Long ownerId, String name);
}
