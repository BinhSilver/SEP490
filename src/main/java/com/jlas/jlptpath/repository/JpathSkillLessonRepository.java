package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathSkillLesson;
import com.jlas.jlptpath.enums.JpathContentStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Skill strategy lessons (key = type code). */
@Repository
public interface JpathSkillLessonRepository extends JpaRepository<JpathSkillLesson, String> {

    /** Lessons of these types with a visible status and not report-hidden. */
    List<JpathSkillLesson> findByTypeCodeInAndStatusInAndReportHiddenFalse(Collection<String> typeCodes,
            Collection<JpathContentStatus> statuses);
}
