package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathCheckSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Issued check sets. */
@Repository
public interface JpathCheckSetRepository extends JpaRepository<JpathCheckSet, Long> {
}
