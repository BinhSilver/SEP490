package com.jlas.moderation.repository;

import com.jlas.moderation.entity.ModerationAuditLog;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModerationAuditLogRepository extends JpaRepository<ModerationAuditLog, Long> {

    @Query("""
            select a from ModerationAuditLog a
            where (:actorId is null or a.actorUserId = :actorId)
              and (:action is null or a.action = :action)
              and (:targetType is null or a.targetType = :targetType)
              and (:targetId is null or a.targetId = :targetId)
              and (:from is null or a.createdAt >= :from)
              and (:to is null or a.createdAt < :to)
            order by a.createdAt desc, a.id desc
            """)
    Page<ModerationAuditLog> search(@Param("actorId") Long actorId,
                                    @Param("action") String action,
                                    @Param("targetType") String targetType,
                                    @Param("targetId") Long targetId,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to,
                                    Pageable pageable);

    /** The decision currently in force for a review case: the newest of the given actions. */
    Optional<ModerationAuditLog> findFirstByCaseIdAndActionInOrderByIdDesc(Long caseId, Collection<String> actions);

    /** Newest transition of a target into a state (e.g. why community content was REMOVED), for the owner's banner. */
    Optional<ModerationAuditLog> findFirstByTargetTypeAndTargetIdAndToStateOrderByIdDesc(String targetType,
                                                                                       Long targetId, String toState);
}
