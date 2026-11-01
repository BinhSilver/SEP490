package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathStudySession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Study sessions. */
@Repository
public interface JpathStudySessionRepository extends JpaRepository<JpathStudySession, Long> {
}
