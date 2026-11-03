package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathSkillQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Skill practice questions. Queries are added by the task that uses them. */
@Repository
public interface JpathSkillQuestionRepository extends JpaRepository<JpathSkillQuestion, String> {
}
