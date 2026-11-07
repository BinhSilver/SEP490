package com.jlas.admin.repository;

import com.jlas.admin.entity.AdminAuditChain;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AdminAuditChainRepository extends JpaRepository<AdminAuditChain, Byte> {
    // Serializes audit writes: the lock is held until the writing transaction ends.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AdminAuditChain c where c.id = " + AdminAuditChain.HEAD_ID)
    Optional<AdminAuditChain> lockHead();
}
