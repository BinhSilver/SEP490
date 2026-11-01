package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathDailyTab;
import com.jlas.jlptpath.entity.JpathDailyTabId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Tabs of daily plans. */
@Repository
public interface JpathDailyTabRepository extends JpaRepository<JpathDailyTab, JpathDailyTabId> {
}
