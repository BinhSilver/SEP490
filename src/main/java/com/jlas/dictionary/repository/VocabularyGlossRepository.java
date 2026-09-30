package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.VocabularyGloss;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Repository cho {@link VocabularyGloss}. */
@Repository
public interface VocabularyGlossRepository extends JpaRepository<VocabularyGloss, Long> {

    /** Load gloss của nhiều sense cùng lúc, tránh N+1 khi map danh sách kết quả search. */
    List<VocabularyGloss> findBySense_IdIn(List<Long> senseIds);

    /**
     * Tier tìm kiếm cuối cùng (fallback) khi query không phải Kanji/Kana/Romaji: tìm theo nghĩa.
     * Chỉ được gọi khi các tier ưu tiên cao hơn chưa đủ kết quả (xem DictionaryServiceImpl).
     */
    @Query("select distinct g.sense.vocabularyEntry.id from VocabularyGloss g "
            + "where lower(g.glossText) like lower(concat('%', :text, '%')) "
            + "and g.sense.vocabularyEntry.dictionaryStatus = 'PUBLISHED'")
    List<Long> findVocabularyIdsByGlossContaining(@Param("text") String text, Pageable pageable);
}
