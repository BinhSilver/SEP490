package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathSkillSetUse;
import com.jlas.jlptpath.entity.JpathSkillSetUseId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Practice sets a learner has used. Queries are added by the task that uses them. */
@Repository
public interface JpathSkillSetUseRepository extends JpaRepository<JpathSkillSetUse, JpathSkillSetUseId> {
}
