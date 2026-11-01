package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathGrammarQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Grammar questions. */
@Repository
public interface JpathGrammarQuestionRepository extends JpaRepository<JpathGrammarQuestion, String> {
}
