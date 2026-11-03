package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathStudyEvent;
import com.jlas.jlptpath.enums.JpathItemType;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Study events. */
@Repository
public interface JpathStudyEventRepository extends JpaRepository<JpathStudyEvent, Long> {

    /**
     * Restart: bulk-deletes every study event of the learner and returns the row count (managed entities are not
     * detached).
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from JpathStudyEvent e where e.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);

    /** True when the learner already rated this item FORGOT (FIRST_RATE or REVIEW_RATE) on {@code day}. */
    @Query("""
            select count(e) > 0 from JpathStudyEvent e
            where e.userId = :userId and e.itemType = :t and e.itemCode = :code
              and e.action in ('FIRST_RATE', 'REVIEW_RATE')
              and e.rating = com.jlas.jlptpath.enums.JpathRating.FORGOT
              and e.studyDay = :day
            """)
    boolean existsForgotToday(@Param("userId") Long userId, @Param("t") JpathItemType t,
                              @Param("code") String code, @Param("day") LocalDate day);

    /**
     * Distinct (study_day, item_type, item_code) of items newly learned in {@code [from, to]}: a FIRST_RATE or a
     * correct SKIP_KNOWN. Read through {@link #newLearnedByDay}.
     */
    @Query("""
            select distinct e.studyDay, e.itemType, e.itemCode from JpathStudyEvent e
            where e.userId = :userId and e.studyDay between :from and :to
              and (e.action = 'FIRST_RATE' or (e.action = 'SKIP_KNOWN' and e.correct = true))
            """)
    List<Object[]> findNewLearnedKeys(@Param("userId") Long userId, @Param("from") LocalDate from,
                                      @Param("to") LocalDate to);

    /**
     * "New learned" per study day in {@code [from, to]}: distinct items with a FIRST_RATE or a correct SKIP_KNOWN
     * that day. The one definition used by the day summary and the history; days without any are absent.
     */
    default Map<LocalDate, Integer> newLearnedByDay(Long userId, LocalDate from, LocalDate to) {
        Map<LocalDate, Integer> out = new TreeMap<>();
        for (Object[] row : findNewLearnedKeys(userId, from, to)) {
            out.merge((LocalDate) row[0], 1, Integer::sum);
        }
        return out;
    }

    /**
     * Distinct (study_day, item_type, item_code) of items reviewed in {@code [from, to]}: a REVIEW_RATE or a RETYPE.
     * Read through {@link #reviewedByDay}.
     */
    @Query("""
            select distinct e.studyDay, e.itemType, e.itemCode from JpathStudyEvent e
            where e.userId = :userId and e.studyDay between :from and :to
              and e.action in ('REVIEW_RATE', 'RETYPE')
            """)
    List<Object[]> findReviewedKeys(@Param("userId") Long userId, @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);

    /**
     * "Reviewed" per study day in {@code [from, to]}: distinct items with a REVIEW_RATE or RETYPE that day (an item
     * reviewed twice counts once). Days without any are absent.
     */
    default Map<LocalDate, Integer> reviewedByDay(Long userId, LocalDate from, LocalDate to) {
        Map<LocalDate, Integer> out = new TreeMap<>();
        for (Object[] row : findReviewedKeys(userId, from, to)) {
            out.merge((LocalDate) row[0], 1, Integer::sum);
        }
        return out;
    }

    /** CHECK_ANSWER (or another action) events of {@code day} with the given {@code correct}. */
    long countByUserIdAndStudyDayAndActionAndCorrect(Long userId, LocalDate studyDay, String action,
                                                     Boolean correct);

    /** Wrong events of {@code day}, oldest first: a wrong CHECK_ANSWER, or FIRST_RATE / REVIEW_RATE rated FORGOT. */
    @Query("""
            select e from JpathStudyEvent e
            where e.userId = :userId and e.studyDay = :day
              and ((e.action = 'CHECK_ANSWER' and e.correct = false)
                or (e.action in ('FIRST_RATE', 'REVIEW_RATE')
                    and e.rating = com.jlas.jlptpath.enums.JpathRating.FORGOT))
            order by e.id
            """)
    List<JpathStudyEvent> findWrongOn(@Param("userId") Long userId, @Param("day") LocalDate day);
}
