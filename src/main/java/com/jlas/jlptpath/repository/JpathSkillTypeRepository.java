package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathSkillType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Skill question types (no status: a type is shown through its lesson and sets). */
@Repository
public interface JpathSkillTypeRepository extends JpaRepository<JpathSkillType, String> {

    /** Question types of {@code level} (any order). */
    List<JpathSkillType> findByLevel(String level);
}
