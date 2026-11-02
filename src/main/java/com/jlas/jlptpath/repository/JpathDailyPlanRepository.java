package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathDailyPlan;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Daily study plans. */
@Repository
public interface JpathDailyPlanRepository extends JpaRepository<JpathDailyPlan, Long> {

    /** Plans of the user with {@code from <= study_day <= to}. */
    List<JpathDailyPlan> findByUserIdAndStudyDayBetween(Long userId, LocalDate from, LocalDate to);
}
