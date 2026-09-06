package com.jlas.shadowing.repository;

import com.jlas.shadowing.entity.ShadowingSeries;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository cho {@link ShadowingSeries}. */
@Repository
public interface ShadowingSeriesRepository extends JpaRepository<ShadowingSeries, Long> { }
