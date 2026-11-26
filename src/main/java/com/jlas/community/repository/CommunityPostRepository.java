package com.jlas.community.repository;

import com.jlas.community.entity.CommunityPost;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CommunityPostRepository extends JpaRepository<CommunityPost, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CommunityPost p where p.id = :id")
    Optional<CommunityPost> lockById(@Param("id") Long id);

    /** Nạp một lần cho cả trang feed (cờ "đã chỉnh sửa"), tránh N+1. */
    List<CommunityPost> findByIdIn(Collection<Long> ids);
}
