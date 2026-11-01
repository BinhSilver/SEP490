package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathContentEdit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Content edit log. */
@Repository
public interface JpathContentEditRepository extends JpaRepository<JpathContentEdit, Long> {
}
