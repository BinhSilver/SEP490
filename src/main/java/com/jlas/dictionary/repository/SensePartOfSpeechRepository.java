package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.SensePartOfSpeech;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Repository cho {@link SensePartOfSpeech} (từ loại của từng sense). */
@Repository
public interface SensePartOfSpeechRepository extends JpaRepository<SensePartOfSpeech, SensePartOfSpeech.SensePosId> {

    @Query("select sp from SensePartOfSpeech sp join fetch sp.partOfSpeech where sp.id.senseId in :senseIds")
    List<SensePartOfSpeech> findBySenseIdIn(@Param("senseIds") List<Long> senseIds);
}
