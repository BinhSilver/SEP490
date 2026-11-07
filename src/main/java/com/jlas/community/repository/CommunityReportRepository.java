package com.jlas.community.repository;

import com.jlas.community.entity.CommunityReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommunityReportRepository extends JpaRepository<CommunityReport, Long> {

    boolean existsByReporterIdAndTargetTypeAndTargetId(Long reporterId, String targetType, Long targetId);

    long countByTargetTypeAndTargetIdAndStatus(String targetType, Long targetId, String status);

    List<CommunityReport> findByTargetTypeAndTargetIdAndStatusOrderByCreatedAtAsc(String targetType, Long targetId,
                                                                                   String status);
}
