package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathSkillQuestion;
import com.jlas.jlptpath.enums.JpathContentStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Skill practice questions. */
@Repository
public interface JpathSkillQuestionRepository extends JpaRepository<JpathSkillQuestion, String> {

    /** Questions of these sets with a visible status and not report-hidden, by set then sort order. */
    List<JpathSkillQuestion> findBySetCodeInAndStatusInAndReportHiddenFalseOrderBySetCodeAscSortOrderAsc(
            Collection<String> setCodes, Collection<JpathContentStatus> statuses);
}
