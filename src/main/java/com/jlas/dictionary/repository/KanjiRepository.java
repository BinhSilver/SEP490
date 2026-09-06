package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.Kanji;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/** Repository cho {@link Kanji}. */
@Repository
public interface KanjiRepository extends JpaRepository<Kanji, Long> {
    Optional<Kanji> findByCharacter(String character);
}
