package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathContentReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Content error reports. */
@Repository
public interface JpathContentReportRepository extends JpaRepository<JpathContentReport, Long> {
}
