package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathKanji;
import com.jlas.jlptpath.enums.JpathContentStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Path kanji. */
@Repository
public interface JpathKanjiRepository extends JpaRepository<JpathKanji, String> {

    /** Learner-visible rows of a level: status in {@code statuses}, not hidden by a report, by sort order. */
    List<JpathKanji> findByLevelAndStatusInAndReportHiddenFalseOrderBySortOrder(
            String level, Collection<JpathContentStatus> statuses);

    /** The kanji written {@code character} (binary compare), whatever its status. */
    Optional<JpathKanji> findByCharacter(String character);

    /** The kanji written as any of {@code characters}, whatever their status. */
    List<JpathKanji> findByCharacterIn(Collection<String> characters);

    long countByLevelAndStatusInAndReportHiddenFalse(String level, Collection<JpathContentStatus> statuses);
}
