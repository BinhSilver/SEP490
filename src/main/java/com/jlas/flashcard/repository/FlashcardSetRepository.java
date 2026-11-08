package com.jlas.flashcard.repository;

import com.jlas.flashcard.entity.FlashcardSet;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlashcardSetRepository extends JpaRepository<FlashcardSet, Long> {
    List<FlashcardSet> findByOwnerId(Long ownerId);

    @Query("""
            select set from FlashcardSet set
            where set.deletedAt is null and set.visibility = 'PUBLIC' and set.draft = false
              and (:title is null or lower(set.title) like lower(concat('%', :title, '%')))
            """)
    Page<FlashcardSet> findPublic(@Param("title") String title, Pageable pageable);

    @Query("""
            select set from FlashcardSet set
            where set.deletedAt is null and set.owner.id = :ownerId
              and (:title is null or lower(set.title) like lower(concat('%', :title, '%')))
              and (:folderId is null
                   or (:folderId = 0 and set.folder is null)
                   or set.folder.id = :folderId)
            """)
    Page<FlashcardSet> findMine(@Param("ownerId") Long ownerId, @Param("title") String title,
                                @Param("folderId") Long folderId, Pageable pageable);

    /** Sets the user marked as favorite and can still open: their own, or public and published. */
    @Query(value = """
            select set from FlashcardSet set, FlashcardUserSetState state
            where state.set = set and state.user.id = :userId and state.isFavorite = true
              and set.deletedAt is null
              and (set.owner.id = :userId or (set.visibility = 'PUBLIC' and set.draft = false))
              and (:title is null or lower(set.title) like lower(concat('%', :title, '%')))
            """, countQuery = """
            select count(set) from FlashcardSet set, FlashcardUserSetState state
            where state.set = set and state.user.id = :userId and state.isFavorite = true
              and set.deletedAt is null
              and (set.owner.id = :userId or (set.visibility = 'PUBLIC' and set.draft = false))
              and (:title is null or lower(set.title) like lower(concat('%', :title, '%')))
            """)
    Page<FlashcardSet> findFavorites(@Param("userId") Long userId, @Param("title") String title, Pageable pageable);

    long countByFolderIdAndDeletedAtIsNull(Long folderId);
}
