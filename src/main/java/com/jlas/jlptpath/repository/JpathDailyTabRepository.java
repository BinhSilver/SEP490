package com.jlas.jlptpath.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import com.jlas.jlptpath.entity.JpathDailyTab;
import com.jlas.jlptpath.entity.JpathDailyTabId;
import com.jlas.jlptpath.enums.JpathTab;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Tabs of daily plans. */
@Repository
public interface JpathDailyTabRepository extends JpaRepository<JpathDailyTab, JpathDailyTabId> {

    /** Tabs of the learner's plan for {@code day} with any new, extra or review item done. */
    @Query("""
            select t.tab from JpathDailyTab t, JpathDailyPlan p
            where p.id = t.planId and p.userId = :userId and p.studyDay = :day
              and (t.newDone > 0 or t.extraDone > 0 or t.reviewDone > 0)
            """)
    List<JpathTab> findStartedTabs(@Param("userId") Long userId, @Param("day") LocalDate day);

    /** Tabs of a plan, by tab. */
    List<JpathDailyTab> findByPlanIdOrderByTab(Long planId);

    /**
     * Tabs of a plan read with a locking read, so rows committed after this transaction's snapshot (by the plan
     * inserter's own transaction) are seen.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from JpathDailyTab t where t.planId = :planId")
    List<JpathDailyTab> findForUpdateByPlanId(@Param("planId") Long planId);
}
