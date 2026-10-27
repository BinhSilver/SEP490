package com.jlas.shadowing.repository;

import com.jlas.shadowing.model.VideoReport;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface VideoReportRepository extends JpaRepository<VideoReport, Long> {
    boolean existsByVideoIdAndReporterIdAndStatus(Long videoId, Long reporterId, String status);

    long countByReporterIdAndCreatedAtAfter(Long reporterId, LocalDateTime since);

    long countByVideoIdAndStatus(Long videoId, String status);

    List<VideoReport> findByVideoIdAndStatus(Long videoId, String status);

    List<VideoReport> findByVideoIdInAndStatus(Collection<Long> videoIds, String status);
}
