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
 * {@code normalized_reading} dùng utf8mb4_0900_as_cs (accent/case-sensitive, phân biệt
 * dakuten); query so sánh trực tiếp, không ép COLLATE, để dùng index (migration 059).
 */
@Repository
public interface VocabularyReadingRepository extends JpaRepository<VocabularyReading, Long> {

    /** Tier fallback: reading chính không khớp, thử các reading phụ. */
    @Query(value = "select distinct ve.* from vocabulary_reading vr "
            + "join vocabulary_entry ve on ve.id = vr.vocabulary_id "
            + "where vr.normalized_reading = :reading "
            + "and ve.dictionary_status = 'PUBLISHED' and ve.usage_scope <> 'TEXTBOOK'", nativeQuery = true)
    List<VocabularyEntry> findVocabularyByNormalizedReading(
            @Param("reading") String normalizedReading, Pageable pageable);
}
