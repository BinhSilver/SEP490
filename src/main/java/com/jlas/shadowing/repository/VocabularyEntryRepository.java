package com.jlas.shadowing.repository;

import com.jlas.shadowing.model.VocabularyEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository("shadowingVocabularyEntryRepository")
public interface VocabularyEntryRepository extends JpaRepository<VocabularyEntry, Long> {
    List<VocabularyEntry> findTop5ByExpressionContainingOrReadingContainingOrderByIdAsc(
            String expression, String reading);

    boolean existsByNormalizedExpressionAndNormalizedReading(String expression, String reading);

    @Query(value = """
            select * from vocabulary_entry
            where :text like concat('%', expression, '%')
            order by char_length(expression) desc
            limit 100
            """, nativeQuery = true)
    List<VocabularyEntry> findContainedIn(@Param("text") String text);

    @Query(value = """
            select * from vocabulary_entry
            where :text like concat('%', expression, '%')
              and dictionary_status = 'PUBLISHED'
            order by char_length(expression) desc, id asc
            limit 100
            """, nativeQuery = true)
    List<VocabularyEntry> findPublishedContainedIn(@Param("text") String text);
}
