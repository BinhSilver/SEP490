package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathSkillSetUse;
import com.jlas.jlptpath.entity.JpathSkillSetUseId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Practice sets a learner has used. */
@Repository
public interface JpathSkillSetUseRepository extends JpaRepository<JpathSkillSetUse, JpathSkillSetUseId> {

    /** The learner's use rows of these sets (sets never used have no row). */
    List<JpathSkillSetUse> findByUserIdAndSetCodeIn(Long userId, Collection<String> setCodes);
}
