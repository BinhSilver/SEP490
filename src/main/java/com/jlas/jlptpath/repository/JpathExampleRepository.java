package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathExample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Example sentences of words, kanji and grammar patterns. */
@Repository
public interface JpathExampleRepository extends JpaRepository<JpathExample, String> {
}
