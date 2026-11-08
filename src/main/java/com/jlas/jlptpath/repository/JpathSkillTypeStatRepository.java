package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathSkillTypeStat;
import com.jlas.jlptpath.entity.JpathSkillTypeStatId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Per-learner question type statistics. */
@Repository
public interface JpathSkillTypeStatRepository extends JpaRepository<JpathSkillTypeStat, JpathSkillTypeStatId> {

    /** The learner's statistics of these types (types never done have no row). */
    List<JpathSkillTypeStat> findByUserIdAndTypeCodeIn(Long userId, Collection<String> typeCodes);
}
