package com.jlas.admin.dto;

import com.jlas.admin.entity.AdminAuditLog;
import java.time.LocalDateTime;

public record AdminAuditLogView(Long id, Long actorId, String actorEmail, String actorRole, String action,
                                String targetType, String targetId, String reason, String result,
                                String changesJson, String ipAddress, LocalDateTime createdAt) {
    public static AdminAuditLogView from(AdminAuditLog log) {
        return new AdminAuditLogView(log.getId(), log.getActor().getId(), log.getActor().getEmail(), log.getActorRole(),
                log.getAction(), log.getTargetType(), log.getTargetId(), log.getReason(), log.getResult(),
                log.getChangesJson(), log.getIpAddress(), log.getCreatedAt());
    }
}
