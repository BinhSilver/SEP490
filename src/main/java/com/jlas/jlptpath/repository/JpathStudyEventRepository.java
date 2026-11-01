package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathStudyEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Study events. */
@Repository
public interface JpathStudyEventRepository extends JpaRepository<JpathStudyEvent, Long> {
}
