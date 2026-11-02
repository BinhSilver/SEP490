package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathContentStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Path vocabulary. */
@Repository
public interface JpathVocabularyRepository extends JpaRepository<JpathVocabulary, String> {

    /** Learner-visible rows of a level: status in {@code statuses}, not hidden by a report, by sort order. */
    List<JpathVocabulary> findByLevelAndStatusInAndReportHiddenFalseOrderBySortOrder(
            String level, Collection<JpathContentStatus> statuses);

    List<JpathVocabulary> findByDictionaryEntryIdIn(Collection<Long> dictionaryEntryIds);

    List<JpathVocabulary> findByNormalizedExpressionIn(Collection<String> normalizedExpressions);

    List<JpathVocabulary> findByNormalizedReadingIn(Collection<String> normalizedReadings);

    long countByLevelAndStatusInAndReportHiddenFalse(String level, Collection<JpathContentStatus> statuses);
}
