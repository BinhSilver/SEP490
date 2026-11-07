package com.jlas.admin.repository;

import com.jlas.admin.entity.AdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long>,
        JpaSpecificationExecutor<AdminAuditLog> {
    /** Integrity check walks the log in id order, one batch at a time. */
    List<AdminAuditLog> findTop500ByIdGreaterThanOrderByIdAsc(Long afterId);
}
