package com.jlas.flashcard.repository;

import com.jlas.moderation.entity.ContentComment;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Flashcard-owned access to shared content comments. */
@Repository
public interface FlashcardContentCommentRepository extends JpaRepository<ContentComment, Long> {
    List<ContentComment> findByTargetIdOrderByCreatedAtDesc(Long targetId);

    Page<ContentComment> findByTargetTypeAndTargetIdAndIsHiddenFalseOrderByCreatedAtDesc(
            String targetType, Long targetId, Pageable pageable);
}
