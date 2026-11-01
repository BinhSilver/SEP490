package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathVocabTopic;
import com.jlas.jlptpath.entity.JpathVocabTopicId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Vocabulary topics of a level. */
@Repository
public interface JpathVocabTopicRepository extends JpaRepository<JpathVocabTopic, JpathVocabTopicId> {
}
