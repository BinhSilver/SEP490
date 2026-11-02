package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathDailyPlan;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Daily study plans. */
@Repository
public interface JpathDailyPlanRepository extends JpaRepository<JpathDailyPlan, Long> {

    /** Plans of the user with {@code from <= study_day <= to}. */
    List<JpathDailyPlan> findByUserIdAndStudyDayBetween(Long userId, LocalDate from, LocalDate to);

    /** The user's plan for {@code studyDay} (at most one: unique user_id, study_day). */
    Optional<JpathDailyPlan> findByUserIdAndStudyDay(Long userId, LocalDate studyDay);

    /**
     * Restart: bulk-deletes every daily plan of the learner (all paths) and returns the row count (managed entities
     * are not detached). Their jpath_daily_tab rows go with them (FK ON DELETE CASCADE).
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from JpathDailyPlan p where p.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
