package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathMockResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] JLPT mock attempts applied to paths (key = mock attempt id). Queries are added by the task that uses them. */
@Repository
public interface JpathMockResultRepository extends JpaRepository<JpathMockResult, Long> {
}
