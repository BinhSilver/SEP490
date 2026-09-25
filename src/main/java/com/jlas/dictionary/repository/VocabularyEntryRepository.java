package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.VocabularyEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/** Repository cho {@link VocabularyEntry}. */
@Repository
public interface VocabularyEntryRepository extends JpaRepository<VocabularyEntry, Long> {
    List<VocabularyEntry> findByReadingContainingIgnoreCase(String keyword);

    /** Published entries with the same spelling, shown to moderators to avoid duplicates. */
    List<VocabularyEntry> findTop5ByNormalizedExpressionAndDictionaryStatusAndIdNot(
            String normalizedExpression, String dictionaryStatus, Long id);
}
