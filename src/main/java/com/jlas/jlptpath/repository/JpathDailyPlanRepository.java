package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathDailyPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Daily study plans. */
@Repository
public interface JpathDailyPlanRepository extends JpaRepository<JpathDailyPlan, Long> {
}
