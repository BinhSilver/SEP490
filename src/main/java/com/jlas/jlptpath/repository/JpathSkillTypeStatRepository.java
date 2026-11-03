package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathSkillTypeStat;
import com.jlas.jlptpath.entity.JpathSkillTypeStatId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Per-learner question type statistics. Queries are added by the task that uses them. */
@Repository
public interface JpathSkillTypeStatRepository extends JpaRepository<JpathSkillTypeStat, JpathSkillTypeStatId> {
}
