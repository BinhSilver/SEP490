package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.DictionaryFavorite;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DictionaryFavoriteRepository extends JpaRepository<DictionaryFavorite, DictionaryFavorite.DictionaryFavoriteId> {
    Page<DictionaryFavorite> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** Atomic idempotency boundary: the composite PK resolves simultaneous quick-save requests. */
    @Modifying
    @Query(value = """
            INSERT IGNORE INTO dictionary_favorite (user_id, vocabulary_id, created_at)
            VALUES (:userId, :vocabularyId, CURRENT_TIMESTAMP)
            """, nativeQuery = true)
    int insertIgnore(@Param("userId") Long userId, @Param("vocabularyId") Long vocabularyId);
}
