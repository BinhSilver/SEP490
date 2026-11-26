package com.jlas.moderation.repository;

import com.jlas.moderation.entity.UserBulkViolation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface UserBulkViolationRepository extends JpaRepository<UserBulkViolation, Long> {

    /** Số lần vi phạm chưa được admin gỡ kể từ {@code since}: quyết định mức xử lý tiếp theo. */
    @Query("""
            select count(v) from UserBulkViolation v
            where v.userId = :userId and v.clearedAt is null and v.createdAt >= :since
            """)
    long countActiveSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    List<UserBulkViolation> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<UserBulkViolation> findByUserIdOrderByCreatedAtDesc(Long userId);
}
