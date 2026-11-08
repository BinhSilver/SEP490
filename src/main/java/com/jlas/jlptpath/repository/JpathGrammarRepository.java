package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathGrammar;
import com.jlas.jlptpath.enums.JpathContentStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Path grammar patterns. */
@Repository
public interface JpathGrammarRepository extends JpaRepository<JpathGrammar, String> {

    /** Learner-visible rows of a level: status in {@code statuses}, not hidden by a report, by sort order. */
    List<JpathGrammar> findByLevelAndStatusInAndReportHiddenFalseAndParentCodeIsNullOrderBySortOrder(
            String level, Collection<JpathContentStatus> statuses);

    long countByLevelAndStatusInAndReportHiddenFalseAndParentCodeIsNull(String level, Collection<JpathContentStatus> statuses);

    /** Visible secondary patterns attached to {@code parentCode}, in teaching order. */
    List<JpathGrammar> findByParentCodeAndStatusInAndReportHiddenFalseOrderBySortOrder(String parentCode,
                                                                                       Collection<JpathContentStatus> statuses);
}
