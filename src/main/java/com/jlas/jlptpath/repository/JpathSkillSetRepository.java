package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathSkillSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Skill practice sets. Queries are added by the task that uses them. */
@Repository
public interface JpathSkillSetRepository extends JpaRepository<JpathSkillSet, String> {
}
