package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathUserSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Display settings of learners. */
@Repository
public interface JpathUserSettingsRepository extends JpaRepository<JpathUserSettings, Long> {
}
