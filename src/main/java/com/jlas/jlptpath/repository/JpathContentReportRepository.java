package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathContentReport;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathReportTarget;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
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

    /** One reported target of the editor queue (aggregate over its reports of one status). */
    interface TargetGroup {
        JpathReportTarget getTargetType();

        String getTargetCode();

        JpathItemType getItemType();

        String getItemCode();

        String getLevel();

        long getReportCount();

        long getReporterCount();

        LocalDateTime getFirstAt();

        LocalDateTime getLastAt();
    }

    /**
     * Reports of {@code status} grouped by target (optionally of one level), most reported first, then most recently
     * reported. The owner and level of a target are derived from it, so they are equal in every row of a group.
     */
    @Query("select r.targetType as targetType, r.targetCode as targetCode, r.itemType as itemType, "
            + "r.itemCode as itemCode, r.level as level, count(r) as reportCount, "
            + "count(distinct r.reporterUserId) as reporterCount, min(r.createdAt) as firstAt, "
            + "max(r.createdAt) as lastAt from JpathContentReport r "
            + "where r.status = :status and (:level is null or r.level = :level) "
            + "group by r.targetType, r.targetCode, r.itemType, r.itemCode, r.level "
            + "order by count(r) desc, max(r.createdAt) desc, r.targetType, r.targetCode")
    List<TargetGroup> groupByTarget(@Param("status") String status, @Param("level") String level);

    /** Reports of {@code status} on any of {@code codes}, newest first (callers filter by target type). */
    List<JpathContentReport> findByStatusAndTargetCodeInOrderByCreatedAtDescIdDesc(String status,
                                                                                  Collection<String> codes);

    /** Newest reports of a target first. */
    List<JpathContentReport> findByTargetTypeAndTargetCodeOrderByCreatedAtDescIdDesc(JpathReportTarget targetType,
                                                                                    String targetCode, Pageable page);

    List<JpathContentReport> findByTargetTypeAndTargetCodeAndStatus(JpathReportTarget targetType, String targetCode,
                                                                    String status);
}
