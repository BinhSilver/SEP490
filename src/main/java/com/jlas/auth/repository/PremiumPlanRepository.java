package com.jlas.auth.repository;

import com.jlas.auth.entity.PremiumPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository thao tác dữ liệu cho {@link PremiumPlan}.
 */
@Repository
public interface PremiumPlanRepository extends JpaRepository<PremiumPlan, Long> {
}
