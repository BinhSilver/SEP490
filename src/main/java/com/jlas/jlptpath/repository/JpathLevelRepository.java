package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathLevel;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] JLPT levels of the path and their release state. */
@Repository
public interface JpathLevelRepository extends JpaRepository<JpathLevel, String> {

    List<JpathLevel> findAllByOrderBySortOrderAsc();
}
