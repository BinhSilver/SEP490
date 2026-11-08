package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathSkillDictation;
import com.jlas.jlptpath.enums.JpathContentStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Skill dictation lines. Queries are added by the task that uses them. */
@Repository
public interface JpathSkillDictationRepository extends JpaRepository<JpathSkillDictation, String> {

    /** Visible lines of {@code setCodes} (Plan 3 Task 14), set by set in sort order. */
    List<JpathSkillDictation> findBySetCodeInAndStatusInAndReportHiddenFalseOrderBySetCodeAscSortOrderAsc(
            Collection<String> setCodes, Collection<JpathContentStatus> statuses);
}
