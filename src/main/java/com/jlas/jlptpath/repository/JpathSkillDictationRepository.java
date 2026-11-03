package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathSkillDictation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Skill dictation lines. Queries are added by the task that uses them. */
@Repository
public interface JpathSkillDictationRepository extends JpaRepository<JpathSkillDictation, String> {
}
