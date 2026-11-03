package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathSkillSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Skill sessions. Queries are added by the task that uses them. */
@Repository
public interface JpathSkillSessionRepository extends JpaRepository<JpathSkillSession, Long> {
}
