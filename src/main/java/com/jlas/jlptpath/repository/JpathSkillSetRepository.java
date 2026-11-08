package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathSkillSet;
import com.jlas.jlptpath.enums.JpathContentStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Skill practice sets. */
@Repository
public interface JpathSkillSetRepository extends JpaRepository<JpathSkillSet, String> {

    /** Sets of these types with a visible status and not report-hidden, by sort order. */
    List<JpathSkillSet> findByTypeCodeInAndStatusInAndReportHiddenFalseOrderBySortOrderAscCodeAsc(
            Collection<String> typeCodes, Collection<JpathContentStatus> statuses);
}
