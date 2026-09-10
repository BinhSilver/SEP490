package com.jlas.moderation.repository;

import com.jlas.moderation.model.ModerationReviewQueue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModerationReviewQueueRepository extends JpaRepository<ModerationReviewQueue, Long> {

    List<ModerationReviewQueue> findByStatusOrderByCreatedAtDesc(String status);

    Optional<ModerationReviewQueue> findFirstByTargetTypeAndTargetIdAndStatus(
            String targetType, Long targetId, String status);
}
