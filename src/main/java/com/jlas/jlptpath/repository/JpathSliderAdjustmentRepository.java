package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathSliderAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Slider adjustment history. */
@Repository
public interface JpathSliderAdjustmentRepository extends JpaRepository<JpathSliderAdjustment, Long> {
}
