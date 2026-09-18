package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.VocabularySense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Repository cho {@link VocabularySense}. */
@Repository
public interface VocabularySenseRepository extends JpaRepository<VocabularySense, Long> {

    List<VocabularySense> findByVocabularyEntry_IdOrderBySenseOrderAsc(Long vocabularyId);

    /** Load sense của nhiều vocabulary cùng lúc, tránh N+1 khi map danh sách kết quả search. */
    List<VocabularySense> findByVocabularyEntry_IdInOrderByVocabularyEntry_IdAscSenseOrderAsc(
            List<Long> vocabularyIds);
}
