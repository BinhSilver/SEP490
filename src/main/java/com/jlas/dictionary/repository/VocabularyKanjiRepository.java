package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.VocabularyKanji;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** Repository cho {@link VocabularyKanji}. */
@Repository
public interface VocabularyKanjiRepository extends JpaRepository<VocabularyKanji, VocabularyKanji.VocabularyKanjiId> {

    /**
     * Liên kết từ ↔ Kanji của một tập từ vựng, kèm Kanji và bộ thủ (fetch join), 1 query.
     * Từ kết quả này service suy ra cả tập Kanji của bài lẫn các từ chứa từng chữ.
     */
    @Query("""
            select vk from VocabularyKanji vk
              join fetch vk.kanji k
              left join fetch k.radical
            where vk.id.vocabularyId in :vocabularyIds
            order by k.id, vk.id.vocabularyId, vk.id.characterPosition
            """)
    List<VocabularyKanji> findByVocabularyIdIn(@Param("vocabularyIds") Collection<Long> vocabularyIds);
}
