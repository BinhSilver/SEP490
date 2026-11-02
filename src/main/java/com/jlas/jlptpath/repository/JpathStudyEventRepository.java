package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathStudyEvent;
import com.jlas.jlptpath.enums.JpathItemType;
import java.time.LocalDate;
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
}
