package com.jlas.community.repository;

import com.jlas.community.entity.CommunityRating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CommunityRatingRepository extends JpaRepository<CommunityRating, Long> {

    Optional<CommunityRating> findByCommunityContentIdAndUserId(Long communityContentId, Long userId);

    long countByCommunityContentId(Long communityContentId);

    @Query("select coalesce(avg(r.score), 0) from CommunityRating r where r.communityContentId = :id")
    Double averageScore(@Param("id") Long communityContentId);
}
