package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathContentReport;
import com.jlas.jlptpath.enums.JpathReportTarget;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Content error reports. */
@Repository
public interface JpathContentReportRepository extends JpaRepository<JpathContentReport, Long> {

    /** Number of distinct learners with an OPEN report on the target. */
    @Query("select count(distinct r.reporterUserId) from JpathContentReport r "
            + "where r.targetType = :targetType and r.targetCode = :targetCode and r.status = 'OPEN'")
    long countDistinctOpenReporters(@Param("targetType") JpathReportTarget targetType,
                                    @Param("targetCode") String targetCode);

    boolean existsByReporterUserIdAndTargetTypeAndTargetCodeAndStatus(Long reporterUserId,
            JpathReportTarget targetType, String targetCode, String status);

    /** Reports of a learner created at or after {@code since} (server time). */
    long countByReporterUserIdAndCreatedAtGreaterThanEqual(Long reporterUserId, LocalDateTime since);
}
