package com.jlas.moderation.repository;

import com.jlas.moderation.model.ContentModerationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContentModerationLogRepository extends JpaRepository<ContentModerationLog, Long> {

    Optional<ContentModerationLog> findFirstByContentHashOrderByCreatedAtDesc(String contentHash);

    List<ContentModerationLog> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType, Long targetId);
}
