package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathSkillLesson;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Skill strategy lessons (key = type code). Queries are added by the task that uses them. */
@Repository
public interface JpathSkillLessonRepository extends JpaRepository<JpathSkillLesson, String> {
}
