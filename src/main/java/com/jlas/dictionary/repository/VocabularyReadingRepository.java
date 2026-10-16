package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.entity.VocabularyReading;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository cho {@link VocabularyReading} (các cách đọc phụ của một vocabulary_entry).
 * {@code normalized_reading} là cột utf8mb4_bin (migration 005) nên so sánh trực tiếp, dùng index.
 */
@Repository
public interface VocabularyReadingRepository extends JpaRepository<VocabularyReading, Long> {

    /** Tier fallback: reading chính không khớp, thử các reading phụ. */
    @Query(value = "select distinct ve.* from vocabulary_reading vr "
            + "join vocabulary_entry ve on ve.id = vr.vocabulary_id "
            + "where vr.normalized_reading = :reading "
            + "and ve.dictionary_status = 'PUBLISHED'", nativeQuery = true)
    List<VocabularyEntry> findVocabularyByNormalizedReading(
            @Param("reading") String normalizedReading, Pageable pageable);
}
