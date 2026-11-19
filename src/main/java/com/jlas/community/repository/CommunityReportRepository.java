package com.jlas.community.repository;

import com.jlas.community.entity.CommunityReport;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CommunityReportRepository extends JpaRepository<CommunityReport, Long> {

    boolean existsByReporterIdAndTargetTypeAndTargetId(Long reporterId, String targetType, Long targetId);

    long countByTargetTypeAndTargetIdAndStatus(String targetType, Long targetId, String status);

    List<CommunityReport> findByTargetTypeAndTargetIdAndStatusOrderByCreatedAtAsc(String targetType, Long targetId,
                                                                                   String status);

    /**
     * Báo cáo còn OPEN theo bản commit MỚI NHẤT (đọc có khoá, không theo snapshot của transaction): khi đóng báo cáo, hai
     * kiểm duyệt viên xử lý cùng lúc không đóng lại / ghi đè báo cáo người kia vừa đóng.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CommunityReport r where r.targetType = :type and r.targetId = :targetId and r.status = 'OPEN' "
            + "order by r.createdAt asc, r.id asc")
    List<CommunityReport> lockOpen(@Param("type") String targetType, @Param("targetId") Long targetId);
}
