package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathSkillType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Skill question types. Queries are added by the task that uses them. */
@Repository
public interface JpathSkillTypeRepository extends JpaRepository<JpathSkillType, String> {
}
