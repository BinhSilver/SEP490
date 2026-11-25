package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.DictionaryCommentReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DictionaryCommentReportRepository extends JpaRepository<DictionaryCommentReport, Long> {
    boolean existsByCommentIdAndReporterId(Long commentId, Long reporterId);

    long countByCommentIdAndStatus(Long commentId, String status);

    List<DictionaryCommentReport> findByCommentIdAndStatus(Long commentId, String status);
}
