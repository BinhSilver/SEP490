package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathGrammarUnit;
import com.jlas.jlptpath.entity.JpathGrammarUnitId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Grammar units of a level. */
@Repository
public interface JpathGrammarUnitRepository extends JpaRepository<JpathGrammarUnit, JpathGrammarUnitId> {
}
