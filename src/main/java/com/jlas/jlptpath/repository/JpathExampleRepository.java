package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathExample;
import com.jlas.jlptpath.enums.JpathContentStatus;
import com.jlas.jlptpath.enums.JpathItemType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Example sentences of words, kanji and grammar patterns. */
@Repository
public interface JpathExampleRepository extends JpaRepository<JpathExample, String> {

    /** Learner-visible examples of one owner: status in {@code statuses}, not hidden by a report, by sort order. */
    List<JpathExample> findByOwnerTypeAndOwnerCodeAndStatusInAndReportHiddenFalseOrderBySortOrder(
            JpathItemType ownerType, String ownerCode, Collection<JpathContentStatus> statuses);
}
