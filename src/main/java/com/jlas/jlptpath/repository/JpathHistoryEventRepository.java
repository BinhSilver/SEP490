package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathStudyEvent;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * [JLAS-W0108] Read-only aggregates of study events for the history, stats and heatmap (grouped in SQL by study day,
 * never one query per day). "New learned" and "reviewed" are not here: they are
 * {@link JpathStudyEventRepository#newLearnedByDay} and {@link JpathStudyEventRepository#reviewedByDay}.
 */
@org.springframework.stereotype.Repository
public interface JpathHistoryEventRepository extends Repository<JpathStudyEvent, Long> {

    /**
     * Per (study_day, action) in {@code [from, to]}: {@code [LocalDate day, String action, Long events, Long correct]},
     * where {@code correct} counts the events with correct = true.
     */
    @Query("""
            select e.studyDay, e.action, count(e), sum(case when e.correct = true then 1 else 0 end)
            from JpathStudyEvent e
            where e.userId = :userId and e.studyDay between :from and :to
            group by e.studyDay, e.action
            """)
    List<Object[]> countByDayAndAction(@Param("userId") Long userId, @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);

    /** CHECK_ANSWER per item type in {@code [from, to]}: {@code [JpathItemType type, Long answers, Long correct]}. */
    @Query("""
            select e.itemType, count(e), sum(case when e.correct = true then 1 else 0 end)
            from JpathStudyEvent e
            where e.userId = :userId and e.studyDay between :from and :to and e.action = 'CHECK_ANSWER'
            group by e.itemType
            """)
    List<Object[]> checkAnswersByItemType(@Param("userId") Long userId, @Param("from") LocalDate from,
                                          @Param("to") LocalDate to);

    /** Every event of the learner on {@code day}, oldest first. */
    List<JpathStudyEvent> findByUserIdAndStudyDayOrderByIdAsc(Long userId, LocalDate studyDay);
}
