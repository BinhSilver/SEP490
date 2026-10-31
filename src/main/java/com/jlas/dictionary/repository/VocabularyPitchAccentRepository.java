package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.VocabularyPitchAccent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface VocabularyPitchAccentRepository extends JpaRepository<VocabularyPitchAccent, Long> {
    interface PitchAccentRow {
        String getAccents();
        String getSourceCode();
    }

    @Query(value = "SELECT p.accents AS accents, s.code AS sourceCode " +
            "FROM vocabulary_pitch_accent p JOIN dictionary_source s ON s.id = p.source_id " +
            "WHERE p.vocabulary_id = :vocabularyId", nativeQuery = true)
    Optional<PitchAccentRow> findDetail(@Param("vocabularyId") Long vocabularyId);
}
