package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathKanjiCompound;
import com.jlas.jlptpath.entity.JpathKanjiCompoundId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Compound words of a kanji. */
@Repository
public interface JpathKanjiCompoundRepository extends JpaRepository<JpathKanjiCompound, JpathKanjiCompoundId> {
}
