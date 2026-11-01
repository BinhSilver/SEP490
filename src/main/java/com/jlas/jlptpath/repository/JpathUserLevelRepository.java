package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserLevelId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Levels inside learner paths. */
@Repository
public interface JpathUserLevelRepository extends JpaRepository<JpathUserLevel, JpathUserLevelId> {
}
