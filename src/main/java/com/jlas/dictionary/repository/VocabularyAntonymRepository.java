package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.VocabularyAntonym;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VocabularyAntonymRepository extends JpaRepository<VocabularyAntonym, Long> {
    List<VocabularyAntonym> findByVocabularyIdOrderByIdAsc(Long vocabularyId);
}
