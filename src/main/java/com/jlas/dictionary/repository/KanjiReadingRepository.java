package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.KanjiReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** Repository cho {@link KanjiReading}. */
@Repository
public interface KanjiReadingRepository extends JpaRepository<KanjiReading, Long> {

    /** Cách đọc On/Kun của nhiều Kanji, 1 query; caller gộp theo kanji_id. */
    List<KanjiReading> findByKanjiIdInOrderByKanjiIdAscReadingTypeAscIdAsc(Collection<Long> kanjiIds);

    List<KanjiReading> findByKanji_IdOrderByReadingTypeAscReadingTextAsc(Long kanjiId);

    List<KanjiReading> findByKanji_IdInOrderByKanji_IdAscReadingTypeAscReadingTextAsc(
            List<Long> kanjiIds);
}
