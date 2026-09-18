package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.KanjiReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Repository cho {@link KanjiReading}. */
@Repository
public interface KanjiReadingRepository extends JpaRepository<KanjiReading, Long> {

    List<KanjiReading> findByKanji_IdOrderByReadingTypeAscReadingTextAsc(Long kanjiId);

    List<KanjiReading> findByKanji_IdInOrderByKanji_IdAscReadingTypeAscReadingTextAsc(
            List<Long> kanjiIds);
}
