package com.jlas.moderation.repository;

import com.jlas.moderation.entity.ReviewCase;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository cho {@link ReviewCase}. */
@Repository
public interface ReviewCaseRepository extends JpaRepository<ReviewCase, Long> {
    List<ReviewCase> findByCaseTypeAndStatusOrderByCreatedAtDesc(String caseType, String status);

    List<ReviewCase> findByCaseTypeAndRequesterUserIdOrderByCreatedAtDesc(
            String caseType, Long requesterUserId);

    Optional<ReviewCase> findFirstByCaseTypeAndRequesterUserIdAndStatusOrderByCreatedAtDesc(
            String caseType, Long requesterUserId, String status);

    @Query("""
            select c from ReviewCase c
            left join fetch c.moderationLog
            left join fetch c.ownerUser
            where c.caseType = 'CONTENT_REVIEW' and c.status = 'OPEN'
            order by c.createdAt desc
            """)
    List<ReviewCase> findOpenModerationQueue();

    @Query("""
            select c from ReviewCase c
            left join fetch c.requesterUser
            left join fetch c.reviewer
            where c.caseType = 'APPEAL'
              and (:status is null or c.status = :status)
            order by c.createdAt desc
            """)
    List<ReviewCase> findAppeals(@Param("status") String status);
}
